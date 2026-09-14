package dev.midplane.zap

import org.json.JSONObject
import java.io.File

/**
 * Encrypted payload files, one per clip. Damaged files stay in place; one unreadable item never prevents
 * loading healthy items.
 */
class PayloadFiles(private val directory: File, private val key: ByteArray) {
    private val cached = mutableMapOf<String, JSONObject>()
    val damaged = mutableSetOf<String>()

    fun readAll(ids: Set<String>): Map<String, JSONObject> {
        cached.keys.retainAll(ids)
        damaged.clear()
        val readable = mutableMapOf<String, JSONObject>()
        for (id in ids) {
            try {
                readable[id] = cached.getOrPut(id) { read(id) }
            } catch (e: Exception) {
                damaged.add(id)
            }
        }
        return readable
    }

    fun save(id: String, payload: JSONObject) {
        validate(payload)
        val destination = file(id)
        // Write to a temporary file and rename, so an interrupted write never replaces a good file.
        val temp = File(directory, "$id.tmp")
        temp.writeBytes(Crypto.seal(payload.toString().toByteArray(), key, id))
        check(temp.renameTo(destination)) { "Could not save history" }
        cached[id] = payload
        damaged.remove(id)
    }

    fun remove(id: String) {
        val target = file(id)
        check(!target.exists() || target.delete()) { "Could not remove content file" }
        cached.remove(id)
        damaged.remove(id)
    }

    private fun read(id: String): JSONObject =
        JSONObject(String(Crypto.open(file(id).readBytes(), key, id))).also(::validate)

    /** Also rejects ids that could escape [directory]. */
    private fun file(id: String): File {
        require(id.matches(ID_PATTERN)) { "Invalid content identifier" }
        return File(directory, id)
    }

    companion object {
        private val ID_PATTERN = Regex("[a-zA-Z0-9_-]{16,100}")

        fun validate(payload: JSONObject) {
            payload.getLong("createdAt")
            when (payload.getString("kind")) {
                "text" -> require(payload.opt("text") is String) { "Text content is damaged" }
                "image" -> payload.getString("png").unb64()
                else -> error("Content type is damaged")
            }
        }
    }
}
