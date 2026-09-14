package dev.midplane.zap

import org.json.JSONObject
import java.util.Base64
import java.util.UUID

const val PAIRING_CODE_PREFIX = "zap://pair#"

fun decodePairingCode(code: String): JSONObject =
    JSONObject(String(Base64.getUrlDecoder().decode(code.substringAfter('#'))))

fun encodePairingCode(pairing: JSONObject): String =
    PAIRING_CODE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(pairing.toString().toByteArray())

/**
 * A connection request, stored inside the encrypted identity before it is sent. The server answers a repeat
 * of the exact request with the original credentials, so an interrupted attempt can be resent safely.
 */
class PendingEnrollment(val record: JSONObject) {
    val url: String get() = record.getString("url")
    val body: JSONObject get() = record.getJSONObject("body")

    fun completed(response: JSONObject): Array<Pair<String, Any>> {
        val matches = response.getString("deviceId") == body.getString("enrollmentId") &&
            response.getString("token") == body.getString("deviceToken") &&
            response.getString("keyId") == body.getString("keyId")
        require(matches) { "The server returned a different enrollment. Update the server and retry." }
        return arrayOf(
            "url" to url,
            "token" to response.getString("token"),
            "deviceId" to response.getString("deviceId"),
            "keyId" to body.getString("keyId"),
            "keys" to record.getJSONObject("keys"),
            "enrollment" to ""
        )
    }

    companion object {
        fun create(url: String, registration: JSONObject, keys: JSONObject): PendingEnrollment {
            val body = JSONObject(registration.toString())
                .put("enrollmentId", UUID.randomUUID().toString())
                .put("deviceToken", Crypto.randomKey().joinToString("") { "%02x".format(it) })
            return PendingEnrollment(JSONObject().put("url", url).put("body", body).put("keys", JSONObject(keys.toString())))
        }
    }
}
