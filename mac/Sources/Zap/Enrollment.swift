import Foundation

struct PendingEnrollment: Codable {
    var url: String
    var path: String
    var credential: String
    var body: Data
    var keys: [String: Data]
    var keyId: String

    init(url: String, path: String, credential: String = "", registration: [String: Any], keys: [String: Data], keyId: String) throws {
        self.url = url; self.path = path; self.credential = credential; self.keys = keys; self.keyId = keyId
        var registration = registration
        registration["enrollmentId"] = UUID().uuidString
        registration["deviceToken"] = VaultCrypto.randomKey().map { String(format: "%02x", $0) }.joined()
        body = try JSONSerialization.data(withJSONObject: registration)
    }

    func completed(_ identity: Identity, response: Data) throws -> Identity {
        let auth = try JSONDecoder().decode(EnrollmentCredentials.self, from: response)
        let request = try JSONSerialization.jsonObject(with: body) as? [String: Any]
        guard auth.deviceId == request?["enrollmentId"] as? String, auth.token == request?["deviceToken"] as? String, auth.keyId == keyId else { throw ZapError("The server returned a different enrollment. Update the server and retry.") }
        var next = identity
        next.url = url; next.token = auth.token; next.deviceId = auth.deviceId; next.keys = keys; next.keyId = keyId; next.enrollment = nil
        return next
    }
}
private struct EnrollmentCredentials: Decodable { var deviceId: String; var token: String; var keyId: String }
