package com.ab.assistant.agent

import com.ab.assistant.tools.JsonObject
import com.ab.assistant.tools.ToolCall
import com.ab.assistant.tools.ToolExecutionResult
import com.ab.assistant.tools.ToolRisk
import java.text.Normalizer
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.Locale

fun interface SkillTrigger {
    fun match(request: String): JsonObject?
}

data class Skill(
    val id: String,
    val version: Int,
    val triggers: List<SkillTrigger>,
    val steps: List<SkillStep>,
    val maxWallMs: Long,
    val risk: ToolRisk,
)

data class SkillContext(
    val request: String,
    val arguments: JsonObject,
    val lastResult: ToolExecutionResult? = null,
)

sealed interface SkillStep {
    data class CallTool(val call: (SkillContext) -> ToolCall) : SkillStep
    data class WaitFor(val millis: Long) : SkillStep
    data class Assert(val predicate: (SkillContext) -> Boolean, val message: String) : SkillStep
    data class BranchOnResult(
        val onSuccess: List<SkillStep>,
        val onFailure: List<SkillStep>,
    ) : SkillStep
    data class AiSlot(val prompt: (SkillContext) -> String) : SkillStep
}

sealed interface SkillDecision {
    data class Call(val call: ToolCall) : SkillDecision
    data class Wait(val millis: Long) : SkillDecision
    data class NeedsModel(val prompt: String) : SkillDecision
    data class Failed(val message: String) : SkillDecision
    data object Complete : SkillDecision
}

data class SkillMatch(
    val skill: Skill,
    val arguments: JsonObject,
)

class SkillExecution internal constructor(
    val skill: Skill,
    val context: SkillContext,
    val startedAtMs: Long,
) {
    internal val pendingSteps = ArrayDeque<SkillStep>().apply { addAll(skill.steps) }
    internal var currentContext = context
    internal var processedSteps: Int = 0
}

class SkillEngine(
    skills: List<Skill> = defaultSkills(),
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val skillsById = LinkedHashMap<String, Skill>().apply { skills.forEach { put(it.id, it) } }

    @Synchronized
    fun register(skill: Skill): Boolean {
        if (skill.id.isBlank() || skill.version < 1 || skill.steps.isEmpty() || skill.maxWallMs !in 1..60_000) {
            return false
        }
        if (skillsById.containsKey(skill.id)) return false
        skillsById[skill.id] = skill
        return true
    }

    fun match(request: String): SkillMatch? {
        skillsById.values.forEach { skill ->
            skill.triggers.forEach { trigger ->
                trigger.match(request)?.let { return SkillMatch(skill, it) }
            }
        }
        return null
    }

    fun start(skillId: String, request: String, arguments: JsonObject): SkillExecution? =
        skillsById[skillId]?.let { skill ->
            SkillExecution(
                skill = skill,
                context = SkillContext(request, arguments),
                startedAtMs = clockMs(),
            )
        }

    fun next(execution: SkillExecution, result: ToolExecutionResult? = null): SkillDecision {
        if (clockMs() - execution.startedAtMs > execution.skill.maxWallMs) {
            return SkillDecision.Failed("Skill vượt quá thời gian cho phép.")
        }
        if (result != null) {
            execution.currentContext = execution.currentContext.copy(lastResult = result)
        }
        while (execution.pendingSteps.isNotEmpty()) {
            execution.processedSteps += 1
            if (execution.processedSteps > MAX_STEPS) {
                return SkillDecision.Failed("Skill vượt quá số bước cho phép.")
            }
            when (val step = execution.pendingSteps.removeFirst()) {
                is SkillStep.CallTool -> return SkillDecision.Call(step.call(execution.currentContext))
                is SkillStep.WaitFor -> {
                    if (step.millis !in 0..30_000) {
                        return SkillDecision.Failed("Skill có thời gian chờ không hợp lệ.")
                    }
                    return SkillDecision.Wait(step.millis)
                }
                is SkillStep.Assert -> {
                    val passed = runCatching { step.predicate(execution.currentContext) }.getOrDefault(false)
                    if (!passed) return SkillDecision.Failed(step.message)
                }
                is SkillStep.BranchOnResult -> {
                    val branch = if (execution.currentContext.lastResult?.ok == true) {
                        step.onSuccess
                    } else {
                        step.onFailure
                    }
                    execution.pendingSteps.addAll(branch)
                }
                is SkillStep.AiSlot -> return SkillDecision.NeedsModel(step.prompt(execution.currentContext))
            }
        }
        return SkillDecision.Complete
    }

    companion object {
        const val MAX_STEPS = 64

        fun defaultSkills(): List<Skill> = listOf(
            Skill(
                id = "prepare_message_to_contact",
                version = 1,
                triggers = listOf(SkillTrigger(::messageTrigger)),
                steps = listOf(
                    SkillStep.CallTool { context ->
                        ToolCall(
                            "send_sms",
                            mapOf(
                                "recipient" to context.arguments.getValue("recipient"),
                                "message" to context.arguments.getValue("message"),
                            ),
                        )
                    },
                ),
                maxWallMs = 15_000,
                risk = ToolRisk.OUTBOUND,
            ),
            Skill(
                id = "read_notifications_from_person",
                version = 1,
                triggers = listOf(SkillTrigger(::notificationTrigger)),
                steps = listOf(
                    SkillStep.CallTool { context ->
                        ToolCall("read_notifications", mapOf("filter" to context.arguments.getValue("filter")))
                    },
                ),
                maxWallMs = 10_000,
                risk = ToolRisk.INFORMATION,
            ),
        )

        private fun messageTrigger(request: String): JsonObject? {
            val pattern = Regex(
                "(?i)(?:nhắn tin|gửi tin nhắn|soạn tin nhắn|nhan tin|gui tin nhan|soan tin nhan)\\s+(?:cho\\s+)?([^:,-]{1,80})\\s*[:,-]\\s*(.{1,500})",
            )
            val match = pattern.find(request) ?: return null
            return mapOf(
                "recipient" to match.groupValues[1].trim(),
                "message" to match.groupValues[2].trim(),
            )
        }

        private fun notificationTrigger(request: String): JsonObject? {
            val normalized = normalize(request)
            if (!normalized.contains("thong bao")) return null
            val pattern = Regex("(?i)(?:xem|đọc|doc|kiểm tra|kiem tra)\\s+(?:thông báo|thong bao)\\s+(?:của|từ|cua|tu)\\s+(.{1,80})")
            val match = pattern.find(request) ?: return null
            return mapOf("filter" to match.groupValues[1].trim())
        }

        private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
            .lowercase(Locale.ROOT)
            .replace('\u0111', 'd')
            .trim()
    }
}
