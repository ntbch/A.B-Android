package com.ab.assistant.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationCacheTest {
    @Test
    fun filtersAndBoundsEphemeralNotifications() {
        val cache = NotificationCache(maxEntries = 2)
        cache.upsert(NotificationSummary("1", "Zalo", "Nam", "Đến rồi", 1))
        cache.upsert(NotificationSummary("2", "Gmail", "Mail", "Hóa đơn", 2))
        cache.upsert(NotificationSummary("3", "Zalo", "Lan", "Chào bạn", 3))

        assertEquals(listOf("3"), cache.read("zalo").map(NotificationSummary::key))
        assertEquals(listOf("3", "2"), cache.read(null).map(NotificationSummary::key))
    }
}
