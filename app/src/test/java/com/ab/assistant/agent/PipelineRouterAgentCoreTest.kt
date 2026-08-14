package com.ab.assistant.agent

import com.ab.assistant.tools.ToolCommand
import com.ab.assistant.tools.ToolExecutionResult
import com.ab.assistant.tools.ToolExecutor
import com.ab.assistant.state.TaskSessionStore
import com.ab.assistant.state.TaskState
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineRouterAgentCoreTest {
    @Test
    fun directRouteDoesNotCallModel() {
        var modelCalls = 0
        val model = AgentModel { _, callback ->
            modelCalls++
            callback("unexpected")
        }
        var result: AgentCore.AgentResult? = null

        AgentCore(model, fakeExecutor(), toolExecutionExecutor = Executor { it.run() })
            .run("bat den pin") { result = it }

        assertEquals(0, modelCalls)
        assertEquals(AgentCore.AgentResult.Final("ok"), result)
    }

    @Test
    fun ambiguousRouteCallsModel() {
        var modelCalls = 0
        val model = AgentModel { _, callback ->
            modelCalls++
            callback("no tool")
        }
        var result: AgentCore.AgentResult? = null

        AgentCore(model, fakeExecutor(), toolExecutionExecutor = Executor { it.run() })
            .run("cho cai den phia sau may sang len") { result = it }

        assertEquals(1, modelCalls)
        assertEquals(AgentCore.AgentResult.Final("no tool"), result)
    }

    @Test
    fun instrumentedModelReceivesPromptAndToolExposureMetadata() {
        val model = RecordingModel()

        AgentCore(model, fakeExecutor(), toolExecutionExecutor = Executor { it.run() })
            .run("cho cai den phia sau may sang len") {}

        assertEquals(1, model.metadata.size)
        assertTrue(model.metadata.single().promptCharacters > 0)
        assertEquals(17, model.metadata.single().exposedToolCount)
        assertEquals(1, model.metadata.single().modelDecisionIndex)
    }

    @Test
    fun terminalToolResultDoesNotCallModelAgain() {
        var modelCalls = 0
        val model = AgentModel { _, callback ->
            modelCalls++
            callback("{\"tool\":\"set_timer\",\"duration_minutes\":1}")
        }
        var result: AgentCore.AgentResult? = null

        AgentCore(model, fakeExecutor(), toolExecutionExecutor = Executor { it.run() })
            .run("đặt hẹn giờ một phút") { result = it }

        assertEquals(1, modelCalls)
        assertEquals(AgentCore.AgentResult.Final("ok"), result)
    }

    @Test
    fun cancellationInvalidatesLateModelCallback() {
        var callback: ((String) -> Unit)? = null
        val model = AgentModel { _, onComplete -> callback = onComplete }
        val store = TaskSessionStore()
        var result: AgentCore.AgentResult? = null
        val core = AgentCore(
            model,
            fakeExecutor(),
            toolExecutionExecutor = Executor { it.run() },
            taskSessionStore = store,
        )

        core.run("một yêu cầu khó chưa rõ") { result = it }
        assertEquals(TaskState.WAITING_FOR_MODEL, store.snapshot().state)
        assertTrue(core.cancel())
        callback?.invoke("no tool")

        assertEquals(TaskState.CANCELLED, store.snapshot().state)
        assertNull(result)
    }

    @Test
    fun knownSkillRouteDoesNotCallModel() {
        var modelCalls = 0
        val model = AgentModel { _, callback ->
            modelCalls++
            callback("unexpected")
        }
        val store = TaskSessionStore()
        var result: AgentCore.AgentResult? = null

        val core = AgentCore(
            model,
            fakeExecutor(),
            toolExecutionExecutor = Executor { it.run() },
            taskSessionStore = store,
        )
        core
            .run("Soạn tin nhắn cho Nam: họp lúc 8 giờ") { result = it }

        assertEquals(0, modelCalls)
        assertTrue(result is AgentCore.AgentResult.ConfirmationRequired)
        assertEquals(TaskState.WAITING_FOR_CONFIRMATION, store.snapshot().state)
        val confirmation = result as AgentCore.AgentResult.ConfirmationRequired
        var confirmedResult: AgentCore.AgentResult? = null
        core.executeConfirmed(confirmation.command) { confirmedResult = it }
        assertEquals(AgentCore.AgentResult.Final("ok"), confirmedResult)
        assertEquals("Nam", confirmation.command.let { (it as ToolCommand.SendSms).recipient })
    }

    private fun fakeExecutor() = object : ToolExecutor {
        override fun requiredPermission(command: ToolCommand): String? = null
        override fun isAvailable(command: ToolCommand): Boolean = true
        override fun unavailableMessage(command: ToolCommand): String = "unavailable"
        override fun execute(command: ToolCommand): ToolExecutionResult = ToolExecutionResult("ok")
    }

    private class RecordingModel : InstrumentedAgentModel {
        val metadata = mutableListOf<ModelRequestMetadata>()

        override fun generate(prompt: String, onComplete: (String) -> Unit) {
            onComplete("no tool")
        }

        override fun generateWithMetadata(
            prompt: String,
            metadata: ModelRequestMetadata,
            onComplete: (String) -> Unit,
        ) {
            this.metadata += metadata
            onComplete("no tool")
        }
    }
}
