package com.ab.assistant.model

import java.util.Base64

/** Structured result returned by the MNN JNI boundary without guessing token timings. */
data class NativeGenerationResult(
    val output: String,
    val statusCode: Int?,
    val promptTokens: Int?,
    val prefillMs: Long?,
    val ttftMs: Long?,
    val generatedTokens: Int?,
    val decodeMs: Long?,
    val decodeTokensPerSecond: Double?,
    val promptCacheHit: Boolean,
    val cachedPromptTokens: Int?,
) {
    companion object {
        private const val HEADER = "AB_GENERATION_V1"

        fun parse(payload: String): NativeGenerationResult? {
            val lines = payload.split('\n')
            if (lines.firstOrNull() != HEADER) return null
            val fields = lines.drop(1).mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
            }.toMap()
            val response = try {
                val encoded = fields["response_b64"] ?: return null
                String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                return null
            }
            return NativeGenerationResult(
                output = response,
                statusCode = fields.intOrNull("status"),
                promptTokens = fields.intOrNull("prompt_tokens"),
                prefillMs = fields.longOrNull("prefill_ms"),
                ttftMs = fields.longOrNull("ttft_ms"),
                generatedTokens = fields.intOrNull("generated_tokens"),
                decodeMs = fields.longOrNull("decode_ms"),
                decodeTokensPerSecond = fields.longOrNull("decode_tps_milli")
                    ?.takeIf { it >= 0 }
                    ?.div(1000.0),
                promptCacheHit = fields["prompt_cache_hit"] == "1",
                cachedPromptTokens = fields.intOrNull("cached_prompt_tokens"),
            )
        }

        private fun Map<String, String>.intOrNull(name: String): Int? = get(name)?.toIntOrNull()?.takeIf { it >= 0 }

        private fun Map<String, String>.longOrNull(name: String): Long? = get(name)?.toLongOrNull()?.takeIf { it >= 0 }
    }
}
