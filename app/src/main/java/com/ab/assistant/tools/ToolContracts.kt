package com.ab.assistant.tools

typealias JsonObject = Map<String, Any?>

enum class ToolRisk { LOW, INFORMATION, OUTBOUND }

enum class Capability {
    CAMERA,
    LAUNCHER,
    AUDIO,
    ALARM,
    NOTIFICATIONS,
    CONTACTS,
    NETWORK,
    SMS,
    DIALER,
}

enum class ConfirmationPolicy { NONE, REQUIRED }

enum class ToolStatus { SUCCESS, FAILURE, REJECTED }

data class ToolCall(
    val name: String,
    val arguments: JsonObject,
)

data class ToolSpec(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val risk: ToolRisk,
    val requiredCapabilities: Set<Capability>,
    val confirmation: ConfirmationPolicy,
    val timeoutMs: Long,
)

data class ToolResult(
    val status: ToolStatus,
    val summary: String,
    val data: JsonObject? = null,
    val verified: Boolean,
    val retryable: Boolean,
    val errorCode: String? = null,
    val requiresFollowUp: Boolean = false,
)

internal object ToolSpecCatalog {
    private const val DEVICE_TIMEOUT_MS = 5_000L
    private const val INFORMATION_TIMEOUT_MS = 10_000L

    private fun schema(vararg required: String): JsonObject = mapOf(
        "type" to "object",
        "required" to required.toList(),
    )

    private val specs = listOf(
        ToolSpec("flashlight", "Turn the device flashlight on or off.", schema("action"), ToolRisk.LOW, setOf(Capability.CAMERA), ConfirmationPolicy.NONE, DEVICE_TIMEOUT_MS),
        ToolSpec("open_app", "Open one uniquely matching launcher app.", schema("app"), ToolRisk.LOW, setOf(Capability.LAUNCHER), ConfirmationPolicy.NONE, DEVICE_TIMEOUT_MS),
        ToolSpec("set_volume", "Set one Android audio stream to a percentage.", schema("stream", "level"), ToolRisk.LOW, setOf(Capability.AUDIO), ConfirmationPolicy.NONE, DEVICE_TIMEOUT_MS),
        ToolSpec("adjust_volume", "Raise or lower one Android audio stream.", schema("stream", "direction"), ToolRisk.LOW, setOf(Capability.AUDIO), ConfirmationPolicy.NONE, DEVICE_TIMEOUT_MS),
        ToolSpec("media", "Send one media control action.", schema("action"), ToolRisk.LOW, setOf(Capability.AUDIO), ConfirmationPolicy.NONE, DEVICE_TIMEOUT_MS),
        ToolSpec("set_timer", "Open the Android timer flow.", schema("duration_minutes"), ToolRisk.LOW, setOf(Capability.ALARM), ConfirmationPolicy.NONE, DEVICE_TIMEOUT_MS),
        ToolSpec("set_alarm", "Open the Android alarm flow.", schema("hour", "minute", "label"), ToolRisk.LOW, setOf(Capability.ALARM), ConfirmationPolicy.NONE, DEVICE_TIMEOUT_MS),
        ToolSpec("read_notifications", "Read the bounded in-memory notification cache.", schema(), ToolRisk.INFORMATION, setOf(Capability.NOTIFICATIONS), ConfirmationPolicy.NONE, INFORMATION_TIMEOUT_MS),
        ToolSpec("find_contact", "Find matching contacts by name.", schema("name"), ToolRisk.INFORMATION, setOf(Capability.CONTACTS), ConfirmationPolicy.NONE, INFORMATION_TIMEOUT_MS),
        ToolSpec("web_search", "Search the network and return bounded result summaries.", schema("query"), ToolRisk.INFORMATION, setOf(Capability.NETWORK), ConfirmationPolicy.NONE, INFORMATION_TIMEOUT_MS),
        ToolSpec("send_sms", "Send an SMS after deterministic confirmation.", schema("recipient", "message"), ToolRisk.OUTBOUND, setOf(Capability.SMS), ConfirmationPolicy.REQUIRED, INFORMATION_TIMEOUT_MS),
        ToolSpec("dial_contact", "Open the dialer for one resolved recipient.", schema("recipient"), ToolRisk.OUTBOUND, setOf(Capability.DIALER), ConfirmationPolicy.REQUIRED, DEVICE_TIMEOUT_MS),
        ToolSpec("device_state", "Read bounded local battery/device state.", schema(), ToolRisk.INFORMATION, emptySet(), ConfirmationPolicy.NONE, DEVICE_TIMEOUT_MS),
    ).associateBy(ToolSpec::name)

    fun forName(name: String): ToolSpec? = specs[name]

    fun forCommand(command: ToolCommand): ToolSpec = specs.getValue(command.toolName())
}

private fun ToolCommand.toolName(): String = when (this) {
        ToolCommand.FlashlightOn, ToolCommand.FlashlightOff -> "flashlight"
        is ToolCommand.OpenApp -> "open_app"
        is ToolCommand.SetVolume -> "set_volume"
        is ToolCommand.AdjustVolume -> "adjust_volume"
        is ToolCommand.Media -> "media"
    is ToolCommand.SetTimer -> "set_timer"
    is ToolCommand.SetAlarm -> "set_alarm"
    is ToolCommand.ReadNotifications -> "read_notifications"
    is ToolCommand.FindContact -> "find_contact"
    is ToolCommand.WebSearch -> "web_search"
        is ToolCommand.SendSms -> "send_sms"
        is ToolCommand.DialContact -> "dial_contact"
        ToolCommand.ReadDeviceState -> "device_state"
}

fun ToolCommand.toToolCall(): ToolCall = when (this) {
    ToolCommand.FlashlightOn -> ToolCall("flashlight", mapOf("action" to "on"))
    ToolCommand.FlashlightOff -> ToolCall("flashlight", mapOf("action" to "off"))
    is ToolCommand.OpenApp -> ToolCall("open_app", mapOf("app" to appName))
    is ToolCommand.SetVolume -> ToolCall("set_volume", mapOf("stream" to stream.name.lowercase(), "level" to level))
    is ToolCommand.AdjustVolume -> ToolCall("adjust_volume", mapOf("stream" to stream.name.lowercase(), "direction" to adjustment.name.lowercase()))
    is ToolCommand.Media -> ToolCall("media", mapOf("action" to action.name.lowercase()))
    is ToolCommand.SetTimer -> ToolCall("set_timer", mapOf("duration_minutes" to durationMinutes))
    is ToolCommand.SetAlarm -> ToolCall("set_alarm", mapOf("hour" to hour, "minute" to minute, "label" to label))
    is ToolCommand.ReadNotifications -> ToolCall(
        "read_notifications",
        filter?.let { mapOf("filter" to it) } ?: emptyMap(),
    )
    is ToolCommand.FindContact -> ToolCall("find_contact", mapOf("name" to name))
    is ToolCommand.WebSearch -> ToolCall("web_search", mapOf("query" to query))
    is ToolCommand.SendSms -> ToolCall("send_sms", mapOf("recipient" to recipient, "message" to message))
    is ToolCommand.DialContact -> ToolCall("dial_contact", mapOf("recipient" to recipient))
    ToolCommand.ReadDeviceState -> ToolCall("device_state", emptyMap())
}

class TypedToolRegistry(private val delegate: ToolExecutor) {
    fun spec(command: ToolCommand): ToolSpec = ToolSpecCatalog.forCommand(command)

    fun command(call: ToolCall): ToolCommand? =
        ToolSpecCatalog.forName(call.name)?.let { ToolCallDecoder.decode(call) }

    fun execute(command: ToolCommand): ToolResult = execute(command.toToolCall())

    fun execute(call: ToolCall): ToolResult {
        val spec = ToolSpecCatalog.forName(call.name)
            ?: return rejected("UNKNOWN_TOOL")
        val command = ToolCallDecoder.decode(call)
            ?: return rejected("MALFORMED_ARGUMENTS")
        return try {
            delegate.execute(command).toToolResult()
        } catch (_: Exception) {
            ToolResult(
                status = ToolStatus.FAILURE,
                summary = "Tool execution failed safely.",
                verified = false,
                retryable = false,
                errorCode = "EXECUTION_ERROR",
            )
        }
    }

    private fun rejected(code: String) = ToolResult(
        status = ToolStatus.REJECTED,
        summary = "Tool call rejected.",
        verified = false,
        retryable = false,
        errorCode = code,
    )
}

private object ToolCallDecoder {
    fun decode(call: ToolCall): ToolCommand? = when (call.name) {
        "flashlight" -> decodeFlashlight(call.arguments)
        "open_app" -> call.arguments.exactKeys("app")?.let { ToolCommand.OpenApp(it.string("app", 80) ?: return null) }
        "set_volume" -> call.arguments.exactKeys("stream", "level")?.let {
            val stream = it.string("stream", 20)?.let { value -> VolumeStream.entries.firstOrNull { entry -> entry.name.lowercase() == value } }
            val level = it.int("level", 0..100)
            if (stream != null && level != null) ToolCommand.SetVolume(stream, level) else null
        }
        "adjust_volume" -> call.arguments.exactKeys("stream", "direction")?.let {
            val stream = it.string("stream", 20)?.let { value -> VolumeStream.entries.firstOrNull { entry -> entry.name.lowercase() == value } }
            val adjustment = it.string("direction", 10)?.let { value -> VolumeAdjustment.entries.firstOrNull { entry -> entry.name.lowercase() == value } }
            if (stream != null && adjustment != null) ToolCommand.AdjustVolume(stream, adjustment) else null
        }
        "media" -> call.arguments.exactKeys("action")?.let {
            it.string("action", 20)?.let { value -> MediaAction.entries.firstOrNull { entry -> entry.name.lowercase() == value } }
                ?.let(ToolCommand::Media)
        }
        "set_timer" -> call.arguments.exactKeys("duration_minutes")?.int("duration_minutes", 1..1440)?.let(ToolCommand::SetTimer)
        "set_alarm" -> call.arguments.exactKeys("hour", "minute", "label")?.let {
            val hour = it.int("hour", 0..23)
            val minute = it.int("minute", 0..59)
            val label = it.string("label", 80, allowBlank = true)
            if (hour != null && minute != null && label != null) ToolCommand.SetAlarm(hour, minute, label) else null
        }
        "read_notifications" -> call.arguments.exactKeys(optional = setOf("filter"))?.let {
            ToolCommand.ReadNotifications(it.string("filter", 80, allowBlank = true))
        }
        "find_contact" -> call.arguments.exactKeys("name")?.string("name", 80)?.let(ToolCommand::FindContact)
        "web_search" -> call.arguments.exactKeys("query")?.string("query", 160)?.let(ToolCommand::WebSearch)
        "send_sms" -> call.arguments.exactKeys("recipient", "message")?.let {
            val recipient = it.string("recipient", 80)
            val message = it.string("message", 500)
            if (recipient != null && message != null) ToolCommand.SendSms(recipient, message) else null
        }
        "dial_contact" -> call.arguments.exactKeys("recipient")?.string("recipient", 80)?.let(ToolCommand::DialContact)
        "device_state" -> call.arguments.exactKeys()?.let { ToolCommand.ReadDeviceState }
        else -> null
    }

    private fun decodeFlashlight(arguments: JsonObject): ToolCommand? = arguments.exactKeys("action")?.string("action", 10)?.let {
        when (it) {
            "on" -> ToolCommand.FlashlightOn
            "off" -> ToolCommand.FlashlightOff
            else -> null
        }
    }

    private fun JsonObject.exactKeys(vararg required: String, optional: Set<String> = emptySet()): JsonObject? {
        val allowed = required.toSet() + optional
        return takeIf { required.all(::containsKey) && keys.all(allowed::contains) }
    }

    private fun JsonObject.string(key: String, maxLength: Int, allowBlank: Boolean = false): String? =
        (this[key] as? String)?.takeIf { it.length <= maxLength && (allowBlank || it.isNotBlank()) }

    private fun JsonObject.int(key: String, range: IntRange): Int? {
        val value = this[key] as? Number ?: return null
        val integer = value.toInt()
        return integer.takeIf { value.toDouble() == integer.toDouble() && it in range }
    }
}

private fun ToolExecutionResult.toToolResult(): ToolResult = ToolResult(
    status = if (ok) ToolStatus.SUCCESS else ToolStatus.FAILURE,
    summary = message,
    verified = ok && verified,
    retryable = code == ToolResultCode.NETWORK_UNAVAILABLE ||
        code == ToolResultCode.NETWORK_ERROR ||
        code == ToolResultCode.TIMEOUT,
    errorCode = if (ok) null else code.name,
    requiresFollowUp = requiresFollowUp,
)

fun ToolResult.toToolExecutionResult(): ToolExecutionResult = ToolExecutionResult(
    message = summary,
    ok = status == ToolStatus.SUCCESS,
    code = errorCode?.let { runCatching { ToolResultCode.valueOf(it) }.getOrNull() }
        ?: if (status == ToolStatus.SUCCESS) ToolResultCode.OK else ToolResultCode.NOT_AVAILABLE,
    requiresFollowUp = requiresFollowUp,
    verified = status == ToolStatus.SUCCESS && verified,
)
