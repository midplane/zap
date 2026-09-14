package dev.midplane.zap

import org.json.JSONObject

private const val MAX_TEXT_BYTES = 1024 * 1024

object TextCapture {
    fun payload(text: String): JSONObject {
        require(text.isNotEmpty()) { "Clipboard is empty" }
        require(text.toByteArray().size <= MAX_TEXT_BYTES) { "Text is too large. Maximum: 1 MiB." }
        require(!text.contains(PAIRING_CODE_PREFIX, ignoreCase = true)) {
            "Pairing codes contain encryption keys and cannot be saved to history. Use Settings to pair a device."
        }
        return JSONObject().put("kind", "text").put("text", text)
    }
}
