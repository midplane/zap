package dev.midplane.zap

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

class PayloadFilesTest {
    @Test fun corruptMissingAndMalformedRecordsAreIsolatedAndRepairable() {
        val directory = Files.createTempDirectory("zap-content-test").toFile()
        try {
            val key = Crypto.randomKey()
            val healthy = UUID.randomUUID().toString(); val damaged = UUID.randomUUID().toString()
            val missing = UUID.randomUUID().toString(); val malformed = UUID.randomUUID().toString()
            val ids = setOf(healthy, damaged, missing, malformed)
            val payload = JSONObject().put("kind", "text").put("text", "Healthy").put("createdAt", 1L)
            val store = PayloadFiles(directory, key)
            store.save(healthy, payload); store.save(damaged, payload)
            File(directory, damaged).writeBytes(byteArrayOf(1, 2, 3))
            File(directory, malformed).writeBytes(Crypto.seal("{}".toByteArray(), key, malformed))
            val reopened = PayloadFiles(directory, key)
            assertEquals(setOf(healthy), reopened.readAll(ids).keys)
            assertEquals(setOf(damaged, missing, malformed), reopened.damaged)
            assertArrayEquals(byteArrayOf(1, 2, 3), File(directory, damaged).readBytes())
            val restarted = PayloadFiles(directory, key)
            assertEquals(setOf(healthy), restarted.readAll(ids).keys)
            restarted.save(damaged, payload)
            assertEquals(setOf(healthy, damaged), restarted.readAll(ids).keys)
            assertEquals(setOf(missing, malformed), restarted.damaged)
            restarted.remove(malformed); restarted.remove(missing)
            assertFalse(File(directory, malformed).exists())
            assertTrue(File(directory, healthy).exists())
            assertThrows(IllegalArgumentException::class.java) { restarted.save("../../escape", payload) }
        } finally { directory.deleteRecursively() }
    }
}
