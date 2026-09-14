import AppKit
import CryptoKit
import SwiftUI

struct RemoteItem: Decodable {
    var id: String
    var keyId: String
    var createdAt: Int64
}

struct RemoteDevice: Decodable, Identifiable {
    var id: String
    var name: String
    var publicKey: String
}

struct RemoteKey: Decodable {
    var keyId: String
    var envelope: Envelope
}

/// The server state from `/v1/sync`. `items` is nil when the requested cursor is already current.
struct Snapshot: Decodable {
    var cursor: Int
    var days: Int
    var keyId: String
    var keys: [RemoteKey]
    var devices: [RemoteDevice]
    var items: [RemoteItem]?
}

struct PairCode: Codable {
    static let prefix = "zap://pair#"
    var url: String
    var token: String
    var keyId: String
    var keys: [String: String]
}

enum ContentFilter: String, CaseIterable, Identifiable {
    case all = "All"
    case text = "Text"
    case images = "Images"

    var id: Self { self }

    func includes(_ clip: Clip) -> Bool {
        switch self {
        case .all: return true
        case .text: return clip.payload.kind == "text"
        case .images: return clip.payload.kind == "image"
        }
    }
}

struct APIError: LocalizedError {
    var status: Int
    var message: String
    var errorDescription: String? { message }
}

private enum DefaultsKey {
    static let days = "days"
    static let clearPending = "clearPending"
    static let daysPending = "daysPending"
}

private let defaultRetentionDays = 10
private let maxImageBytes = 20 * 1024 * 1024
private let maxImagePixels = 40_000_000
private let maxTextBytes = 1024 * 1024
private let initialRetryDelay: TimeInterval = 2
private let maxRetryDelay: TimeInterval = 120

private func isAllowedServer(_ url: URL) -> Bool {
    url.scheme == "https" || (url.scheme == "http" && ["localhost", "127.0.0.1"].contains(url.host ?? ""))
}

private extension Data {
    init?(base64URLEncoded string: String) {
        var base64 = string.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        base64 += String(repeating: "=", count: (4 - base64.count % 4) % 4)
        self.init(base64Encoded: base64)
    }

    func base64URLEncodedString() -> String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}

@MainActor final class Model: ObservableObject {
    @Published var clips: [Clip] = []
    @Published var query = ""
    @Published var filter = ContentFilter.all
    @Published var selection: String?
    @Published var status = "Local history"
    @Published var error: String?
    @Published var days = defaultRetentionDays
    @Published var devices: [RemoteDevice] = []
    @Published var pairingCode: String?
    @Published var settingsOpen = false
    @Published var preview: Clip?
    @Published var connected = false
    @Published var recovery: String?

    var identity: Identity
    let store: Store
    var paste: (() -> Void)?

    private var unavailableIDs = Set<String>()
    /// The server revision last synced completely, or -1 to request every item.
    private var cursor = -1
    private var syncing = false
    private var enrolling = false
    private var syncRequested = false
    private var forceRequested = false
    private var retryAt = Date.distantPast
    private var retryDelay = initialRetryDelay
    private var syncRetry: Task<Void, Never>?
    private var captureTimer: Timer?
    private var fallbackSyncTimer: Timer?
    private var socket: URLSessionWebSocketTask?
    private var socketLoop: Task<Void, Never>?
    private var changeCount = NSPasteboard.general.changeCount

    init() throws {
        if let saved = try VaultCrypto.loadSecret() {
            identity = try JSONDecoder().decode(Identity.self, from: saved)
        } else {
            identity = Identity.make()
            try VaultCrypto.saveSecret(JSONEncoder().encode(identity))
        }
        store = try Store(key: identity.localKey)
        let savedDays = UserDefaults.standard.integer(forKey: DefaultsKey.days)
        days = savedDays == 0 ? defaultRetentionDays : savedDays
        connected = !identity.token.isEmpty
        try reload()
    }

    var filtered: [Clip] {
        clips.filter { clip in
            guard filter.includes(clip) else { return false }
            guard !query.isEmpty else { return true }
            return (clip.payload.text ?? "Image").localizedCaseInsensitiveContains(query)
                || clip.payload.source.localizedCaseInsensitiveContains(query)
        }
    }

    var selected: Clip? { filtered.first(where: { $0.id == selection }) ?? filtered.first }

    var pendingCount: Int { clips.filter(\.pending).count }

    func start() {
        // NSPasteboard has no change notification, so poll it.
        captureTimer = Timer.scheduledTimer(withTimeInterval: 0.65, repeats: true) { [weak self] _ in
            guard let self else { return }
            Task { @MainActor in self.capture() }
        }
        captureTimer?.tolerance = 0.15
        fallbackSyncTimer = Timer.scheduledTimer(withTimeInterval: 300, repeats: true) { [weak self] _ in
            guard let self else { return }
            Task { @MainActor in await self.sync() }
        }
        fallbackSyncTimer?.tolerance = 30
        Task {
            await sync()
            connectSocket()
        }
    }

    func reload() throws {
        try store.expire(days: days)
        clips = try store.all()
        let unreadable = store.damagedIDs.union(unavailableIDs).count
        recovery = unreadable == 0
            ? nil
            : "Unable to read \(unreadable) saved \(unreadable == 1 ? "item" : "items"). Synced content retries automatically. Damaged local records are kept for recovery."
    }

    func ignoreCurrentClipboard() {
        changeCount = NSPasteboard.general.changeCount
    }

    private func capture() {
        let board = NSPasteboard.general
        guard board.changeCount != changeCount else { return }
        changeCount = board.changeCount
        let content = ClipboardContent.read(from: board)
        let source = NSWorkspace.shared.frontmostApplication?.localizedName ?? "Mac"
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        Task.detached(priority: .userInitiated) { [weak self] in
            do {
                guard let payload = try Model.payload(from: content, source: source, createdAt: now) else { return }
                try await self?.add(payload)
            } catch {
                await self?.report(error)
            }
        }
    }

    nonisolated private static func payload(from content: ClipboardContent, source: String, createdAt: Int64) throws -> Payload? {
        if let image = content.image, let tiff = image.tiffRepresentation, let bitmap = NSBitmapImageRep(data: tiff) {
            guard bitmap.pixelsWide * bitmap.pixelsHigh <= maxImagePixels,
                  let png = bitmap.representation(using: .png, properties: [:]),
                  png.count <= maxImageBytes
            else { throw ZapError("Image is too large. Maximum: 20 MiB and 40 megapixels.") }
            return Payload(kind: "image", png: png.base64EncodedString(), source: source, createdAt: createdAt)
        }
        guard let text = content.text, !text.isEmpty else { return nil }
        // A pairing code carries the group keys. Moving one between devices should not file it in history.
        guard !ClipboardContent.containsPairingCode(text) else { return nil }
        guard text.utf8.count <= maxTextBytes else { throw ZapError("Text is too large. Maximum: 1 MiB.") }
        return Payload(kind: "text", text: text, source: source, createdAt: createdAt)
    }

    private func report(_ error: Error) {
        self.error = error.localizedDescription
    }

    private func add(_ payload: Payload) throws {
        if let previous = clips.first?.payload, previous.kind == payload.kind, previous.text == payload.text, previous.png == payload.png {
            return
        }
        let clip = Clip(id: UUID().uuidString, payload: payload, pending: true)
        try store.save(clip)
        clips.insert(clip, at: 0)
        Task { await sync() }
    }

    func copy(_ clip: Clip, andPaste: Bool = false) {
        let board = NSPasteboard.general
        if clip.payload.kind == "image", let data = Data(base64Encoded: clip.payload.png ?? "") {
            board.clearContents()
            board.setData(data, forType: .png)
        } else if let text = clip.payload.text {
            board.clearContents()
            board.setString(text, forType: .string)
        }
        ignoreCurrentClipboard()
        if andPaste { paste?() } else { status = "Copied" }
    }

    func move(_ delta: Int) {
        let list = filtered
        guard !list.isEmpty else { return }
        let index = list.firstIndex(where: { $0.id == selection }) ?? 0
        selection = list[max(0, min(list.count - 1, index + delta))].id
    }

    func delete(_ clip: Clip) {
        do {
            try store.remove(clip.id, queue: connected)
            try reload()
            Task { await sync() }
        } catch {
            self.error = error.localizedDescription
        }
    }

    func clear() {
        do {
            for record in try store.records() { try store.remove(record.id) }
            unavailableIDs = []
            UserDefaults.standard.set(connected, forKey: DefaultsKey.clearPending)
            try reload()
            Task { await sync() }
        } catch {
            self.error = error.localizedDescription
        }
    }

    func discardDamaged() {
        do {
            for id in store.damagedIDs { try store.remove(id, queue: connected) }
            try reload()
            Task { await sync(force: true) }
        } catch {
            self.error = error.localizedDescription
        }
    }

    func setDays(_ value: Int) {
        days = max(1, min(365, value))
        UserDefaults.standard.set(days, forKey: DefaultsKey.days)
        UserDefaults.standard.set(true, forKey: DefaultsKey.daysPending)
        do { try reload() } catch { self.error = error.localizedDescription }
        Task { await sync() }
    }

    func setup(url: String, token: String) async {
        do {
            guard !connected else { throw ZapError("Disconnect before connecting to another server.") }
            let endpoint = url.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            guard let parsed = URL(string: endpoint), isAllowedServer(parsed) else { throw ZapError("Use an HTTPS server URL.") }
            guard let key = identity.keys[identity.keyId] else { throw ZapError("The encryption key is missing.") }
            if identity.enrollment == nil {
                let envelope = try VaultCrypto.wrap(key, for: identity.publicKey, keyId: identity.keyId)
                let registration: [String: Any] = try [
                    "name": deviceName,
                    "publicKey": identity.publicKey,
                    "keyId": identity.keyId,
                    "envelope": envelopeJSON(envelope),
                ]
                try stage(PendingEnrollment(url: endpoint, path: "/v1/bootstrap", credential: token, registration: registration, keys: identity.keys, keyId: identity.keyId))
            }
            try await finishEnrollment()
            await sync()
            connectSocket()
        } catch let error as APIError where error.status == 409 {
            self.error = "This server already has a history. Get a pairing code from a connected device, then choose Join existing history."
        } catch {
            self.error = error.localizedDescription
        }
    }

    func join(code: String) async {
        do {
            guard !connected else { throw ZapError("Disconnect before joining another history.") }
            if identity.enrollment == nil {
                try stage(pairingEnrollment(from: code))
            }
            try await finishEnrollment()
            await sync()
            connectSocket()
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func pairingEnrollment(from code: String) throws -> PendingEnrollment {
        guard code.hasPrefix(PairCode.prefix) else { throw ZapError("Enter a pairing code from an existing device.") }
        guard let data = Data(base64URLEncoded: String(code.dropFirst(PairCode.prefix.count))) else { throw ZapError("Invalid pairing code.") }
        let pairing = try JSONDecoder().decode(PairCode.self, from: data)
        guard let url = URL(string: pairing.url), isAllowedServer(url) else { throw ZapError("Use an HTTPS server URL.") }
        let keys = try pairing.keys.mapValues { value -> Data in
            guard let key = Data(base64Encoded: value), key.count == 32 else { throw ZapError("Invalid pairing key.") }
            return key
        }
        // Wrap every key, not just the current one, so this Mac can read history from before the last rotation.
        var envelopes: [String: Any] = [:]
        for (keyId, key) in keys {
            envelopes[keyId] = try envelopeJSON(VaultCrypto.wrap(key, for: identity.publicKey, keyId: keyId))
        }
        guard let envelope = envelopes[pairing.keyId] else { throw ZapError("Pairing key is missing.") }
        let registration: [String: Any] = try [
            "token": pairing.token,
            "name": deviceName,
            "publicKey": identity.publicKey,
            "keyId": pairing.keyId,
            "envelope": envelope,
            "historyEnvelopes": envelopes,
        ]
        return try PendingEnrollment(url: pairing.url, path: "/v1/pair", registration: registration, keys: keys, keyId: pairing.keyId)
    }

    /// Saves the enrollment before sending it, so an interrupted attempt resends the identical request.
    private func stage(_ enrollment: PendingEnrollment) throws {
        var next = identity
        next.enrollment = enrollment
        try replaceIdentity(next)
    }

    private func finishEnrollment() async throws {
        guard let pending = identity.enrollment else { return }
        guard !enrolling else { throw ZapError("Connection is already in progress.") }
        enrolling = true
        defer { enrolling = false }
        do {
            let response = try await request(pending.path, method: "POST", body: pending.body, credential: pending.credential, endpoint: pending.url)
            try replaceIdentity(pending.completed(identity, response: response))
            connected = true
            error = nil
            retryAt = .distantPast
        } catch let error as APIError where [400, 401, 409].contains(error.status) {
            // A definitive rejection permits a new attempt. Transport/storage failures retain the request.
            var next = identity
            next.enrollment = nil
            try replaceIdentity(next)
            throw error
        }
    }

    func invite() async {
        do {
            await sync()
            let result = try await request("/v1/invitations", method: "POST")
            guard let token = (try JSONSerialization.jsonObject(with: result) as? [String: String])?["token"], !token.isEmpty else {
                throw ZapError("The server returned an invalid pairing invitation.")
            }
            let code = PairCode(url: identity.url, token: token, keyId: identity.keyId, keys: identity.keys.mapValues { $0.base64EncodedString() })
            pairingCode = try PairCode.prefix + JSONEncoder().encode(code).base64URLEncodedString()
        } catch {
            self.error = error.localizedDescription
        }
    }

    func removeDevice(_ device: RemoteDevice) async {
        do {
            let keyId = UUID().uuidString
            let key = VaultCrypto.randomKey()
            var envelopes: [String: Any] = [:]
            for remaining in devices where remaining.id != device.id {
                envelopes[remaining.id] = try envelopeJSON(VaultCrypto.wrap(key, for: remaining.publicKey, keyId: keyId))
            }
            _ = try await request("/v1/rotate", method: "POST", body: json(["removeDevice": device.id, "keyId": keyId, "envelopes": envelopes]))
            pairingCode = nil
            await sync()
        } catch {
            self.error = error.localizedDescription
        }
    }

    func disconnect() async {
        guard !enrolling else { error = "Wait for the connection attempt to finish."; return }
        guard !syncing else { error = "Wait for the current sync to finish, then disconnect."; return }
        var warning: String?
        if connected {
            closeSocket()
            // Holding the sync flag keeps new syncs from starting while this device is revoked.
            syncing = true
            status = "Disconnecting…"
            do {
                _ = try await request("/v1/devices/me", method: "DELETE")
            } catch let error as APIError where error.status == 401 {
                // Already revoked.
            } catch {
                warning = "Disconnected on this Mac. The server could not be reached, so remove this Mac from another paired device."
            }
            syncing = false
        }
        do { try forget(warning) } catch { self.error = error.localizedDescription }
    }

    /// Return to local-only history, keeping everything captured here for the next server.
    private func forget(_ message: String?) throws {
        closeSocket()
        syncRetry?.cancel()
        syncRetry = nil
        try store.markAllPending()
        unavailableIDs = []
        for id in try store.deletions() { try store.deleted(id) }

        let keyId = UUID().uuidString
        identity.url = ""
        identity.token = ""
        identity.deviceId = ""
        identity.enrollment = nil
        identity.keyId = keyId
        identity.keys = [keyId: VaultCrypto.randomKey()]
        try persist()

        connected = false
        devices = []
        pairingCode = nil
        cursor = -1
        syncRequested = false
        forceRequested = false
        retryAt = .distantPast
        retryDelay = initialRetryDelay
        UserDefaults.standard.removeObject(forKey: DefaultsKey.clearPending)
        // Carry this Mac's retention setting to the next server.
        UserDefaults.standard.set(true, forKey: DefaultsKey.daysPending)
        status = "Local history"
        error = message
        try reload()
    }

    func sync(force: Bool = false) async {
        if !connected, identity.enrollment != nil {
            guard !enrolling else { return }
            do {
                try await finishEnrollment()
                connectSocket()
            } catch {
                self.error = error.localizedDescription
                return
            }
        }
        guard !syncing else {
            syncRequested = true
            forceRequested = forceRequested || force
            return
        }
        guard connected else {
            do { try reload() } catch { self.error = error.localizedDescription }
            return
        }
        guard force || Date() >= retryAt else { return }

        syncing = true
        defer {
            syncing = false
            if syncRequested {
                let nextForce = forceRequested
                syncRequested = false
                forceRequested = false
                Task { await sync(force: nextForce) }
            }
        }
        do {
            try reload()
            unavailableIDs = []
            try await pushLocalChanges()
            // Damaged local rows need the full item list so they can be downloaded again.
            let requestedCursor = store.damagedIDs.isEmpty ? cursor : -1
            let snapshot = try JSONDecoder().decode(Snapshot.self, from: await request("/v1/sync?cursor=\(requestedCursor)"))
            let currentKey = try apply(snapshot)
            if let items = snapshot.items {
                try await reconcile(with: items)
            }
            cursor = unavailableIDs.isEmpty ? snapshot.cursor : -1
            try reload()
            try await uploadPending(key: currentKey)
            try reload()

            status = recovery == nil ? "Up to date" : "Some items need recovery"
            error = nil
            retryDelay = initialRetryDelay
            retryAt = .distantPast
            syncRetry?.cancel()
            syncRetry = nil
        } catch let error as APIError where error.status == 401 {
            do {
                try forget("This Mac is no longer paired. Join your history again with a code from a connected device.")
            } catch {
                self.error = error.localizedDescription
            }
        } catch {
            status = "Offline · will retry"
            self.error = error.localizedDescription
            scheduleRetry()
        }
    }

    private func pushLocalChanges() async throws {
        let defaults = UserDefaults.standard
        if defaults.bool(forKey: DefaultsKey.clearPending) {
            _ = try await request("/v1/items", method: "DELETE")
            defaults.removeObject(forKey: DefaultsKey.clearPending)
        }
        for id in try store.deletions() {
            _ = try await request("/v1/items/\(id)", method: "DELETE")
            try store.deleted(id)
        }
        if defaults.bool(forKey: DefaultsKey.daysPending) {
            let requestedDays = days
            _ = try await request("/v1/settings", method: "PUT", body: json(["days": requestedDays]))
            // Keep the flag if the setting changed again while the request was in flight.
            if days == requestedDays { defaults.removeObject(forKey: DefaultsKey.daysPending) }
        }
    }

    private func apply(_ snapshot: Snapshot) throws -> Data {
        var identityChanged = identity.keyId != snapshot.keyId
        for entry in snapshot.keys where identity.keys[entry.keyId] == nil {
            identity.keys[entry.keyId] = try VaultCrypto.unwrap(entry.envelope, privateKey: identity.privateKey, keyId: entry.keyId)
            identityChanged = true
        }
        guard let currentKey = identity.keys[snapshot.keyId], currentKey.count == 32, (1...365).contains(snapshot.days) else {
            throw ZapError("The server returned invalid sync settings.")
        }
        identity.keyId = snapshot.keyId
        if identityChanged { try persist() }
        devices = snapshot.devices
        if !UserDefaults.standard.bool(forKey: DefaultsKey.daysPending), days != snapshot.days {
            days = snapshot.days
            UserDefaults.standard.set(days, forKey: DefaultsKey.days)
        }
        return currentKey
    }

    private func reconcile(with items: [RemoteItem]) async throws {
        let remoteIDs = Set(items.map(\.id))
        for record in try store.records() where !record.pending && !remoteIDs.contains(record.id) {
            try store.remove(record.id)
        }
        let localIDs = Set(clips.map(\.id))
        let deleted = Set(try store.deletions())
        for item in items where !localIDs.contains(item.id) && !deleted.contains(item.id) {
            do {
                try await download(item)
            } catch let error as APIError where error.status == 401 {
                throw error
            } catch let error as APIError where error.status == 404 {
                continue
            } catch is CancellationError {
                throw CancellationError()
            } catch let error as URLError {
                throw error
            } catch {
                unavailableIDs.insert(item.id)
            }
        }
    }

    private func download(_ item: RemoteItem) async throws {
        guard let key = identity.keys[item.keyId] else { throw ZapError("An encryption key is missing. Pair this device again.") }
        let encrypted = try await request("/v1/items/\(item.id)")
        let payload = try JSONDecoder().decode(Payload.self, from: VaultCrypto.open(encrypted, key: key, aad: item.id))
        guard payload.createdAt == item.createdAt else { throw ZapError("Content metadata did not match.") }
        // The item may have been deleted here while it downloaded.
        if !(try store.deletions()).contains(item.id) {
            try store.save(Clip(id: item.id, payload: payload, pending: false))
        }
    }

    private func uploadPending(key: Data) async throws {
        for clip in clips where clip.pending {
            let encrypted = try VaultCrypto.seal(JSONEncoder().encode(clip.payload), key: key, aad: clip.id)
            let headers = [
                "Content-Type": "application/octet-stream",
                "X-Key-Id": identity.keyId,
                "X-Created-At": String(clip.payload.createdAt),
            ]
            do {
                _ = try await request("/v1/items/\(clip.id)", method: "PUT", body: encrypted, headers: headers)
                try store.sent(clip.id)
            } catch let error as APIError where error.status == 410 {
                // Deleted on another device, or already past retention.
                try store.remove(clip.id)
            }
        }
    }

    private func scheduleRetry() {
        let delay = retryDelay
        retryAt = Date().addingTimeInterval(delay)
        retryDelay = min(delay * 2, maxRetryDelay)
        syncRetry?.cancel()
        syncRetry = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(delay)) } catch { return }
            await self?.sync()
        }
    }

    func connectSocket() {
        guard connected, socketLoop == nil else { return }
        socketLoop = Task { [weak self] in
            guard let self else { return }
            var delay = initialRetryDelay
            while !Task.isCancelled {
                let task = URLSession.shared.webSocketTask(with: eventsRequest())
                socket = task
                task.resume()
                do {
                    await sync(force: true)
                    while !Task.isCancelled {
                        _ = try await task.receive()
                        delay = initialRetryDelay
                        await sync(force: true)
                    }
                } catch {
                    task.cancel(with: .goingAway, reason: nil)
                    do { try await Task.sleep(for: .seconds(delay)) } catch { return }
                    delay = min(delay * 2, maxRetryDelay)
                }
            }
        }
    }

    private func eventsRequest() -> URLRequest {
        var components = URLComponents(string: identity.url + "/v1/events")!
        components.scheme = components.scheme == "https" ? "wss" : "ws"
        var request = URLRequest(url: components.url!)
        request.setValue("Bearer \(identity.token)", forHTTPHeaderField: "Authorization")
        return request
    }

    private func closeSocket() {
        socketLoop?.cancel()
        socketLoop = nil
        socket?.cancel(with: .goingAway, reason: nil)
    }

    private var deviceName: String { Host.current().localizedName ?? "Mac" }

    private func request(
        _ path: String,
        method: String = "GET",
        body: Data? = nil,
        headers: [String: String] = [:],
        credential: String? = nil,
        endpoint: String? = nil
    ) async throws -> Data {
        guard let url = URL(string: (endpoint ?? identity.url) + path) else { throw ZapError("Enter a valid server URL.") }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.httpBody = body
        request.timeoutInterval = 30
        request.setValue("Bearer \(credential ?? identity.token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        for (key, value) in headers { request.setValue(value, forHTTPHeaderField: key) }

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let response = response as? HTTPURLResponse else { throw ZapError("Server did not respond.") }
        guard (200...299).contains(response.statusCode) else {
            let message = (try? JSONSerialization.jsonObject(with: data) as? [String: String])?["error"]
            throw APIError(status: response.statusCode, message: message ?? "Server returned \(response.statusCode).")
        }
        return data
    }

    private func json(_ value: Any) throws -> Data {
        try JSONSerialization.data(withJSONObject: value)
    }

    private func envelopeJSON(_ envelope: Envelope) throws -> Any {
        try JSONSerialization.jsonObject(with: JSONEncoder().encode(envelope))
    }

    private func persist() throws {
        try VaultCrypto.saveSecret(JSONEncoder().encode(identity))
    }

    /// Adopts `next` only once it is saved, so a Keychain failure leaves the previous identity in effect.
    private func replaceIdentity(_ next: Identity) throws {
        try VaultCrypto.saveSecret(JSONEncoder().encode(next))
        identity = next
    }
}
