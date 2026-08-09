package com.ab.assistant.notifications

object NotificationStore {
    private val cache = NotificationCache()

    fun replaceAll(items: List<NotificationSummary>) = cache.replaceAll(items)
    fun upsert(item: NotificationSummary) = cache.upsert(item)
    fun remove(key: String) = cache.remove(key)
    fun read(filter: String?): List<NotificationSummary> = cache.read(filter)
}
