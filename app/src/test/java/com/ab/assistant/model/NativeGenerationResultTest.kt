package com.ab.assistant.model

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeGenerationResultTest {
    @Test
    fun parsesStructuredNativeMetricsAndUtf8Output() {
        val encoded = Base64.getEncoder().encodeToString("Xác nhận gửi SMS".toByteArray(Charsets.UTF_8))
        val payload = """
            AB_GENERATION_V1
            status=2
            prompt_tokens=87
            prefill_ms=123
            ttft_ms=140
            generated_tokens=12
            decode_ms=600
            decode_tps_milli=20000
            response_b64=$encoded
        """.trimIndent()

        val result = NativeGenerationResult.parse(payload)

        assertEquals("Xác nhận gửi SMS", result?.output)
        assertEquals(2, result?.statusCode)
        assertEquals(87, result?.promptTokens)
        assertEquals(123L, result?.prefillMs)
        assertEquals(140L, result?.ttftMs)
        assertEquals(12, result?.generatedTokens)
        assertEquals(20.0, result?.decodeTokensPerSecond)
        assertEquals(false, result?.promptCacheHit)
        assertNull(result?.cachedPromptTokens)
    }

    @Test
    fun rejectsMalformedPayloadAndKeepsNegativeMetricsUnavailable() {
        assertNull(NativeGenerationResult.parse("not-native-result"))
        val payload = "AB_GENERATION_V1\nstatus=1\nprompt_tokens=-1\nresponse_b64="

        val result = NativeGenerationResult.parse(payload)

        assertEquals("", result?.output)
        assertNull(result?.promptTokens)
    }
}
