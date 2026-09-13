package dev.midplane.zap

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EnrollmentTest {
    @Test fun pendingRequestSurvivesRestartAndFailedCompletion() {
        val keys = JSONObject().put("key-id", Crypto.randomKey().b64())
        val pending = PendingEnrollment.create("https://zap.test", JSONObject().put("keyId", "key-id"), keys)
        val persisted = pending.record.toString()
        val restored = PendingEnrollment(JSONObject(persisted))
        assertEquals(pending.body.toString(), restored.body.toString())
        assertTrue(restored.body.getString("deviceToken").matches(Regex("[a-f0-9]{64}")))
        val response = JSONObject().put("deviceId", restored.body.getString("enrollmentId"))
            .put("token", restored.body.getString("deviceToken")).put("keyId", "key-id")
        val completed = restored.completed(response).toMap()
        assertEquals("", completed["enrollment"])
        // Preparing connected state must not mutate the persisted request if saving that state fails.
        assertEquals(persisted, restored.record.toString())
        val retry = PendingEnrollment(JSONObject(persisted)).completed(response).toMap()
        assertEquals(completed["token"], retry["token"])
        assertEquals(keys.toString(), (retry["keys"] as JSONObject).toString())
        assertThrows(IllegalArgumentException::class.java) { restored.completed(JSONObject(response.toString()).put("token", "wrong")) }
    }
}
