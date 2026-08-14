package com.ab.assistant.tools

sealed interface ToolCommand {
    data object FlashlightOn : ToolCommand
    data object FlashlightOff : ToolCommand
    data class OpenApp(val appName: String) : ToolCommand
    data class SetVolume(val stream: VolumeStream, val level: Int) : ToolCommand
    data class AdjustVolume(val stream: VolumeStream, val adjustment: VolumeAdjustment) : ToolCommand
    data class Media(val action: MediaAction) : ToolCommand
    data class SetTimer(val durationMinutes: Int) : ToolCommand
    data class SetAlarm(val hour: Int, val minute: Int, val label: String) : ToolCommand
    data class ReadNotifications(val filter: String?) : ToolCommand
    data class FindContact(val name: String) : ToolCommand
    data class WebSearch(val query: String) : ToolCommand
    data class SendSms(val recipient: String, val message: String) : ToolCommand
    data class DialContact(val recipient: String) : ToolCommand
    data object ReadDeviceState : ToolCommand
    data object GetUiSnapshot : ToolCommand
    data class TapUi(val snapshotId: Long, val ref: String) : ToolCommand
    data class InputUiText(val snapshotId: Long, val ref: String, val text: String) : ToolCommand
    data class ScrollUi(val snapshotId: Long, val ref: String, val direction: UiScrollDirection) : ToolCommand
}

enum class VolumeStream { MUSIC, RING, ALARM, NOTIFICATION }

enum class VolumeAdjustment { UP, DOWN }

enum class MediaAction { PLAY, PAUSE, NEXT, PREVIOUS }

enum class UiScrollDirection { FORWARD, BACKWARD }
