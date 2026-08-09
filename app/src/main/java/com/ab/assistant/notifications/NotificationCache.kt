package com.ab.assistant.notifications

import java.text.Normalizer
import java.util.Locale

data class NotificationSummary(
    val key: String,
    val appName: String,
    val title: String,
    val text: String,
    val postedAtMillis: Long,
)

/** In-memory only: notification content disappears with the app process. */
class NotificationCache(private val maxEntries: Int = 50) {
    private val notifications = LinkedHashMap<String, NotificationSummary>()

    @Synchronized
    fun replaceAll(items: List<NotificationSummary>) {
        notifications.clear()
        items.sortedByDescending(NotificationSummary::postedAtMillis).take(maxEntries).forEach { item ->
            notifications[item.key] = item
        }
    }

    @Synchronized
    fun upsert(item: NotificationSummary) {
        notifications.remove(item.key)
        notifications[item.key] = item
        while (notifications.size > maxEntries) notifications.remove(notifications.entries.first().key)
    }

    @Synchronized
    fun remove(key: String) {
        notifications.remove(key)
    }

    @Synchronized
    fun read(filter: String?, limit: Int = 8): List<NotificationSummary> {
        val query = normalize(filter.orEmpty())
        return notifications.values
            .asSequence()
            .sortedByDescending(NotificationSummary::postedAtMillis)
            .filter { item ->
                query.isBlank() || listOf(item.appName, item.title, item.text).any { normalize(it).contains(query) }
            }
            .take(limit)
            .toList()
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace("\\p{M}+".toRegex(), "")
        .lowercase(Locale.ROOT)
        .replace('đ', 'd')
        .trim()
}
