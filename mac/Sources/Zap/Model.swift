import AppKit
import CryptoKit
import SwiftUI

struct RemoteItem: Decodable { var id: String; var keyId: String; var createdAt: Int64 }
struct RemoteDevice: Decodable, Identifiable { var id: String; var name: String; var publicKey: String }
struct RemoteKey: Decodable { var keyId: String; var envelope: Envelope }
struct Snapshot: Decodable { var cursor: Int; var days: Int; var keyId: String; var keys: [RemoteKey]; var devices: [RemoteDevice]; var items: [RemoteItem]? }
struct Credentials: Decodable { var deviceId: String; var token: String; var keyId: String }
struct PairCode: Codable { var url: String; var token: String; var keyId: String; var keys: [String: String] }

@MainActor final class Model: ObservableObject {
    @Published var clips: [Clip] = []
    @Published var query = ""
    @Published var filter = "All"
    @Published var selection: String?
    @Published var status = "Local history"
    @Published var error: String?
    @Published var days = 10
    @Published var devices: [RemoteDevice] = []
    @Published var pairingCode: String?
    @Published var settingsOpen = false
    @Published var preview: Clip?
    @Published var connected = false
    var identity: Identity
    let store: Store
    private var cursor = -1
    private var syncing = false
    private var timer: Timer?
    private var syncTimer: Timer?
    private var socket: URLSessionWebSocketTask?
    private var socketLoop: Task<Void, Never>?
    private var changeCount = NSPasteboard.general.changeCount
    private var syncRequested = false
    private var retryAt = Date.distantPast
    private var retryDelay: TimeInterval = 2
    private var syncRetry: Task<Void, Never>?
    var paste: (() -> Void)?

    init() throws {
        if let data = try VaultCrypto.loadSecret() { identity = try JSONDecoder().decode(Identity.self, from: data) }
        else { identity = Identity.make(); try VaultCrypto.saveSecret(JSONEncoder().encode(identity)) }
        store = try Store(key: identity.localKey)
        days = UserDefaults.standard.integer(forKey: "days"); if days == 0 { days = 10 }
        connected = !identity.token.isEmpty
        try reload()
    }
    var filtered: [Clip] {
        clips.filter { (filter == "All" || $0.payload.kind == (filter == "Text" ? "text" : "image")) && (query.isEmpty || ($0.payload.text ?? "Image").localizedCaseInsensitiveContains(query) || $0.payload.source.localizedCaseInsensitiveContains(query)) }
    }
    var selected: Clip? { filtered.first(where: { $0.id == selection }) ?? filtered.first }
    var pendingCount: Int { clips.filter(\.pending).count }
    func start() {
        timer = Timer.scheduledTimer(withTimeInterval: 0.65, repeats: true) { [weak self] _ in guard let self else { return }; Task { @MainActor in self.capture() } }
        timer?.tolerance = 0.15
        syncTimer = Timer.scheduledTimer(withTimeInterval: 300, repeats: true) { [weak self] _ in guard let self else { return }; Task { @MainActor in await self.sync() } }
        syncTimer?.tolerance = 30
        Task { await sync(); connectSocket() }
    }
    func persist() throws { try VaultCrypto.saveSecret(JSONEncoder().encode(identity)) }
    func reload() throws { try store.expire(days: days); clips = try store.all() }
    func ignoreCurrentClipboard() { changeCount = NSPasteboard.general.changeCount }
    func capture() {
        let board = NSPasteboard.general
        guard board.changeCount != changeCount else { return }; changeCount = board.changeCount
        do {
            let source = NSWorkspace.shared.frontmostApplication?.localizedName ?? "Mac"
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            if let image = ClipboardImage.read(from: board), let tiff = image.tiffRepresentation, let bitmap = NSBitmapImageRep(data: tiff) {
                guard bitmap.pixelsWide * bitmap.pixelsHigh <= 40_000_000, let png = bitmap.representation(using: .png, properties: [:]), png.count <= 20 * 1024 * 1024 else { throw ZapError("Image is too large. Maximum: 20 MiB and 40 megapixels.") }
                try add(Payload(kind: "image", png: png.base64EncodedString(), source: source, createdAt: now))
            } else if let text = board.string(forType: .string), !text.isEmpty {
                guard text.utf8.count <= 1024 * 1024 else { throw ZapError("Text is too large. Maximum: 1 MiB.") }
                try add(Payload(kind: "text", text: text, source: source, createdAt: now))
            }
        } catch { self.error = error.localizedDescription }
    }
    func add(_ payload: Payload) throws {
        if let previous = clips.first?.payload, previous.kind == payload.kind, previous.text == payload.text, previous.png == payload.png { return }
        let clip = Clip(id: UUID().uuidString, payload: payload, pending: true)
        try store.save(clip); clips.insert(clip, at: 0)
        Task { await sync() }
    }
    func copy(_ clip: Clip, andPaste: Bool = false) {
        let board = NSPasteboard.general
        if clip.payload.kind == "image", let data = Data(base64Encoded: clip.payload.png ?? "") {
            board.clearContents(); board.setData(data, forType: .png)
        } else if let text = clip.payload.text { board.clearContents(); board.setString(text, forType: .string) }
        changeCount = board.changeCount
        if andPaste { paste?() } else { status = "Copied" }
    }
    func move(_ delta: Int) {
        let list = filtered; guard !list.isEmpty else { return }
        let index = list.firstIndex(where: { $0.id == selection }) ?? 0
        selection = list[max(0, min(list.count - 1, index + delta))].id
    }
    func delete(_ clip: Clip) {
        do { try store.remove(clip.id, queue: connected); try reload(); Task { await sync() } }
        catch { self.error = error.localizedDescription }
    }
    func clear() {
        do {
            for clip in clips { try store.remove(clip.id, queue: connected) }
            UserDefaults.standard.set(connected, forKey: "clearPending")
            try reload(); Task { await sync() }
        } catch { self.error = error.localizedDescription }
    }
    func setDays(_ value: Int) {
        days = max(1, min(365, value)); UserDefaults.standard.set(days, forKey: "days")
        UserDefaults.standard.set(true, forKey: "daysPending")
        do { try reload() } catch { self.error = error.localizedDescription }
        Task { await sync() }
    }
    func request(_ path: String, method: String = "GET", body: Data? = nil, headers: [String: String] = [:], credential: String? = nil, endpoint: String? = nil) async throws -> Data {
        guard let url = URL(string: (endpoint ?? identity.url) + path) else { throw ZapError("Enter a valid server URL.") }
        var request = URLRequest(url: url); request.httpMethod = method; request.httpBody = body; request.timeoutInterval = 30
        request.setValue("Bearer \(credential ?? identity.token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        for (key, value) in headers { request.setValue(value, forHTTPHeaderField: key) }
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let response = response as? HTTPURLResponse else { throw ZapError("Server did not respond.") }
        if !(200...299).contains(response.statusCode) {
            let message = (try? JSONSerialization.jsonObject(with: data) as? [String: String])?["error"] ?? "Server returned \(response.statusCode)."
            throw APIError(status: response.statusCode, message: message)
        }
        return data
    }
    func json(_ value: Any) throws -> Data { try JSONSerialization.data(withJSONObject: value) }
    func envelopeJSON(_ envelope: Envelope) throws -> Any { try JSONSerialization.jsonObject(with: JSONEncoder().encode(envelope)) }
    func setup(url: String, token: String) async {
        do {
            guard !connected else { throw ZapError("Disconnect before connecting to another server.") }
            let endpoint = url.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            guard let parsed = URL(string: endpoint), parsed.scheme == "https" || (parsed.scheme == "http" && ["localhost", "127.0.0.1"].contains(parsed.host ?? "")) else { throw ZapError("Use an HTTPS server URL.") }
            guard let key = identity.keys[identity.keyId] else { throw ZapError("The encryption key is missing.") }
            let envelope = try VaultCrypto.wrap(key, for: identity.publicKey, keyId: identity.keyId)
            let data = try await request("/v1/bootstrap", method: "POST", body: json(["name": Host.current().localizedName ?? "Mac", "publicKey": identity.publicKey, "keyId": identity.keyId, "envelope": envelopeJSON(envelope)]), credential: token, endpoint: endpoint)
            let auth = try JSONDecoder().decode(Credentials.self, from: data)
            identity.url = endpoint; identity.token = auth.token; identity.deviceId = auth.deviceId
            try persist(); connected = true; error = nil; retryAt = .distantPast
            await sync(); connectSocket()
        } catch let error as APIError where error.status == 409 {
            self.error = "This server already has a history. Get a pairing code from a connected device, then choose Join existing history."
        } catch { self.error = error.localizedDescription }
    }
    func disconnect() {
        guard !syncing else { error = "Wait for the current sync to finish, then disconnect."; return }
        do {
            socketLoop?.cancel(); socketLoop = nil; socket?.cancel(with: .goingAway, reason: nil)
            syncRetry?.cancel(); syncRetry = nil
            for var clip in clips { clip.pending = true; try store.save(clip) }
            for id in try store.deletions() { try store.deleted(id) }
            let keyId = UUID().uuidString
            identity.url = ""; identity.token = ""; identity.deviceId = ""
            identity.keyId = keyId; identity.keys = [keyId: VaultCrypto.randomKey()]
            try persist(); connected = false; devices = []; pairingCode = nil; cursor = -1
            UserDefaults.standard.removeObject(forKey: "clearPending")
            UserDefaults.standard.set(true, forKey: "daysPending")
            status = "Local history"; error = nil; try reload()
        } catch { self.error = error.localizedDescription }
    }
    func join(code: String) async {
        do {
            guard !connected else { throw ZapError("Disconnect before joining another history.") }
            guard code.hasPrefix("zap://pair#") else { throw ZapError("Enter a pairing code from an existing device.") }
            var encoded = String(code.dropFirst(11)).replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
            encoded += String(repeating: "=", count: (4 - encoded.count % 4) % 4)
            guard let data = Data(base64Encoded: encoded) else { throw ZapError("Invalid pairing code.") }
            let pairing = try JSONDecoder().decode(PairCode.self, from: data)
            guard let url = URL(string: pairing.url), url.scheme == "https" || (url.scheme == "http" && ["localhost", "127.0.0.1"].contains(url.host ?? "")) else { throw ZapError("Use an HTTPS server URL.") }
            let keys = try pairing.keys.mapValues { value -> Data in
                guard let data = Data(base64Encoded: value), data.count == 32 else { throw ZapError("Invalid pairing key.") }; return data
            }
            var envelopes: [String: Any] = [:]
            for (keyId, key) in keys { envelopes[keyId] = try envelopeJSON(VaultCrypto.wrap(key, for: identity.publicKey, keyId: keyId)) }
            guard let envelope = envelopes[pairing.keyId] else { throw ZapError("Pairing key is missing.") }
            let auth = try JSONDecoder().decode(Credentials.self, from: await request("/v1/pair", method: "POST", body: json(["token": pairing.token, "name": Host.current().localizedName ?? "Mac", "publicKey": identity.publicKey, "keyId": pairing.keyId, "envelope": envelope, "historyEnvelopes": envelopes]), credential: "", endpoint: pairing.url))
            identity.url = pairing.url; identity.token = auth.token; identity.deviceId = auth.deviceId; identity.keyId = pairing.keyId; identity.keys = keys
            try persist(); connected = true; error = nil; retryAt = .distantPast; await sync(); connectSocket()
        } catch { self.error = error.localizedDescription }
    }
    func invite() async {
        do {
            await sync()
            let result = try await request("/v1/invitations", method: "POST")
            guard let token = (try JSONSerialization.jsonObject(with: result) as? [String: String])?["token"], !token.isEmpty else { throw ZapError("The server returned an invalid pairing invitation.") }
            let code = PairCode(url: identity.url, token: token, keyId: identity.keyId, keys: identity.keys.mapValues { $0.base64EncodedString() })
            pairingCode = "zap://pair#" + (try JSONEncoder().encode(code)).base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
        } catch { self.error = error.localizedDescription }
    }
    func removeDevice(_ device: RemoteDevice) async {
        do {
            let keyId = UUID().uuidString, key = VaultCrypto.randomKey()
            var envelopes: [String: Any] = [:]
            for d in devices where d.id != device.id { envelopes[d.id] = try envelopeJSON(VaultCrypto.wrap(key, for: d.publicKey, keyId: keyId)) }
            _ = try await request("/v1/rotate", method: "POST", body: json(["removeDevice": device.id, "keyId": keyId, "envelopes": envelopes]))
            pairingCode = nil; await sync()
        } catch { self.error = error.localizedDescription }
    }
    func sync(force: Bool = false) async {
        guard !syncing else { syncRequested = true; return }
        if !connected {
            do { try reload() } catch { self.error = error.localizedDescription }
            return
        }
        guard force || Date() >= retryAt else { return }
        syncing = true
        defer {
            syncing = false
            if syncRequested { syncRequested = false; Task { await sync() } }
        }
        do {
            try reload()
            if UserDefaults.standard.bool(forKey: "clearPending") {
                _ = try await request("/v1/items", method: "DELETE"); UserDefaults.standard.removeObject(forKey: "clearPending")
            }
            for id in try store.deletions() { _ = try await request("/v1/items/\(id)", method: "DELETE"); try store.deleted(id) }
            if UserDefaults.standard.bool(forKey: "daysPending") {
                let requestedDays = days
                _ = try await request("/v1/settings", method: "PUT", body: json(["days": requestedDays]))
                if days == requestedDays { UserDefaults.standard.removeObject(forKey: "daysPending") }
            }
            let snapshot = try JSONDecoder().decode(Snapshot.self, from: await request("/v1/sync?cursor=\(cursor)"))
            var identityChanged = identity.keyId != snapshot.keyId
            for entry in snapshot.keys where identity.keys[entry.keyId] == nil { identity.keys[entry.keyId] = try VaultCrypto.unwrap(entry.envelope, privateKey: identity.privateKey, keyId: entry.keyId); identityChanged = true }
            guard let currentKey = identity.keys[snapshot.keyId], currentKey.count == 32, (1...365).contains(snapshot.days) else { throw ZapError("The server returned invalid sync settings.") }
            identity.keyId = snapshot.keyId; if identityChanged { try persist() }; devices = snapshot.devices
            if !UserDefaults.standard.bool(forKey: "daysPending"), days != snapshot.days { days = snapshot.days; UserDefaults.standard.set(days, forKey: "days") }
            if let items = snapshot.items {
                let remoteIDs = Set(items.map(\.id))
                for clip in clips where !clip.pending && !remoteIDs.contains(clip.id) { try store.remove(clip.id) }
                let localIDs = Set(clips.map(\.id)), deleted = Set(try store.deletions())
                for item in items where !localIDs.contains(item.id) && !deleted.contains(item.id) {
                    guard let key = identity.keys[item.keyId] else { throw ZapError("An encryption key is missing. Pair this device again.") }
                    do {
                        let encrypted = try await request("/v1/items/\(item.id)")
                        let payload = try JSONDecoder().decode(Payload.self, from: VaultCrypto.open(encrypted, key: key, aad: item.id))
                        guard payload.createdAt == item.createdAt else { throw ZapError("Content metadata did not match.") }
                        if !(try store.deletions()).contains(item.id) { try store.save(Clip(id: item.id, payload: payload, pending: false)) }
                    } catch let error as APIError where error.status == 404 { continue }
                }
            }
            cursor = snapshot.cursor
            try reload()
            for clip in clips where clip.pending {
                let encrypted = try VaultCrypto.seal(JSONEncoder().encode(clip.payload), key: currentKey, aad: clip.id)
                do {
                    _ = try await request("/v1/items/\(clip.id)", method: "PUT", body: encrypted, headers: ["Content-Type": "application/octet-stream", "X-Key-Id": identity.keyId, "X-Created-At": String(clip.payload.createdAt)])
                    try store.sent(clip.id)
                } catch let error as APIError where error.status == 410 { try store.remove(clip.id) }
            }
            try reload(); status = "Up to date"; error = nil; retryDelay = 2; retryAt = .distantPast
            syncRetry?.cancel(); syncRetry = nil
        } catch {
            status = "Offline · will retry"; self.error = error.localizedDescription
            let delay = retryDelay
            retryAt = Date().addingTimeInterval(delay); retryDelay = min(delay * 2, 120)
            syncRetry?.cancel()
            syncRetry = Task { [weak self] in
                do { try await Task.sleep(for: .seconds(delay)) } catch { return }
                await self?.sync()
            }
        }
    }
    func connectSocket() {
        guard connected, socketLoop == nil else { return }
        socketLoop = Task { [weak self] in
            guard let self else { return }
            var delay: TimeInterval = 2
            while !Task.isCancelled {
                var components = URLComponents(string: identity.url + "/v1/events")!
                components.scheme = components.scheme == "https" ? "wss" : "ws"
                var request = URLRequest(url: components.url!); request.setValue("Bearer \(identity.token)", forHTTPHeaderField: "Authorization")
                let task = URLSession.shared.webSocketTask(with: request); socket = task; task.resume()
                do {
                    await sync(force: true)
                    while !Task.isCancelled { _ = try await task.receive(); delay = 2; await sync(force: true) }
                } catch {
                    task.cancel(with: .goingAway, reason: nil)
                    do { try await Task.sleep(for: .seconds(delay)) } catch { return }
                    delay = min(delay * 2, 120)
                }
            }
        }
    }
}
struct APIError: LocalizedError { var status: Int; var message: String; var errorDescription: String? { message } }
