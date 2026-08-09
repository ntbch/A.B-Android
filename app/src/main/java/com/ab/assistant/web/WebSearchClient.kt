package com.ab.assistant.web

import android.text.Html
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class WebSearchEntry(val title: String, val snippet: String)

sealed interface WebSearchResponse {
    data class Success(val entries: List<WebSearchEntry>) : WebSearchResponse
    data class Failure(val message: String) : WebSearchResponse
}

fun interface WebSearchClient {
    fun search(query: String): WebSearchResponse
}

class BingRssSearchClient : WebSearchClient {
    override fun search(query: String): WebSearchResponse {
        if (query.isBlank()) return WebSearchResponse.Failure("Cần nội dung để tìm kiếm.")
        return try {
            val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
            val connection = URI("https://www.bing.com/search?format=rss&q=$encodedQuery").toURL()
                .openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                setRequestProperty("User-Agent", "A.B-Android/0.3")
                setRequestProperty("Accept", "text/html")
            }
            try {
                val http = connection
                if (http.responseCode !in 200..299) {
                    return WebSearchResponse.Failure("Dịch vụ tìm kiếm trả về lỗi ${http.responseCode}.")
                }
                val html = http.inputStream.use(::readLimitedUtf8)
                val entries = extractEntries(html)
                if (entries.isEmpty()) WebSearchResponse.Failure("Không tìm thấy kết quả phù hợp.")
                else WebSearchResponse.Success(entries)
            } finally {
                connection.disconnect()
            }
        } catch (_: java.net.UnknownHostException) {
            WebSearchResponse.Failure("Không có kết nối mạng để tìm kiếm.")
        } catch (_: java.net.SocketTimeoutException) {
            WebSearchResponse.Failure("Tìm kiếm quá thời gian chờ. Hãy thử lại.")
        } catch (_: Exception) {
            WebSearchResponse.Failure("Không thể thực hiện tìm kiếm lúc này.")
        }
    }

    private fun extractEntries(html: String): List<WebSearchEntry> {
        return itemPattern.findAll(html)
            .map { match -> WebSearchEntry(cleanHtml(match.groupValues[1]), cleanHtml(match.groupValues[2])) }
            .filter { it.title.isNotBlank() }
            .take(MAX_RESULTS)
            .toList()
    }

    private fun readLimitedUtf8(input: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() < MAX_RESPONSE_BYTES) {
            val read = input.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_BYTES - output.size()))
            if (read < 0) break
            output.write(buffer, 0, read)
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private fun cleanHtml(value: String): String = Html.fromHtml(
        value.removePrefix("<![CDATA[").removeSuffix("]]>") ,
        Html.FROM_HTML_MODE_LEGACY,
    )
        .toString()
        .replace(Regex("\\s+"), " ")
        .trim()

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 8_000
        const val READ_TIMEOUT_MILLIS = 8_000
        const val MAX_RESPONSE_BYTES = 256 * 1024
        const val MAX_RESULTS = 3
        val itemPattern = Regex(
            """<item>.*?<title>(.*?)</title>.*?<description>(.*?)</description>.*?</item>""",
            RegexOption.DOT_MATCHES_ALL,
        )
    }
}
