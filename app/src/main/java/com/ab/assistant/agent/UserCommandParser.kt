package com.ab.assistant.agent

import com.ab.assistant.tools.MediaAction
import com.ab.assistant.tools.ToolCommand
import com.ab.assistant.tools.VolumeAdjustment
import com.ab.assistant.tools.VolumeStream
import java.text.Normalizer
import java.util.Locale

/**
 * Fast, conservative handling for explicit device commands. It keeps routine Vietnamese
 * actions reliable on a small on-device model while routing every result through AgentCore.
 */
object UserCommandParser {
    private val number = Regex("\\b(\\d{1,4})\\s*%?")
    private val timer = Regex("(?:hen gio|timer).*?\\b(\\d{1,4})\\b")
    private val alarm = Regex("(?:bao thuc|alarm).*?\\b(\\d{1,2})(?::|h| gio\\s*)(\\d{1,2})?")
    private val notificationFilter = Regex("(?:thong bao|notification)(?:.*?(?:tu|from)\\s+(.+))?$")
    private val sms = Regex("(?iu)^(?:nh\\u1eafn tin|g\\u1eedi tin nh\\u1eafn|sms|text)\\s+(.+?)\\s*[:;]\\s*(.+)$")
    private val naturalSms = Regex("(?iu)^(?:nh\\u1eafn(?:\\s+tin)?|nhan(?:\\s+tin)?)\\s+(?:cho\\s+)?(.+?)\\s+(?:l\\u00e0|la)\\s+(.+)$")
    private val dial = Regex("(?iu)^(?:g\\u1ecdi|call)\\s+(.+)$")

    fun parse(request: String): ToolCommand? {
        val normalized = normalize(request)
        if (normalized.isBlank()) return null

        if (normalized.contains("den pin") || normalized.contains("flashlight")) {
            if (containsAny(normalized, "tat", "off")) return ToolCommand.FlashlightOff
            if (containsAny(normalized, "bat", "on")) return ToolCommand.FlashlightOn
        }
        if (normalized == "pin" || normalized.startsWith("pin ") ||
            normalized.contains("battery") || normalized.contains("trang thai thiet bi") ||
            normalized.contains("device status") || normalized.contains("bao nhieu pin")
        ) {
            return ToolCommand.ReadDeviceState
        }
        sms.matchEntire(request.trim())?.let { match ->
            val recipient = match.groupValues[1].trim()
            val message = match.groupValues[2].trim()
            if (recipient.isNotBlank() && message.isNotBlank()) return ToolCommand.SendSms(recipient, message)
        }
        naturalSms.matchEntire(request.trim())?.let { match ->
            val recipient = match.groupValues[1].trim()
            val message = match.groupValues[2].trim()
            if (recipient.isNotBlank() && message.isNotBlank()) return ToolCommand.SendSms(recipient, message)
        }
        dial.matchEntire(request.trim())?.let { match ->
            val recipient = match.groupValues[1].trim()
            if (recipient.isNotBlank()) return ToolCommand.DialContact(recipient)
        }
        if (containsAny(normalized, "tim kiem", "search", "tra cuu")) {
            val words = request.trim().split(Regex("\\s+"), limit = 3)
            val query = if (normalized.startsWith("tim kiem ") || normalized.startsWith("tra cuu ")) {
                words.getOrNull(2).orEmpty()
            } else {
                request.substringAfter(' ', "").trim()
            }
            if (query.isNotBlank()) return ToolCommand.WebSearch(query)
        }
        if (normalized.contains("danh ba") || normalized.contains("lien he") || normalized.contains("contact")) {
            val name = when {
                normalized.contains("danh ba") -> dropLeadingWords(request, 4)
                normalized.contains("lien he") -> dropLeadingWords(request, 3)
                normalized.contains("contact") -> dropLeadingWords(request, 2)
                else -> ""
            }
            if (name.isNotBlank()) return ToolCommand.FindContact(name)
        }
        if (normalized.contains("thong bao") || normalized.contains("notification")) {
            val filter = notificationFilter.find(normalized)?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null }
            return ToolCommand.ReadNotifications(filter)
        }
        if (normalized.startsWith("mo ") || normalized.startsWith("open ")) {
            val app = request.substringAfter(' ', "").trim()
            if (app.isNotBlank() && !containsAny(normalized, "cai app", "app toi hay", "ung dung toi hay")) {
                return ToolCommand.OpenApp(app)
            }
        }
        if (normalized.contains("am luong") || normalized.contains("volume")) {
            val level = number.find(normalized)?.groupValues?.get(1)?.toIntOrNull()
            if (level != null && level in 0..100) {
                return ToolCommand.SetVolume(streamFor(normalized), level)
            }
            if (containsAny(normalized, "tang", "up", "raise")) {
                return ToolCommand.AdjustVolume(streamFor(normalized), VolumeAdjustment.UP)
            }
            if (containsAny(normalized, "giam", "down", "lower")) {
                return ToolCommand.AdjustVolume(streamFor(normalized), VolumeAdjustment.DOWN)
            }
        }
        if (containsAny(normalized, "dung phat", "pause")) return ToolCommand.Media(MediaAction.PAUSE)
        if (containsAny(normalized, "bai tiep", "next")) return ToolCommand.Media(MediaAction.NEXT)
        if (containsAny(normalized, "bai truoc", "previous")) return ToolCommand.Media(MediaAction.PREVIOUS)
        if (containsAny(normalized, "phat nhac", "play music", "play")) return ToolCommand.Media(MediaAction.PLAY)

        timer.find(normalized)?.let { match ->
            val minutes = match.groupValues[1].toInt()
            if (minutes in 1..1440) return ToolCommand.SetTimer(minutes)
        }
        alarm.find(normalized)?.let { match ->
            val hour = match.groupValues[1].toInt()
            val minute = match.groupValues[2].ifBlank { "0" }.toInt()
            if (hour in 0..23 && minute in 0..59) return ToolCommand.SetAlarm(hour, minute, "")
        }
        return null
    }

    private fun streamFor(value: String): VolumeStream = when {
        containsAny(value, "chuong", "ring") -> VolumeStream.RING
        containsAny(value, "bao thuc", "alarm") -> VolumeStream.ALARM
        containsAny(value, "thong bao", "notification") -> VolumeStream.NOTIFICATION
        else -> VolumeStream.MUSIC
    }

    private fun containsAny(value: String, vararg terms: String): Boolean = terms.any { term ->
        Regex("\\b${Regex.escape(term)}\\b").containsMatchIn(value)
    }

    private fun dropLeadingWords(value: String, count: Int): String {
        var remainder = value.trim()
        repeat(count) { remainder = remainder.substringAfter(' ', "").trim() }
        return remainder
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace("\\p{M}+".toRegex(), "")
        .lowercase(Locale.ROOT)
        .replace('\u0111', 'd')
        .trim()
}
