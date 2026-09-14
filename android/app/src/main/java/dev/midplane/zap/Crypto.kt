package dev.midplane.zap

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
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

private const val NONCE_SIZE = 12
private const val TAG_BITS = 128
private const val COORDINATE_SIZE = 32

object Crypto {
    private val curve: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC")
            .apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
    }

    fun randomKey() = ByteArray(32).also { SecureRandom().nextBytes(it) }

    fun identity(): Pair<ByteArray, ByteArray> {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        return pair.private.encoded to encodePublicKey(pair.public as ECPublicKey)
    }

    fun seal(data: ByteArray, key: ByteArray, aad: String): ByteArray = seal(data, SecretKeySpec(key, "AES"), aad)

    fun open(data: ByteArray, key: ByteArray, aad: String): ByteArray = open(data, SecretKeySpec(key, "AES"), aad)

    fun seal(data: ByteArray, key: SecretKey, aad: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad.toByteArray())
        return cipher.iv + cipher.doFinal(data)
    }

    fun open(data: ByteArray, key: SecretKey, aad: String): ByteArray {
        require(data.size >= NONCE_SIZE + TAG_BITS / 8) { "Invalid encrypted content" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, data.copyOfRange(0, NONCE_SIZE)))
        cipher.updateAAD(aad.toByteArray())
        return cipher.doFinal(data.copyOfRange(NONCE_SIZE, data.size))
    }

    fun wrap(key: ByteArray, publicKey: ByteArray, keyId: String): JSONObject {
        val (ephemeralPrivate, ephemeralPublic) = identity()
        val sealed = seal(key, wrappingKey(ephemeralPrivate, publicKey), keyId)
        return JSONObject()
            .put("ephemeralPublicKey", ephemeralPublic.b64())
            .put("nonce", sealed.copyOfRange(0, NONCE_SIZE).b64())
            .put("ciphertext", sealed.copyOfRange(NONCE_SIZE, sealed.size).b64())
    }

    fun unwrap(envelope: JSONObject, privateKey: ByteArray, keyId: String): ByteArray {
        val sealed = envelope.getString("nonce").unb64() + envelope.getString("ciphertext").unb64()
        return open(sealed, wrappingKey(privateKey, envelope.getString("ephemeralPublicKey").unb64()), keyId)
    }

    /** ECDH, then HKDF-SHA256 (RFC 5869) with an empty salt and "zap-key" info. Must match the Mac client. */
    private fun wrappingKey(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(privateKey)))
        agreement.doPhase(decodePublicKey(publicKey), true)
        // An empty salt means a hash-length string of zeros.
        val pseudorandomKey = hmacSha256(ByteArray(32), agreement.generateSecret())
        // A 32-byte output needs only the first expansion block.
        return hmacSha256(pseudorandomKey, "zap-key".toByteArray() + byteArrayOf(1))
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun decodePublicKey(raw: ByteArray): ECPublicKey {
        require(raw.size == 1 + 2 * COORDINATE_SIZE && raw[0] == 4.toByte()) { "Invalid device public key" }
        val x = BigInteger(1, raw.copyOfRange(1, 1 + COORDINATE_SIZE))
        val y = BigInteger(1, raw.copyOfRange(1 + COORDINATE_SIZE, raw.size))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), curve)) as ECPublicKey
    }

    private fun encodePublicKey(key: ECPublicKey): ByteArray {
        // BigInteger.toByteArray may add a sign byte or omit leading zeros; normalize to exactly 32 bytes.
        fun coordinate(value: BigInteger): ByteArray {
            val bytes = value.toByteArray()
            return ByteArray(COORDINATE_SIZE - minOf(COORDINATE_SIZE, bytes.size)) + bytes.takeLast(COORDINATE_SIZE).toByteArray()
        }
        return byteArrayOf(4) + coordinate(key.w.affineX) + coordinate(key.w.affineY)
    }
}

class Identity(context: Context) {
    private val prefs = context.getSharedPreferences("identity", Context.MODE_PRIVATE)
    private val protector: SecretKey = loadOrCreateProtector()
    private var state: JSONObject = prefs.getString("encrypted", null)?.let(::decrypt) ?: newState()

    init {
        save()
    }

    val connected get() = value("token").isNotEmpty()
    val localKey get() = value("localKey").unb64()

    @Synchronized fun save() = persist(state)

    @Synchronized fun value(name: String): String = state.optString(name)

    /** Changes a field in memory only; call [save] to persist. */
    @Synchronized fun put(name: String, value: Any) {
        state.put(name, value)
    }

    @Synchronized fun update(vararg entries: Pair<String, Any>) {
        val next = JSONObject(state.toString())
        for ((name, value) in entries) next.put(name, value)
        persist(next)
        state = next
    }

    @Synchronized fun hasKey(keyId: String): Boolean = state.getJSONObject("keys").has(keyId)

    /** Adds a group key in memory only; call [save] to persist. */
    @Synchronized fun putKey(keyId: String, key: ByteArray) {
        state.getJSONObject("keys").put(keyId, key.b64())
    }

    @Synchronized fun key(keyId: String): ByteArray {
        val keys = state.getJSONObject("keys")
        require(keys.has(keyId)) { "An encryption key is missing. Pair this device again." }
        return keys.getString(keyId).unb64()
    }

    @Synchronized fun keys(): JSONObject = JSONObject(state.getJSONObject("keys").toString())

    @Synchronized fun enrollment(): PendingEnrollment? =
        value("enrollment").takeIf { it.isNotEmpty() }?.let { PendingEnrollment(JSONObject(it)) }

    private fun persist(value: JSONObject) {
        val encrypted = Crypto.seal(value.toString().toByteArray(), protector, "identity").b64()
        check(prefs.edit().putString("encrypted", encrypted).commit()) { "Could not save device keys" }
    }

    private fun decrypt(encrypted: String) = JSONObject(String(Crypto.open(encrypted.unb64(), protector, "identity")))

    private fun newState(): JSONObject {
        val (privateKey, publicKey) = Crypto.identity()
        return JSONObject()
            .put("private", privateKey.b64())
            .put("public", publicKey.b64())
            .put("localKey", Crypto.randomKey().b64())
            .put("keys", JSONObject())
            .put("url", "")
            .put("token", "")
            .put("deviceId", "")
            .put("keyId", "")
    }

    private fun loadOrCreateProtector(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(PROTECTOR_ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(PROTECTOR_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }

    private companion object {
        const val PROTECTOR_ALIAS = "dev.midplane.zap.identity"
    }
}
