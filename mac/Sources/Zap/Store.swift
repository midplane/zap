import Foundation
import CryptoKit
import SQLite3

struct Payload: Codable {
    var kind: String
    var text: String?
    var png: String?
    var source: String
    var createdAt: Int64
}
struct Clip: Identifiable {
    var id: String
    var payload: Payload
    var pending: Bool
    var date: Date { Date(timeIntervalSince1970: Double(payload.createdAt) / 1000) }
}
struct Identity: Codable {
    var privateKey: Data
    var localKey: Data
    var keys: [String: Data]
    var keyId: String
    var url: String = ""
    var token: String = ""
    var deviceId: String = ""
    static func make() -> Identity {
        let id = UUID().uuidString
        return Identity(privateKey: P256.KeyAgreement.PrivateKey().rawRepresentation, localKey: VaultCrypto.randomKey(), keys: [id: VaultCrypto.randomKey()], keyId: id)
    }
    var publicKey: String { get throws { try P256.KeyAgreement.PrivateKey(rawRepresentation: privateKey).publicKey.x963Representation.base64EncodedString() } }
}
final class Store {
    private var db: OpaquePointer?
    private let localKey: Data
    init(key: Data) throws {
        localKey = key
        let directory = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true).appendingPathComponent("Zap")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        guard sqlite3_open(directory.appendingPathComponent("history.sqlite").path, &db) == SQLITE_OK else { throw ZapError("Could not open local history.") }
        try execute("PRAGMA journal_mode=WAL")
        try execute("CREATE TABLE IF NOT EXISTS clips(id TEXT PRIMARY KEY, created INTEGER, payload TEXT, pending INTEGER)")
        try execute("CREATE TABLE IF NOT EXISTS deletions(id TEXT PRIMARY KEY)")
    }
    deinit { sqlite3_close(db) }
    private func execute(_ sql: String, _ args: [String] = []) throws {
        let statement = try prepare(sql, args); defer { sqlite3_finalize(statement) }
        var result = sqlite3_step(statement)
        while result == SQLITE_ROW { result = sqlite3_step(statement) }
        guard result == SQLITE_DONE else { throw ZapError("Could not save local history.") }
    }
    private func prepare(_ sql: String, _ args: [String]) throws -> OpaquePointer {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK, let statement else { throw ZapError("Could not read local history.") }
        for (i, value) in args.enumerated() { sqlite3_bind_text(statement, Int32(i + 1), value, -1, unsafeBitCast(-1, to: sqlite3_destructor_type.self)) }
        return statement
    }
    private func rows(_ sql: String, _ args: [String] = []) throws -> [[String]] {
        let statement = try prepare(sql, args); defer { sqlite3_finalize(statement) }
        var result: [[String]] = []
        while sqlite3_step(statement) == SQLITE_ROW {
            result.append((0..<sqlite3_column_count(statement)).map { String(cString: sqlite3_column_text(statement, $0)) })
        }
        return result
    }
    func all() throws -> [Clip] {
        try rows("SELECT id,payload,pending FROM clips ORDER BY created DESC").map { row in
            guard let encrypted = Data(base64Encoded: row[1]) else { throw ZapError("Local history is damaged.") }
            let payload = try JSONDecoder().decode(Payload.self, from: VaultCrypto.open(encrypted, key: localKey, aad: row[0]))
            return Clip(id: row[0], payload: payload, pending: row[2] == "1")
        }
    }
    func save(_ clip: Clip) throws {
        let encrypted = try VaultCrypto.seal(JSONEncoder().encode(clip.payload), key: localKey, aad: clip.id)
        try execute("INSERT OR REPLACE INTO clips VALUES(?,?,?,?)", [clip.id, String(clip.payload.createdAt), encrypted.base64EncodedString(), clip.pending ? "1" : "0"])
    }
    func sent(_ id: String) throws { try execute("UPDATE clips SET pending=0 WHERE id=?", [id]) }
    func remove(_ id: String, queue: Bool = false) throws {
        if queue { try execute("INSERT OR IGNORE INTO deletions VALUES(?)", [id]) }
        try execute("DELETE FROM clips WHERE id=?", [id])
    }
    func deletions() throws -> [String] { try rows("SELECT id FROM deletions").map { $0[0] } }
    func deleted(_ id: String) throws { try execute("DELETE FROM deletions WHERE id=?", [id]) }
    func expire(days: Int) throws { try execute("DELETE FROM clips WHERE created<?", [String(Int64(Date().timeIntervalSince1970 * 1000) - Int64(days) * 86_400_000)]) }
}
