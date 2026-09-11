import Foundation
import CryptoKit
import Security

struct Envelope: Codable {
    var ephemeralPublicKey: String
    var nonce: String
    var ciphertext: String
}

enum VaultCrypto {
    static func randomKey() -> Data { SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) } }
    static func seal(_ data: Data, key: Data, aad: String) throws -> Data {
        try AES.GCM.seal(data, using: SymmetricKey(data: key), authenticating: Data(aad.utf8)).combined!
    }
    static func open(_ data: Data, key: Data, aad: String) throws -> Data {
        try AES.GCM.open(AES.GCM.SealedBox(combined: data), using: SymmetricKey(data: key), authenticating: Data(aad.utf8))
    }
    static func wrap(_ key: Data, for publicKey: String, keyId: String) throws -> Envelope {
        guard let raw = Data(base64Encoded: publicKey) else { throw ZapError("Invalid device key.") }
        let ephemeral = P256.KeyAgreement.PrivateKey()
        let shared = try ephemeral.sharedSecretFromKeyAgreement(with: P256.KeyAgreement.PublicKey(x963Representation: raw))
        let wrapping = shared.hkdfDerivedSymmetricKey(using: SHA256.self, salt: Data(), sharedInfo: Data("zap-key".utf8), outputByteCount: 32)
        let box = try AES.GCM.seal(key, using: wrapping, authenticating: Data(keyId.utf8))
        return Envelope(ephemeralPublicKey: ephemeral.publicKey.x963Representation.base64EncodedString(), nonce: Data(box.nonce).base64EncodedString(), ciphertext: (box.ciphertext + box.tag).base64EncodedString())
    }
    static func unwrap(_ envelope: Envelope, privateKey: Data, keyId: String) throws -> Data {
        guard let pub = Data(base64Encoded: envelope.ephemeralPublicKey), let nonce = Data(base64Encoded: envelope.nonce), let body = Data(base64Encoded: envelope.ciphertext) else { throw ZapError("Invalid encrypted key.") }
        let key = try P256.KeyAgreement.PrivateKey(rawRepresentation: privateKey)
        let shared = try key.sharedSecretFromKeyAgreement(with: P256.KeyAgreement.PublicKey(x963Representation: pub))
        let wrapping = shared.hkdfDerivedSymmetricKey(using: SHA256.self, salt: Data(), sharedInfo: Data("zap-key".utf8), outputByteCount: 32)
        return try AES.GCM.open(AES.GCM.SealedBox(combined: nonce + body), using: wrapping, authenticating: Data(keyId.utf8))
    }
    static func loadSecret() throws -> Data? {
        var result: CFTypeRef?
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "dev.zap.vault", kSecAttrAccount as String: "identity", kSecReturnData as String: true]
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw ZapError("Unlock your login keychain to open Zap.") }
        return result as? Data
    }
    static func saveSecret(_ data: Data) throws {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "dev.zap.vault", kSecAttrAccount as String: "identity"]
        let status = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var insert = query; insert[kSecValueData as String] = data
            insert[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            guard SecItemAdd(insert as CFDictionary, nil) == errSecSuccess else { throw ZapError("Could not save encryption keys to Keychain.") }; return
        }
        guard status == errSecSuccess else { throw ZapError("Could not update encryption keys.") }
    }
}
struct ZapError: LocalizedError { var message: String; init(_ message: String) { self.message = message }; var errorDescription: String? { message } }
