import Foundation

@main struct StoreCheck {
    static func main() throws {
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
    static func expect(_ store: Store, text: String, pending: Bool) throws {
        let clips = try store.all()
        precondition(clips.count == 1 && clips[0].payload.text == text && clips[0].pending == pending)
    }
}
