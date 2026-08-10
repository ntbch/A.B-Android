package com.ab.assistant.tools

object ToolCommandParser {
    private val flashlightCommand = Regex(
        "^\\{\\s*\"tool\"\\s*:\\s*\"flashlight\"\\s*,\\s*\"action\"\\s*:\\s*\"(on|off)\"\\s*\\}$",
    )
    private val openAppCommand = Regex(
        "^\\{\\s*\"tool\"\\s*:\\s*\"open_app\"\\s*,\\s*\"app\"\\s*:\\s*\"([^\"\\\\\\r\\n]{1,80})\"\\s*\\}$",
    )
    private val volumeCommand = Regex(
        "^\\{\\s*\"tool\"\\s*:\\s*\"set_volume\"\\s*,\\s*\"stream\"\\s*:\\s*\"(music|ring|alarm|notification)\"\\s*,\\s*\"level\"\\s*:\\s*(\\d{1,3})\\s*\\}$",
    )
    private val adjustVolumeCommand = Regex(
        "^\\{\\s*\"tool\"\\s*:\\s*\"adjust_volume\"\\s*,\\s*\"stream\"\\s*:\\s*\"(music|ring|alarm|notification)\"\\s*,\\s*\"direction\"\\s*:\\s*\"(up|down)\"\\s*\\}$",
    )
    private val mediaCommand = Regex(
        "^\\{\\s*\"tool\"\\s*:\\s*\"media\"\\s*,\\s*\"action\"\\s*:\\s*\"(play|pause|next|previous)\"\\s*\\}$",
    )
    private val timerCommand = Regex(
        "^\\{\\s*\"tool\"\\s*:\\s*\"set_timer\"\\s*,\\s*\"duration_minutes\"\\s*:\\s*(\\d{1,4})\\s*\\}$",
    )
    private val alarmCommand = Regex(
        "^\\{\\s*\"tool\"\\s*:\\s*\"set_alarm\"\\s*,\\s*\"hour\"\\s*:\\s*(\\d{1,2})\\s*,\\s*\"minute\"\\s*:\\s*(\\d{1,2})\\s*,\\s*\"label\"\\s*:\\s*\"([^\"\\\\\\r\\n]{0,80})\"\\s*\\}$",
    )
    private val notificationsCommand = Regex(
        "^\\{\\s*\"tool\"\\s*:\\s*\"read_notifications\"\\s*,\\s*\"filter\"\\s*:\\s*\"([^\"\\\\\\r\\n]{0,80})\"\\s*\\}$",
    )
    private val contactsCommand = Regex(
        "^\\{\\s*\"tool\"\\s*:\\s*\"find_contact\"\\s*,\\s*\"name\"\\s*:\\s*\"([^\"\\\\\\r\\n]{1,80})\"\\s*\\}$",
    )
    private val webSearchCommand = Regex(
        "^\\{\\s*\"tool\"\\s*:\\s*\"web_search\"\\s*,\\s*\"query\"\\s*:\\s*\"([^\"\\\\\\r\\n]{1,160})\"\\s*\\}$",
    )
    private val sendSmsCommand = Regex(
        "^\\{\\s*\"tool\"\\s*:\\s*\"send_sms\"\\s*,\\s*\"recipient\"\\s*:\\s*\"([^\"\\\\\\r\\n]{1,80})\"\\s*,\\s*\"message\"\\s*:\\s*\"([^\"\\\\\\r\\n]{1,500})\"\\s*\\}$",
    )
    private val dialContactCommand = Regex(
        "^\\{\\s*\"tool\"\\s*:\\s*\"dial_contact\"\\s*,\\s*\"recipient\"\\s*:\\s*\"([^\"\\\\\\r\\n]{1,80})\"\\s*\\}$",
    )
    private val deviceStateCommand = Regex(
        "^\\{\\s*\"tool\"\\s*:\\s*\"device_state\"\\s*\\}$",
    )

    fun parse(modelOutput: String): ToolCommand? {
        val output = modelOutput.trim()
        when (flashlightCommand.matchEntire(output)?.groupValues?.get(1)) {
            "on" -> return ToolCommand.FlashlightOn
            "off" -> return ToolCommand.FlashlightOff
        }
        openAppCommand.matchEntire(output)?.let { return ToolCommand.OpenApp(it.groupValues[1]) }
        volumeCommand.matchEntire(output)?.let { match ->
            val level = match.groupValues[2].toInt()
            val stream = VolumeStream.entries.firstOrNull { it.name.lowercase() == match.groupValues[1] }
            if (stream != null && level in 0..100) return ToolCommand.SetVolume(stream, level)
        }
        adjustVolumeCommand.matchEntire(output)?.let { match ->
            val stream = VolumeStream.entries.firstOrNull { it.name.lowercase() == match.groupValues[1] }
            val adjustment = when (match.groupValues[2]) {
                "up" -> VolumeAdjustment.UP
                "down" -> VolumeAdjustment.DOWN
                else -> null
            }
            if (stream != null && adjustment != null) return ToolCommand.AdjustVolume(stream, adjustment)
        }
        mediaCommand.matchEntire(output)?.let { match ->
            val action = MediaAction.entries.firstOrNull { it.name.lowercase() == match.groupValues[1] }
            if (action != null) return ToolCommand.Media(action)
        }
        timerCommand.matchEntire(output)?.let { match ->
            val minutes = match.groupValues[1].toInt()
            if (minutes in 1..1440) return ToolCommand.SetTimer(minutes)
        }
        alarmCommand.matchEntire(output)?.let { match ->
            val hour = match.groupValues[1].toInt()
            val minute = match.groupValues[2].toInt()
            if (hour in 0..23 && minute in 0..59) {
                return ToolCommand.SetAlarm(hour, minute, match.groupValues[3])
            }
        }
        notificationsCommand.matchEntire(output)?.let { return ToolCommand.ReadNotifications(it.groupValues[1].ifBlank { null }) }
        contactsCommand.matchEntire(output)?.let { return ToolCommand.FindContact(it.groupValues[1]) }
        webSearchCommand.matchEntire(output)?.let { return ToolCommand.WebSearch(it.groupValues[1]) }
        sendSmsCommand.matchEntire(output)?.let { return ToolCommand.SendSms(it.groupValues[1], it.groupValues[2]) }
        dialContactCommand.matchEntire(output)?.let { return ToolCommand.DialContact(it.groupValues[1]) }
        if (deviceStateCommand.matches(output)) return ToolCommand.ReadDeviceState
        return null
    }
}
