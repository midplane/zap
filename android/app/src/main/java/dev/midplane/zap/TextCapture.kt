package dev.midplane.zap

import org.json.JSONObject

object TextCapture {
    fun payload(text: String): JSONObject {
        require(text.isNotEmpty()) { "Clipboard is empty" }
        require(text.toByteArray().size <= 1024 * 1024) { "Text is too large. Maximum: 1 MiB." }
        require(!text.contains("zap://pair#", ignoreCase = true)) { "Pairing codes contain encryption keys and cannot be saved to history. Use Settings to pair a device." }
        return JSONObject().put("kind", "text").put("text", text)
    }
}
