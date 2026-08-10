package com.ab.assistant.agent

import com.ab.assistant.tools.ToolCommand
import com.ab.assistant.tools.ToolExecutionResult

enum class StuckReason {
    REPEATED_ACTION,
    REPEATED_ERROR,
    MALFORMED_MODEL_OUTPUT,
    STEP_BUDGET,
    DEADLINE,
}

data class StuckDecision(
    val reason: StuckReason? = null,
    val message: String? = null,
) {
    val isStuck: Boolean get() = reason != null
}

class StuckDetector(
    private val maxSteps: Int,
    private val maxWallMs: Long,
    private val repeatThreshold: Int = 2,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val startedAtMs = clockMs()
    private val actionCounts = mutableMapOf<String, Int>()
    private val errorCounts = mutableMapOf<String, Int>()
    private var malformedOutputCount = 0

    fun deadlineDecision(): StuckDecision {
        return if (clockMs() - startedAtMs > maxWallMs) {
            stuck(StuckReason.DEADLINE, "Tác vụ vượt quá thời gian cho phép và đã được dừng.")
        } else {
            StuckDecision()
        }
    }

    fun stepBudgetDecision(step: Int): StuckDecision =
        if (step >= maxSteps) {
            stuck(StuckReason.STEP_BUDGET, "Tác vụ đã đạt giới hạn số bước an toàn.")
        } else {
            StuckDecision()
        }

    fun recordAction(command: ToolCommand): StuckDecision {
        deadlineDecision().takeIf { it.isStuck }?.let { return it }
        val signature = command.toString()
        val count = (actionCounts[signature] ?: 0) + 1
        actionCounts[signature] = count
        return if (count >= repeatThreshold) {
            stuck(StuckReason.REPEATED_ACTION, "Tác vụ bị dừng vì lặp lại cùng một hành động.")
        } else {
            StuckDecision()
        }
    }

    fun recordResult(result: ToolExecutionResult): StuckDecision {
        deadlineDecision().takeIf { it.isStuck }?.let { return it }
        if (result.ok) return StuckDecision()
        val signature = "${result.code}:${result.message}"
        val count = (errorCounts[signature] ?: 0) + 1
        errorCounts[signature] = count
        return if (count >= repeatThreshold) {
            stuck(StuckReason.REPEATED_ERROR, "Tác vụ bị dừng vì cùng một lỗi lặp lại.")
        } else {
            StuckDecision()
        }
    }

    fun recordModelOutput(output: String, parsedCommand: ToolCommand?): StuckDecision {
        deadlineDecision().takeIf { it.isStuck }?.let { return it }
        val trimmed = output.trim()
        val looksLikeBrokenToolOutput = parsedCommand == null &&
            (trimmed.isBlank() || trimmed.startsWith("{") || trimmed.contains("\"tool\""))
        if (!looksLikeBrokenToolOutput) return StuckDecision()
        malformedOutputCount += 1
        return if (malformedOutputCount >= repeatThreshold) {
            stuck(StuckReason.MALFORMED_MODEL_OUTPUT, "Mô hình trả về định dạng không hợp lệ nhiều lần; tác vụ đã dừng.")
        } else {
            StuckDecision()
        }
    }

    private fun stuck(reason: StuckReason, message: String) = StuckDecision(reason, message)
}
