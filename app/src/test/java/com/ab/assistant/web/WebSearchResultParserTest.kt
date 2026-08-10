package com.ab.assistant.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchResultParserTest {
    @Test
    fun parserAddsStableIdsSourcesAndCapsResults() {
        val html = (1..4).joinToString("") { index ->
            "<item><title><![CDATA[Title $index]]></title><link>https://example.test/$index</link>" +
                "<description>Snippet $index</description></item>"
        }

        val results = WebSearchResultParser.parse(html)

        assertEquals(3, results.size)
        assertEquals("search-1", results.first().id)
        assertEquals("https://example.test/1", results.first().sourceUrl)
        assertEquals("Title 1", results.first().title)
        assertTrue(results.all { it.snippet.length <= 512 })
    }
}
