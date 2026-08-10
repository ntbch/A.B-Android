package com.ab.assistant.agent

import com.ab.assistant.tools.ToolCall
import com.ab.assistant.tools.ToolCommand
import com.ab.assistant.tools.ToolExecutionResult
import com.ab.assistant.tools.ToolExecutor
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProceduralSkillLearnerTest {
    @Test
    fun repeatedSuccessfulTrajectoryNeedsReplayAndApproval() {
        val learner = ProceduralSkillLearner(clockMs = { 123L })
        recordSuccessfulRun(learner, 1L)
        val first = learner.complete(1L, successful = true)
        recordSuccessfulRun(learner, 2L)
        val second = learner.complete(2L, successful = true)

        assertEquals(1, first?.evidenceCount)
        assertEquals(2, second?.evidenceCount)
        assertEquals(SkillCandidateState.DRAFT, second?.state)
        assertFalse(learner.approve(second!!.id, SkillEngine()))

        val replay = learner.replay(second.id) { ToolExecutionResult("ok") }
        assertTrue(replay.passed)
        val engine = SkillEngine()
        assertTrue(learner.approve(second.id, engine))
        assertEquals(SkillCandidateState.APPROVED, learner.inspect(second.id)?.state)
        assertNotNull(engine.match("Bật đèn pin"))
    }

    @Test
    fun failedTrajectoryIsNotCandidate() {
        val learner = ProceduralSkillLearner()
        learner.begin(1L, "bật đèn pin")
        learner.recordStep(
            1L,
            ToolCommand.FlashlightOn,
            ToolExecutionResult("camera permission missing", ok = false),
        )

        assertEquals(null, learner.complete(1L, successful = false))
        assertTrue(learner.candidates().isEmpty())
    }

    @Test
    fun agentCoreFeedsSuccessfulTrajectoriesToLearner() {
        val learner = ProceduralSkillLearner()
        val executor = object : ToolExecutor {
            override fun requiredPermission(command: ToolCommand): String? = null
            override fun isAvailable(command: ToolCommand): Boolean = true
            override fun unavailableMessage(command: ToolCommand): String = "unavailable"
            override fun execute(command: ToolCommand): ToolExecutionResult = ToolExecutionResult("ok")
        }
        val core = AgentCore(
            model = AgentModel { _, callback -> callback("unused") },
            toolRegistry = executor,
            toolExecutionExecutor = Executor { it.run() },
            proceduralSkillLearner = learner,
        )

        core.run("bật đèn pin") {}
        core.run("bật đèn pin") {}

        assertEquals(2, learner.candidates().single().evidenceCount)
    }

    private fun recordSuccessfulRun(learner: ProceduralSkillLearner, taskId: Long) {
        learner.begin(taskId, "bật đèn pin")
        learner.recordStep(taskId, ToolCommand.FlashlightOn, ToolExecutionResult("ok"))
    }
}
