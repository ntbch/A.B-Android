package com.ab.assistant.observability

import android.util.Log

enum class AbLogCategory(val tag: String) {
    RUNTIME("AB/RUNTIME"),
    ROUTER("AB/ROUTER"),
    TOOL("AB/TOOL"),
    SKILL("AB/SKILL"),
    ACCESSIBILITY("AB/A11Y"),
    CAPABILITY("AB/CAP"),
    VOICE("AB/VOICE"),
    SECURITY("AB/SECURITY"),
}

/** Formats bounded diagnostic events without retaining private command or screen content. */
object AbLog {
    fun event(category: AbLogCategory, name: String, fields: Map<String, Any?> = emptyMap()) {
        Log.i(category.tag, PrivacyEventFormatter.format(name, fields))
    }

    fun warning(category: AbLogCategory, name: String, fields: Map<String, Any?> = emptyMap()) {
        Log.w(category.tag, PrivacyEventFormatter.format(name, fields))
    }
}

object PrivacyEventFormatter {
    private val sensitiveKeys = setOf(
        "body", "content", "description", "message", "name", "query", "recipient", "request", "summary", "text", "url",
    )
    private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val phone = Regex("(?<!\\d)(?:\\+?\\d[ .()-]?){6,}\\d")
    private val url = Regex("https?://\\S+", RegexOption.IGNORE_CASE)

    fun format(name: String, fields: Map<String, Any?> = emptyMap()): String {
        val safeName = name.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(MAX_NAME_LENGTH)
        return buildString {
            append("event=").append(safeName.ifBlank { "unknown" })
            fields.toSortedMap().forEach { (key, value) ->
                append(' ').append(key.filter { it.isLetterOrDigit() || it == '_' }.take(MAX_KEY_LENGTH))
                append('=').append(redact(key, value))
            }
        }
    }

    private fun redact(key: String, value: Any?): String {
        val text = value?.toString().orEmpty()
        if (key.lowercase() in sensitiveKeys || email.containsMatchIn(text) || phone.containsMatchIn(text) || url.containsMatchIn(text)) {
            return "[redacted:${text.length}]"
        }
        return text.replace(Regex("[\\r\\n\\t]+"), " ").take(MAX_VALUE_LENGTH)
    }

    private const val MAX_NAME_LENGTH = 48
    private const val MAX_KEY_LENGTH = 32
    private const val MAX_VALUE_LENGTH = 120
}
