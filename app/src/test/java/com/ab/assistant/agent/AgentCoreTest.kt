package com.ab.assistant.agent

import com.ab.assistant.tools.ToolCommand
import com.ab.assistant.tools.ToolExecutionResult
import com.ab.assistant.tools.ToolExecutor
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Executor

class AgentCoreTest {
    @Test
    fun limitsFollowUpLoopToFiveToolSteps() {
        val model = AgentModel { _, callback -> callback("{\"tool\":\"set_timer\",\"duration_minutes\":1}") }
        val executor = object : ToolExecutor {
            var executions = 0
            override fun requiredPermission(command: ToolCommand): String? = null
            override fun isAvailable(command: ToolCommand) = true
            override fun unavailableMessage(command: ToolCommand) = "unavailable"
            override fun execute(command: ToolCommand): ToolExecutionResult {
                executions += 1
                return ToolExecutionResult("step $executions", requiresFollowUp = true)
            }
        }
        var result: AgentCore.AgentResult? = null

        AgentCore(model, executor, toolExecutionExecutor = Executor { it.run() }).run("Đặt hẹn giờ") { result = it }

        assertEquals(5, executor.executions)
        assertEquals(AgentCore.AgentResult.Final("step 5"), result)
    }

    @Test
    fun reportsCameraPermissionBeforeFlashlightExecution() {
        val model = AgentModel { _, callback -> callback("{\"tool\":\"flashlight\",\"action\":\"on\"}") }
        val executor = object : ToolExecutor {
            override fun requiredPermission(command: ToolCommand) = android.Manifest.permission.CAMERA
            override fun isAvailable(command: ToolCommand) = true
            override fun unavailableMessage(command: ToolCommand) = "unavailable"
            override fun permissionMessage(command: ToolCommand, permission: String) =
                "Cần cấp quyền Camera để điều khiển đèn pin."
            override fun execute(command: ToolCommand) = error("must not execute")
        }
        var result: AgentCore.AgentResult? = null

        AgentCore(model, executor, toolExecutionExecutor = Executor { it.run() }).run("Bật đèn pin") { result = it }

        assertEquals(
            AgentCore.AgentResult.PermissionRequired(
                ToolCommand.FlashlightOn,
                listOf(android.Manifest.permission.CAMERA),
                "Cần cấp quyền Camera để điều khiển đèn pin.",
            ),
            result,
        )
    }

    @Test
    fun requiresConfirmationBeforeSmsExecution() {
        val model = AgentModel { _, callback -> callback("unused") }
        val executor = object : ToolExecutor {
            var executions = 0
            override fun requiredPermission(command: ToolCommand): String? = null
            override fun isAvailable(command: ToolCommand) = true
            override fun unavailableMessage(command: ToolCommand) = "unavailable"
            override fun requiresConfirmation(command: ToolCommand) = command is ToolCommand.SendSms
            override fun confirmationMessage(command: ToolCommand) = "confirm sms"
            override fun execute(command: ToolCommand): ToolExecutionResult {
                executions += 1
                return ToolExecutionResult("sent")
            }
        }
        var result: AgentCore.AgentResult? = null

        AgentCore(model, executor, toolExecutionExecutor = Executor { it.run() })
            .run("SMS Nam: chào bạn") { result = it }

        assertEquals(0, executor.executions)
        assertEquals(
            AgentCore.AgentResult.ConfirmationRequired(
                ToolCommand.SendSms("Nam", "chào bạn"),
                "confirm sms",
            ),
            result,
        )
    }
}
