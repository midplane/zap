import Foundation

@main struct CryptoCheck {
    static func main() throws {
        let data = try Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))
        let vector = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        let id = vector["id"] as! String, key = Data(base64Encoded: vector["key"] as! String)!, sealed = Data(base64Encoded: vector["sealed"] as! String)!
        let plaintext = try VaultCrypto.open(sealed, key: key, aad: id)
        precondition(String(data: plaintext, encoding: .utf8) == vector["plaintext"] as? String)
        let envelope = try JSONDecoder().decode(Envelope.self, from: JSONSerialization.data(withJSONObject: vector["envelope"]!))
        let unwrapped = try VaultCrypto.unwrap(envelope, privateKey: Data(base64Encoded: vector["privateKeyRaw"] as! String)!, keyId: id)
        precondition(unwrapped == key)
        let roundtrip = try VaultCrypto.wrap(key, for: vector["publicKey"] as! String, keyId: id)
        let reopened = try VaultCrypto.unwrap(roundtrip, privateKey: Data(base64Encoded: vector["privateKeyRaw"] as! String)!, keyId: id)
        precondition(reopened == key)
        var tampered = sealed; tampered[tampered.count - 1] ^= 1
        do { _ = try VaultCrypto.open(tampered, key: key, aad: id); fatalError("Accepted tampered content") } catch {}
        print("Swift crypto vectors and tamper rejection passed")
    }
}
