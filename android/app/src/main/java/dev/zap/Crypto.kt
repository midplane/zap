package dev.zap

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

fun ByteArray.b64(): String = Base64.getEncoder().encodeToString(this)
fun String.unb64(): ByteArray = Base64.getDecoder().decode(this)

object Crypto {
    fun randomKey() = ByteArray(32).also { SecureRandom().nextBytes(it) }
    fun privateKey(): ByteArray = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private.encoded
    private fun private(raw: ByteArray) = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(raw))
    private fun point(raw: ByteArray): ECPublicKey {
        require(raw.size == 65 && raw[0] == 4.toByte()) { "Invalid device public key" }
        val params = (KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public as ECPublicKey).params
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(java.math.BigInteger(1, raw.copyOfRange(1, 33)), java.math.BigInteger(1, raw.copyOfRange(33, 65))), params)) as ECPublicKey
    }
    private fun publicBytes(key: ECPublicKey): ByteArray {
        fun coordinate(n: java.math.BigInteger): ByteArray { val b = n.toByteArray(); return ByteArray(32 - minOf(32, b.size)) + b.takeLast(32).toByteArray() }
        return byteArrayOf(4) + coordinate(key.w.affineX) + coordinate(key.w.affineY)
    }
    fun identity(): Pair<ByteArray, ByteArray> {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        return pair.private.encoded to publicBytes(pair.public as ECPublicKey)
    }
    fun seal(data: ByteArray, key: ByteArray, aad: String): ByteArray = seal(data, SecretKeySpec(key, "AES"), aad)
    fun open(data: ByteArray, key: ByteArray, aad: String): ByteArray = open(data, SecretKeySpec(key, "AES"), aad)
    fun seal(data: ByteArray, key: SecretKey, aad: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad.toByteArray()); return cipher.iv + cipher.doFinal(data)
    }
    fun open(data: ByteArray, key: SecretKey, aad: String): ByteArray {
        require(data.size >= 28) { "Invalid encrypted content" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data.copyOfRange(0, 12)))
        cipher.updateAAD(aad.toByteArray()); return cipher.doFinal(data.copyOfRange(12, data.size))
    }
    private fun wrapping(private: ByteArray, public: ByteArray): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH"); agreement.init(private(private)); agreement.doPhase(point(public), true)
        val extract = Mac.getInstance("HmacSHA256"); extract.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val prk = extract.doFinal(agreement.generateSecret())
        val expand = Mac.getInstance("HmacSHA256"); expand.init(SecretKeySpec(prk, "HmacSHA256"))
        return expand.doFinal("zap-key".toByteArray() + byteArrayOf(1))
    }
    fun wrap(key: ByteArray, public: ByteArray, keyId: String): JSONObject {
        val (private, ephemeral) = identity()
        val sealed = seal(key, wrapping(private, public), keyId)
        return JSONObject().put("ephemeralPublicKey", ephemeral.b64()).put("nonce", sealed.copyOfRange(0, 12).b64()).put("ciphertext", sealed.copyOfRange(12, sealed.size).b64())
    }
    fun unwrap(envelope: JSONObject, private: ByteArray, keyId: String): ByteArray = open(
        envelope.getString("nonce").unb64() + envelope.getString("ciphertext").unb64(),
        wrapping(private, envelope.getString("ephemeralPublicKey").unb64()), keyId
    )
}

class Identity(context: Context) {
    private val prefs = context.getSharedPreferences("identity", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val protector: SecretKey = (keyStore.getKey("zap.identity", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
        init(KeyGenParameterSpec.Builder("zap.identity", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
    }.generateKey()
    val state: JSONObject = prefs.getString("encrypted", null)?.let { JSONObject(String(Crypto.open(it.unb64(), protector, "identity"))) } ?: run {
        val (private, public) = Crypto.identity()
        JSONObject().put("private", private.b64()).put("public", public.b64()).put("localKey", Crypto.randomKey().b64()).put("keys", JSONObject()).put("url", "").put("token", "").put("deviceId", "").put("keyId", "")
    }
    init { save() }
    fun save() { check(prefs.edit().putString("encrypted", Crypto.seal(state.toString().toByteArray(), protector, "identity").b64()).commit()) { "Could not save device keys" } }
    val connected get() = state.getString("token").isNotEmpty()
    val localKey get() = state.getString("localKey").unb64()
    val keys: JSONObject get() = state.getJSONObject("keys")
}
