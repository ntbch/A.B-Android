package com.ab.assistant.model

data class InferenceMetrics(
    val requestId: String,
    val backendRequested: String?,
    val backendActual: String?,
    val fallbackReason: String?,
    val coldStart: Boolean,
    val modelLoadMs: Long?,
    val promptTokens: Int?,
    val prefillMs: Long?,
    val ttftMs: Long?,
    val generatedTokens: Int?,
    val decodeTokensPerSecond: Double?,
    val generationMs: Long,
    val totalMs: Long,
    val promptCharacters: Int? = null,
    val exposedToolCount: Int? = null,
    val modelDecisionIndex: Int? = null,
    val promptCacheHit: Boolean? = null,
    val cachedPromptTokens: Int? = null,
) {
    fun toDebugText(): String = buildString {
        appendLine("Inference metrics")
        appendLine("Backend: ${backendActual ?: "null"} (requested ${backendRequested ?: "null"})")
        appendLine("Cold start: $coldStart; loadMs=${modelLoadMs ?: "null"}")
        appendLine("GenerationMs: $generationMs; totalMs=$totalMs")
        appendLine("PromptChars: ${promptCharacters ?: "null"}; tools=${exposedToolCount ?: "null"}; decision=${modelDecisionIndex ?: "null"}")
        appendLine("Tokens/prefill/TTFT/decode: ${promptTokens ?: "null"}/${prefillMs ?: "null"}/${ttftMs ?: "null"}/${generatedTokens ?: "null"}/${decodeTokensPerSecond ?: "null"}")
        appendLine("Prompt cache: ${promptCacheHit?.toString() ?: "null"}; cachedTokens=${cachedPromptTokens ?: "null"}")
        fallbackReason?.let { appendLine("Fallback: $it") }
    }

    fun toJson(): String = buildString {
        append('{')
        appendField("requestId", requestId)
        appendField("backendRequested", backendRequested)
        appendField("backendActual", backendActual)
        appendField("fallbackReason", fallbackReason)
        appendField("coldStart", coldStart)
        appendField("modelLoadMs", modelLoadMs)
        appendField("promptTokens", promptTokens)
        appendField("prefillMs", prefillMs)
        appendField("ttftMs", ttftMs)
        appendField("generatedTokens", generatedTokens)
        appendField("decodeTokensPerSecond", decodeTokensPerSecond)
        appendField("generationMs", generationMs)
        appendField("totalMs", totalMs)
        appendField("promptCharacters", promptCharacters)
        appendField("exposedToolCount", exposedToolCount)
        appendField("modelDecisionIndex", modelDecisionIndex)
        appendField("promptCacheHit", promptCacheHit)
        appendField("cachedPromptTokens", cachedPromptTokens)
        append('}')
    }

    private fun StringBuilder.appendField(name: String, value: Any?) {
        if (length > 1) append(',')
        append('"').append(escape(name)).append("\":")
        when (value) {
            null -> append("null")
            is String -> append('"').append(escape(value)).append('"')
            else -> append(value)
        }
    }

    private fun escape(value: String): String = buildString {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
    }
}
