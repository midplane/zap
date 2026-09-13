import Foundation
import SQLite3

@main struct StoreCheck {
    static func main() throws {
        try enrollmentRecovery()
        try corruptionRecovery()
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
    static func corruptionRecovery() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let key = VaultCrypto.randomKey(), store = try Store(key: key, directory: directory)
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let healthy = Clip(id: UUID().uuidString, payload: Payload(kind: "text", text: "Healthy", source: "Test", createdAt: now), pending: false)
        var damaged = healthy; damaged.id = UUID().uuidString
        var unsent = healthy; unsent.id = UUID().uuidString; unsent.pending = true; unsent.payload.createdAt = 0
        var malformed = healthy; malformed.id = UUID().uuidString
        for clip in [healthy, damaged, unsent, malformed] { try store.save(clip) }
        var db: OpaquePointer?
        precondition(sqlite3_open(directory.appendingPathComponent("history.sqlite").path, &db) == SQLITE_OK)
        defer { sqlite3_close(db) }
        precondition(sqlite3_exec(db, "UPDATE clips SET payload='broken' WHERE id IN ('\(damaged.id)','\(unsent.id)')", nil, nil, nil) == SQLITE_OK)
        let invalid = try VaultCrypto.seal(Data("{}".utf8), key: key, aad: malformed.id).base64EncodedString()
        precondition(sqlite3_exec(db, "UPDATE clips SET payload='\(invalid)' WHERE id='\(malformed.id)'", nil, nil, nil) == SQLITE_OK)
        let reopened = try Store(key: key, directory: directory)
        let loaded = try reopened.all()
        precondition(loaded.map(\.id) == [healthy.id] && reopened.damagedIDs.count == 3)
        try reopened.expire(days: 1)
        let records = try reopened.records()
        precondition(records.count == 4 && records.contains(where: { $0.id == unsent.id && $0.pending }))
        let restarted = try Store(key: key, directory: directory)
        _ = try restarted.all(); precondition(restarted.damagedIDs.count == 3)
        // A valid redownload repairs one row without losing unreadable unsent content.
        try restarted.save(damaged)
        let repaired = try restarted.all()
        precondition(repaired.count == 2 && restarted.damagedIDs.count == 2)
        try restarted.markAllPending()
        let pending = try restarted.records(); precondition(pending.allSatisfy(\.pending))
        for id in restarted.damagedIDs { try restarted.remove(id) }
        let remaining = try restarted.records(); precondition(remaining.count == 2)
    }
    static func expect(_ store: Store, text: String, pending: Bool) throws {
        let clips = try store.all()
        precondition(clips.count == 1 && clips[0].payload.text == text && clips[0].pending == pending)
    }
}
