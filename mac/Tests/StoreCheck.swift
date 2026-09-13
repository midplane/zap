import Foundation

@main struct StoreCheck {
    static func main() throws {
        try enrollmentRecovery()
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let key = VaultCrypto.randomKey()
        let store = try Store(key: key, directory: directory)
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        var clip = Clip(id: UUID().uuidString, payload: Payload(kind: "text", text: "First", source: "Test", createdAt: now), pending: true)
        try store.save(clip)
        try expect(store, text: "First", pending: true)
        try store.sent(clip.id)
        try expect(store, text: "First", pending: false)
        clip.payload.text = "Replacement"
        try store.save(clip)
        try expect(store, text: "Replacement", pending: true)
        try expect(Store(key: key, directory: directory), text: "Replacement", pending: true)
        try store.remove(clip.id)
        let removed = try store.all(); precondition(removed.isEmpty)
        clip.payload.createdAt = 0
        try store.save(clip); try store.expire(days: 10)
        let expired = try store.all(); precondition(expired.isEmpty)

        for _ in 0..<8 {
            try store.save(Clip(id: UUID().uuidString, payload: Payload(kind: "image", png: Data(repeating: 42, count: 1_048_576).base64EncodedString(), source: "Test", createdAt: now), pending: false))
        }
        let reloaded = try Store(key: key, directory: directory)
        let start = Date(); _ = try reloaded.all(); let cold = Date().timeIntervalSince(start)
        let warmStart = Date()
        for _ in 0..<20 { let clips = try reloaded.all(); precondition(clips.count == 8) }
        let warm = Date().timeIntervalSince(warmStart) / 20
        print(String(format: "Store checks passed. Eight 1 MiB payloads: cold %.2f ms, cached %.2f ms per reload.", cold * 1000, warm * 1000))
    }
    static func enrollmentRecovery() throws {
        var identity = Identity.make()
        let pending = try PendingEnrollment(url: "https://zap.test", path: "/v1/bootstrap", credential: "setup", registration: ["keyId": identity.keyId], keys: identity.keys, keyId: identity.keyId)
        identity.enrollment = pending
        let persisted = try JSONEncoder().encode(identity)
        let restored = try JSONDecoder().decode(Identity.self, from: persisted)
        precondition(restored.enrollment?.body == pending.body)
        let request = try JSONSerialization.jsonObject(with: pending.body) as! [String: Any]
        let response = try JSONSerialization.data(withJSONObject: ["deviceId": request["enrollmentId"]!, "token": request["deviceToken"]!, "keyId": identity.keyId])
        let completed = try pending.completed(restored, response: response)
        precondition(completed.enrollment == nil && completed.url == "https://zap.test" && !completed.token.isEmpty)
        // Until that candidate is saved successfully, the old identity still resumes the exact request.
        precondition(restored.enrollment?.body == pending.body && restored.token.isEmpty)
        let retry = try JSONDecoder().decode(Identity.self, from: persisted)
        let retried = try retry.enrollment!.completed(retry, response: response)
        precondition(retried.token == completed.token && retried.keys == completed.keys)
        do {
            _ = try pending.completed(restored, response: Data("{\"deviceId\":\"different\",\"token\":\"wrong\",\"keyId\":\"wrong\"}".utf8))
            fatalError("Accepted a mismatched enrollment response")
        } catch {}
    }
    static func expect(_ store: Store, text: String, pending: Bool) throws {
        let clips = try store.all()
        precondition(clips.count == 1 && clips[0].payload.text == text && clips[0].pending == pending)
    }
}
