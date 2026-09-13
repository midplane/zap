package dev.midplane.zap

import org.junit.Assert.*
import org.junit.Test

class TextCaptureTest {
    @Test fun pairingKeysCannotBecomeClipboardOrSharedTextHistory() {
        for (text in listOf("zap://pair#secret", "  \nzap://pair#secret", "Invitation: ZAP://PAIR#secret\nKeep private")) {
            assertThrows(IllegalArgumentException::class.java) { TextCapture.payload(text) }
        }
        assertEquals("Ordinary clipboard text", TextCapture.payload("Ordinary clipboard text").getString("text"))
        assertThrows(IllegalArgumentException::class.java) { TextCapture.payload("") }
        assertThrows(IllegalArgumentException::class.java) { TextCapture.payload("é".repeat(524_289)) }
    }
}
