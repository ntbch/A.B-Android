package com.ab.assistant.agent

import com.ab.assistant.tools.ToolCall
import com.ab.assistant.tools.ToolExecutionResult
import com.ab.assistant.tools.ToolRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillEngineTest {
    @Test
    fun knownPhaseFourWorkflowsMatchAndAvoidModelDecision() {
        val engine = SkillEngine()

        val message = engine.match("Soạn tin nhắn cho Nam: họp lúc 8 giờ")
        assertEquals("prepare_message_to_contact", message?.skill?.id)
        assertEquals("Nam", message?.arguments?.get("recipient"))

        val notifications = engine.match("Xem thông báo của ngân hàng")
        assertEquals("read_notifications_from_person", notifications?.skill?.id)
        assertEquals("ngân hàng", notifications?.arguments?.get("filter"))
    }

    @Test
    fun skillExecutionAdvancesThroughToolAndResult() {
        val skill = Skill(
            id = "test",
            version = 1,
            triggers = emptyList(),
            steps = listOf(
                SkillStep.CallTool { ToolCall("device_state", emptyMap()) },
                SkillStep.Assert({ it.lastResult?.ok == true }, "tool failed"),
            ),
            maxWallMs = 1_000,
            risk = ToolRisk.LOW,
        )
        val engine = SkillEngine(listOf(skill))
        val execution = engine.start("test", "test", emptyMap())!!

        val call = engine.next(execution)
        assertTrue(call is SkillDecision.Call)
        assertEquals("device_state", (call as SkillDecision.Call).call.name)
        assertEquals(
            SkillDecision.Complete,
            engine.next(execution, ToolExecutionResult("ok")),
        )
    }

    @Test
    fun branchOnResultSelectsSuccessAndFailureRecipes() {
        fun branchSkill(id: String) = Skill(
            id = id,
            version = 1,
            triggers = emptyList(),
            steps = listOf(
                SkillStep.CallTool { ToolCall("device_state", emptyMap()) },
                SkillStep.BranchOnResult(
                    onSuccess = listOf(SkillStep.Assert({ it.lastResult?.message == "success" }, "wrong success branch")),
                    onFailure = listOf(SkillStep.Assert({ it.lastResult?.message == "failure" }, "wrong failure branch")),
                ),
            ),
            maxWallMs = 1_000,
            risk = ToolRisk.LOW,
        )
        val engine = SkillEngine(listOf(branchSkill("success"), branchSkill("failure")))

        val success = engine.start("success", "success", emptyMap())!!
        assertTrue(engine.next(success) is SkillDecision.Call)
        assertEquals(SkillDecision.Complete, engine.next(success, ToolExecutionResult("success")))

        val failure = engine.start("failure", "failure", emptyMap())!!
        assertTrue(engine.next(failure) is SkillDecision.Call)
        assertEquals(SkillDecision.Complete, engine.next(failure, ToolExecutionResult("failure", ok = false)))
    }

    @Test
    fun recursiveBranchIsBounded() {
        val recursiveBranchSteps = mutableListOf<SkillStep>()
        val recursiveBranch = SkillStep.BranchOnResult(
            onSuccess = recursiveBranchSteps,
            onFailure = recursiveBranchSteps,
        )
        recursiveBranchSteps += recursiveBranch
        val engine = SkillEngine(
            listOf(
                Skill(
                    id = "loop",
                    version = 1,
                    triggers = emptyList(),
                    steps = listOf(recursiveBranch),
                    maxWallMs = 1_000,
                    risk = ToolRisk.LOW,
                ),
            ),
        )
        val execution = engine.start("loop", "loop", emptyMap())!!

        assertTrue(engine.next(execution) is SkillDecision.Failed)
    }
}
