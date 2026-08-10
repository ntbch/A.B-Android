package com.ab.assistant.web

internal object WebSearchResultParser {
    private val itemPattern = Regex(
        """<item(?:\\s[^>]*)?>(.*?)</item>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private const val MAX_RESULTS = 3
    private const val MAX_FIELD_LENGTH = 512

    fun parse(html: String): List<WebSearchEntry> = itemPattern.findAll(html)
        .mapIndexedNotNull { index, match ->
            val item = match.groupValues[1]
            val title = clean(tag(item, "title"))
            val snippet = clean(tag(item, "description"))
            val sourceUrl = clean(tag(item, "link")).take(MAX_FIELD_LENGTH)
            if (title.isBlank()) null else WebSearchEntry(
                title = title.take(MAX_FIELD_LENGTH),
                snippet = snippet.take(MAX_FIELD_LENGTH),
                id = "search-${index + 1}",
                sourceUrl = sourceUrl,
            )
        }
        .take(MAX_RESULTS)
        .toList()

    private fun tag(value: String, name: String): String = Regex(
        "<$name(?:\\s[^>]*)?>(.*?)</$name>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    ).find(value)?.groupValues?.getOrNull(1).orEmpty()

    private fun clean(value: String): String = value
        .removePrefix("<![CDATA[")
        .removeSuffix("]]>")
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace(Regex("\\s+"), " ")
        .trim()
}
