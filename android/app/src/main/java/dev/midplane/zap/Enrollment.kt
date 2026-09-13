package dev.midplane.zap

import org.json.JSONObject
import java.util.UUID

/** Stored inside the encrypted identity before any enrollment request leaves this device. */
class PendingEnrollment(val record: JSONObject) {
    val url: String get() = record.getString("url")
    val body: JSONObject get() = record.getJSONObject("body")
    fun completed(response: JSONObject): Array<Pair<String, Any>> {
        require(response.getString("deviceId") == body.getString("enrollmentId") &&
            response.getString("token") == body.getString("deviceToken") &&
            response.getString("keyId") == body.getString("keyId")) { "The server returned a different enrollment. Update the server and retry." }
        return arrayOf("url" to url, "token" to response.getString("token"), "deviceId" to response.getString("deviceId"),
            "keyId" to body.getString("keyId"), "keys" to record.getJSONObject("keys"), "enrollment" to "")
    }
    companion object {
        fun create(url: String, registration: JSONObject, keys: JSONObject): PendingEnrollment {
            val body = JSONObject(registration.toString()).put("enrollmentId", UUID.randomUUID().toString())
                .put("deviceToken", Crypto.randomKey().joinToString("") { "%02x".format(it) })
            return PendingEnrollment(JSONObject().put("url", url).put("body", body).put("keys", JSONObject(keys.toString())))
        }
    }
}
