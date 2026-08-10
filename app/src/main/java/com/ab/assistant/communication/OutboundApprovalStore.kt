package com.ab.assistant.communication

import com.ab.assistant.tools.ToolCommand
import java.util.UUID

data class PreparedOutboundAction(
    val token: String,
    val command: ToolCommand,
    val preview: String,
    val createdAtMs: Long,
)

data class ApprovedOutboundAction(
    val command: ToolCommand,
    val approvedAtMs: Long,
)

class OutboundApprovalStore(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val tokenFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Any()
    private val prepared = mutableMapOf<String, PreparedOutboundAction>()

    fun prepare(command: ToolCommand): PreparedOutboundAction? {
        if (!command.isOutbound()) return null
        val item = PreparedOutboundAction(
            token = tokenFactory(),
            command = command,
            preview = command.preview(),
            createdAtMs = clockMs(),
        )
        synchronized(lock) { prepared[item.token] = item }
        return item
    }

    fun authorize(token: String, command: ToolCommand): ApprovedOutboundAction? {
        val item = synchronized(lock) { prepared.remove(token) } ?: return null
        if (clockMs() - item.createdAtMs > ttlMs || item.command != command) return null
        return ApprovedOutboundAction(command = item.command, approvedAtMs = clockMs())
    }

    fun cancel(token: String) {
        synchronized(lock) { prepared.remove(token) }
    }

    private fun ToolCommand.isOutbound(): Boolean = this is ToolCommand.SendSms || this is ToolCommand.DialContact

    private fun ToolCommand.preview(): String = when (this) {
        is ToolCommand.SendSms -> "SMS tới $recipient:\n$message"
        is ToolCommand.DialContact -> "Mở trình quay số cho $recipient"
        else -> error("Not an outbound command")
    }

    companion object {
        const val DEFAULT_TTL_MS = 60_000L
    }
}
