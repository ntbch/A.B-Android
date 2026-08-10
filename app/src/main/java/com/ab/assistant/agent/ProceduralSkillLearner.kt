package com.ab.assistant.agent

import com.ab.assistant.tools.ToolCall
import com.ab.assistant.tools.ToolCommand
import com.ab.assistant.tools.ToolExecutionResult
import com.ab.assistant.tools.ToolRisk
import com.ab.assistant.tools.toToolCall
import java.text.Normalizer
import java.util.LinkedHashMap
import java.util.Locale

enum class SkillCandidateState {
    DRAFT,
    TESTED,
    APPROVED,
    REJECTED,
}

data class SkillCandidate(
    val id: String,
    val triggerText: String,
    val steps: List<ToolCall>,
    val risk: ToolRisk,
    val evidenceCount: Int,
    val createdAtMs: Long,
    val state: SkillCandidateState,
)

data class SkillReplayResult(
    val candidateId: String,
    val passed: Boolean,
    val message: String,
)

/** In-memory, opt-in learning boundary; it never persists or registers automatically. */
class ProceduralSkillLearner(
    private val minimumEvidence: Int = 2,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private data class ActiveTrajectory(
        val request: String,
        val steps: MutableList<ToolCall> = mutableListOf(),
        var failed: Boolean = false,
    )

    private val active = LinkedHashMap<Long, ActiveTrajectory>()
    private val candidates = LinkedHashMap<String, SkillCandidate>()

    init {
        require(minimumEvidence >= 2) { "A learned skill needs repeated evidence." }
    }

    @Synchronized
    fun begin(taskId: Long, request: String) {
        active[taskId] = ActiveTrajectory(request.trim())
    }

    @Synchronized
    fun recordStep(taskId: Long, command: ToolCommand, result: ToolExecutionResult) {
        val trajectory = active[taskId] ?: return
        if (!result.ok) trajectory.failed = true
        trajectory.steps += command.toToolCall()
    }

    @Synchronized
    fun complete(taskId: Long, successful: Boolean): SkillCandidate? {
        val trajectory = active.remove(taskId) ?: return null
        if (!successful || trajectory.failed || trajectory.steps.isEmpty()) return null
        val signature = signature(trajectory.request, trajectory.steps)
        val current = candidates[signature]
        val next = if (current == null) {
            SkillCandidate(
                id = candidateId(signature),
                triggerText = trajectory.request,
                steps = trajectory.steps.toList(),
                risk = riskOf(trajectory.steps),
                evidenceCount = 1,
                createdAtMs = clockMs(),
                state = SkillCandidateState.DRAFT,
            )
        } else if (current.state == SkillCandidateState.REJECTED) {
            return current
        } else {
            current.copy(evidenceCount = current.evidenceCount + 1)
        }
        candidates[signature] = next
        return next
    }

    @Synchronized
    fun candidates(): List<SkillCandidate> = candidates.values.toList()

    @Synchronized
    fun inspect(candidateId: String): SkillCandidate? = candidates.values.firstOrNull { it.id == candidateId }

    /** Replay is an explicit test step; it does not mutate the active runtime. */
    fun replay(candidateId: String, execute: (ToolCall) -> ToolExecutionResult): SkillReplayResult {
        val candidate = synchronized(this) { candidates.values.firstOrNull { it.id == candidateId } }
            ?: return SkillReplayResult(candidateId, false, "Candidate không tồn tại.")
        if (candidate.state == SkillCandidateState.APPROVED) {
            return SkillReplayResult(candidateId, false, "Candidate đã được đăng ký.")
        }
        for ((index, step) in candidate.steps.withIndex()) {
            val result = runCatching { execute(step) }.getOrElse {
                return SkillReplayResult(candidateId, false, "Replay bước ${index + 1} bị lỗi an toàn.")
            }
            if (!result.ok) {
                return SkillReplayResult(candidateId, false, "Replay thất bại ở bước ${index + 1}: ${result.message}")
            }
        }
        synchronized(this) {
            candidates.entries.firstOrNull { it.value.id == candidateId }?.let { entry ->
                entry.setValue(entry.value.copy(state = SkillCandidateState.TESTED))
            }
        }
        return SkillReplayResult(candidateId, true, "Replay candidate đạt.")
    }

    /** Explicit approval is the only path that registers a learned Skill. */
    @Synchronized
    fun approve(candidateId: String, skillEngine: SkillEngine): Boolean {
        val entry = candidates.entries.firstOrNull { it.value.id == candidateId } ?: return false
        val candidate = entry.value
        if (candidate.state != SkillCandidateState.TESTED || candidate.evidenceCount < minimumEvidence) {
            return false
        }
        if (!skillEngine.register(candidate.toSkill())) return false
        entry.setValue(candidate.copy(state = SkillCandidateState.APPROVED))
        return true
    }

    @Synchronized
    fun reject(candidateId: String): Boolean {
        val entry = candidates.entries.firstOrNull { it.value.id == candidateId } ?: return false
        if (entry.value.state == SkillCandidateState.APPROVED) return false
        entry.setValue(entry.value.copy(state = SkillCandidateState.REJECTED))
        return true
    }

    private fun SkillCandidate.toSkill(): Skill = Skill(
        id = "learned_$id",
        version = 1,
        triggers = listOf(
            SkillTrigger { request ->
                if (normalize(request) == normalize(triggerText)) emptyMap() else null
            },
        ),
        steps = steps.map { call -> SkillStep.CallTool { call } },
        maxWallMs = 15_000,
        risk = risk,
    )

    private fun signature(request: String, steps: List<ToolCall>): String = buildString {
        append(normalize(request))
        steps.forEach { step ->
            append('|').append(step.name)
            step.arguments.toSortedMap().forEach { (key, value) -> append('|').append(key).append('=').append(value) }
        }
    }

    private fun candidateId(signature: String): String = Integer.toUnsignedString(signature.hashCode(), 16)

    private fun riskOf(steps: List<ToolCall>): ToolRisk = when {
        steps.any { it.name == "send_sms" || it.name == "dial_contact" } -> ToolRisk.OUTBOUND
        steps.any { it.name == "read_notifications" || it.name == "find_contact" || it.name == "web_search" } -> ToolRisk.INFORMATION
        else -> ToolRisk.LOW
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace("\\p{M}+".toRegex(), "")
        .lowercase(Locale.ROOT)
        .replace('\u0111', 'd')
        .trim()
}
