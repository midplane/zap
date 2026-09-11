package dev.zap

import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import java.io.File

class CryptoTest {
    @Test fun sharedVectorAndTamperRejection() {
        val vector = JSONObject(File("../../protocol/crypto-vector.json").readText())
        val id = vector.getString("id"); val key = vector.getString("key").unb64(); val sealed = vector.getString("sealed").unb64()
        assertEquals(vector.getString("plaintext"), String(Crypto.open(sealed, key, id)))
        assertArrayEquals(key, Crypto.unwrap(vector.getJSONObject("envelope"), vector.getString("privateKeyPKCS8").unb64(), id))
        val wrapped = Crypto.wrap(key, vector.getString("publicKey").unb64(), id)
        assertArrayEquals(key, Crypto.unwrap(wrapped, vector.getString("privateKeyPKCS8").unb64(), id))
        sealed[sealed.lastIndex] = (sealed.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { Crypto.open(sealed, key, id) }
    }
}
