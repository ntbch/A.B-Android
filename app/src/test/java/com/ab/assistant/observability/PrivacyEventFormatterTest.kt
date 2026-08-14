package com.ab.assistant.observability

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyEventFormatterTest {
    @Test
    fun sensitiveValuesAndEmbeddedPiiAreNeverRendered() {
        val formatted = PrivacyEventFormatter.format(
            "tool-result",
            mapOf(
                "message" to "Gửi mã 1234 cho Nam",
                "status" to "contact 0901 234 567 failed",
                "tool" to "send_sms",
            ),
        )

        assertTrue(formatted.contains("tool=send_sms"))
        assertTrue(formatted.contains("[redacted:"))
        assertFalse(formatted.contains("Gửi mã"))
        assertFalse(formatted.contains("0901"))
    }
}
