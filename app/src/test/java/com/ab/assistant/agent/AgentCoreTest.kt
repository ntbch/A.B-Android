package com.ab.assistant.agent

import com.ab.assistant.tools.ToolCommand
import com.ab.assistant.tools.ToolExecutionResult
import com.ab.assistant.tools.ToolExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AgentCoreTest {
    @Test
    fun boundedAgentUsesVerifiedObservationForTheNextDecision() {
        val outputs = ArrayDeque(
            listOf(
                "{\"tool\":\"device_state\"}",
                "{\"tool\":\"web_search\",\"query\":\"thời tiết Hà Nội\"}",
                "Đã hoàn tất tác vụ.",
            ),
        )
        val prompts = mutableListOf<String>()
        val commands = mutableListOf<ToolCommand>()
        val model = AgentModel { prompt, callback ->
            prompts += prompt
            callback(outputs.removeFirst())
        }
        val executor = object : ToolExecutor {
            override fun requiredPermission(command: ToolCommand): String? = null
            override fun isAvailable(command: ToolCommand) = true
            override fun unavailableMessage(command: ToolCommand) = "unavailable"
            override fun execute(command: ToolCommand): ToolExecutionResult {
                commands += command
                return ToolExecutionResult("verified ${command.toolNameForTest()}", verified = true)
            }
        }
        var result: AgentCore.AgentResult? = null
        val core = AgentCore(model, executor, toolExecutionExecutor = Executor { it.run() })

        try {
            core.run("kiểm tra pin rồi tìm kiếm thời tiết Hà Nội") { result = it }

            assertEquals(
                listOf(
                    ToolCommand.ReadDeviceState,
                    ToolCommand.WebSearch("thời tiết Hà Nội"),
                ),
                commands,
            )
            assertEquals(3, prompts.size)
            assertTrue(prompts[1].contains("action=device_state"))
            assertEquals(AgentCore.AgentResult.Final("Đã hoàn tất tác vụ."), result)
        } finally {
            core.close()
        }
    }

    @Test
    fun malformedToolOutputGetsOneConstrainedRepair() {
        val outputs = ArrayDeque(
            listOf(
                "{\"tool\":\"device_state\",}",
                "{\"tool\":\"device_state\"}",
            ),
        )
        val prompts = mutableListOf<String>()
        val model = AgentModel { prompt, callback ->
            prompts += prompt
            callback(outputs.removeFirst())
        }
        val executor = object : ToolExecutor {
            override fun requiredPermission(command: ToolCommand): String? = null
            override fun isAvailable(command: ToolCommand) = true
            override fun unavailableMessage(command: ToolCommand) = "unavailable"
            override fun execute(command: ToolCommand) = ToolExecutionResult("device state")
        }
        var result: AgentCore.AgentResult? = null
        val core = AgentCore(model, executor, toolExecutionExecutor = Executor { it.run() })

        try {
            core.run("cho cái đèn phía sau máy sáng lên") { result = it }

            assertEquals(2, prompts.size)
            assertTrue(prompts[1].contains("previous response could not be parsed"))
            assertEquals(AgentCore.AgentResult.Final("device state"), result)
        } finally {
            core.close()
        }
    }

    @Test
    fun boundedAgentGetsOneRecoveryTurnBeforeRepeatedActionStopsIt() {
        val outputs = ArrayDeque(
            listOf(
                "{\"tool\":\"device_state\"}",
                "{\"tool\":\"device_state\"}",
                "Không cần thêm hành động.",
            ),
        )
        val prompts = mutableListOf<String>()
        val commands = mutableListOf<ToolCommand>()
        val model = AgentModel { prompt, callback ->
            prompts += prompt
            callback(outputs.removeFirst())
        }
        val executor = object : ToolExecutor {
            override fun requiredPermission(command: ToolCommand): String? = null
            override fun isAvailable(command: ToolCommand) = true
            override fun unavailableMessage(command: ToolCommand) = "unavailable"
            override fun execute(command: ToolCommand): ToolExecutionResult {
                commands += command
                return ToolExecutionResult("battery 42%", verified = true)
            }
        }
        var result: AgentCore.AgentResult? = null
        val core = AgentCore(model, executor, toolExecutionExecutor = Executor { it.run() })

        try {
            core.run("battery then search weather Hanoi") { result = it }

            assertEquals(listOf(ToolCommand.ReadDeviceState), commands)
            assertEquals(3, prompts.size)
            assertTrue(prompts[2].contains("Do not call device_state again."))
            assertEquals(AgentCore.AgentResult.Final("Không cần thêm hành động."), result)
        } finally {
            core.close()
        }
    }

    @Test
    fun boundedAgentCapsRepairAndContinuationAtFiveModelDecisions() {
        val outputs = ArrayDeque(
            listOf(
                "{\"tool\":\"device_state\"}",
                "{\"tool\":\"unknown\"}",
                "{\"tool\":\"flashlight\",\"action\":\"on\"}",
                "{\"tool\":\"flashlight\",\"action\":\"off\"}",
                "{\"tool\":\"media\",\"action\":\"play\"}",
            ),
        )
        var modelCalls = 0
        val model = AgentModel { _, callback ->
            modelCalls += 1
            callback(outputs.removeFirst())
        }
        val executor = object : ToolExecutor {
            override fun requiredPermission(command: ToolCommand): String? = null
            override fun isAvailable(command: ToolCommand) = true
            override fun unavailableMessage(command: ToolCommand) = "unavailable"
            override fun execute(command: ToolCommand) = ToolExecutionResult("ok")
        }
        var result: AgentCore.AgentResult? = null
        val core = AgentCore(model, executor, toolExecutionExecutor = Executor { it.run() })

        try {
            core.run("battery then search weather Hanoi") { result = it }

            assertEquals(5, modelCalls)
            assertEquals(
                AgentCore.AgentResult.Final("Tác vụ đã đạt giới hạn số quyết định AI an toàn."),
                result,
            )
        } finally {
            core.close()
        }
    }

    @Test
    fun stopsRepeatedFollowUpBeforeStepBudget() {
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

        assertEquals(1, executor.executions)
        assertEquals(
            AgentCore.AgentResult.Final("Tác vụ bị dừng vì lặp lại cùng một hành động."),
            result,
        )
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

    @Test
    fun rejectsChangedOutboundPayloadAfterConfirmation() {
        val model = AgentModel { _, callback -> callback("unused") }
        val executor = object : ToolExecutor {
            var executions = 0
            override fun requiredPermission(command: ToolCommand): String? = null
            override fun isAvailable(command: ToolCommand) = true
            override fun unavailableMessage(command: ToolCommand) = "unavailable"
            override fun execute(command: ToolCommand): ToolExecutionResult {
                executions += 1
                return ToolExecutionResult("sent")
            }
        }
        var confirmation: AgentCore.AgentResult? = null
        var result: AgentCore.AgentResult? = null
        val core = AgentCore(model, executor, toolExecutionExecutor = Executor { it.run() })

        core.run("SMS Nam: chào bạn") { confirmation = it }
        val pending = confirmation as AgentCore.AgentResult.ConfirmationRequired
        core.executeConfirmed(ToolCommand.SendSms("Lan", "nội dung khác")) { result = it }

        assertEquals(0, executor.executions)
        assertEquals(
            AgentCore.AgentResult.Final("Xác nhận đã hết hạn hoặc payload không còn khớp."),
            result,
        )
        assertEquals(ToolCommand.SendSms("Nam", "chào bạn"), pending.command)
    }

    @Test
    fun toolExecutionHonorsTypedSpecTimeout() {
        val started = CountDownLatch(1)
        val model = AgentModel { _, callback -> callback("{\"tool\":\"set_timer\",\"duration_minutes\":1}") }
        val executor = object : ToolExecutor {
            override fun requiredPermission(command: ToolCommand): String? = null
            override fun isAvailable(command: ToolCommand) = true
            override fun unavailableMessage(command: ToolCommand) = "unavailable"
            override fun execute(command: ToolCommand): ToolExecutionResult {
                started.countDown()
                try {
                    Thread.sleep(30_000)
                } catch (_: InterruptedException) {
                    // The timeout must cancel the worker before it can return.
                }
                return ToolExecutionResult("late result")
            }
        }
        var result: AgentCore.AgentResult? = null
        val core = AgentCore(model, executor, toolExecutionExecutor = Executor { it.run() })

        try {
            core.run("Đặt hẹn giờ") { result = it }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            assertEquals(AgentCore.AgentResult.Final("Tool execution timed out safely."), result)
        } finally {
            core.close()
        }
    }

    @Test
    fun cancellationInterruptsRunningToolAndSuppressesLateCompletion() {
        val started = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val model = AgentModel { _, callback -> callback("{\"tool\":\"set_timer\",\"duration_minutes\":1}") }
        val executor = object : ToolExecutor {
            override fun requiredPermission(command: ToolCommand): String? = null
            override fun isAvailable(command: ToolCommand) = true
            override fun unavailableMessage(command: ToolCommand) = "unavailable"
            override fun execute(command: ToolCommand): ToolExecutionResult {
                started.countDown()
                try {
                    Thread.sleep(30_000)
                } catch (_: InterruptedException) {
                    interrupted.countDown()
                }
                return ToolExecutionResult("late result")
            }
        }
        val outerExecutor = Executors.newSingleThreadExecutor()
        var result: AgentCore.AgentResult? = null
        val core = AgentCore(model, executor, toolExecutionExecutor = outerExecutor)

        try {
            core.run("Đặt hẹn giờ") { result = it }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertTrue(core.cancel())
            assertTrue(interrupted.await(2, TimeUnit.SECONDS))
            Thread.sleep(100)
            assertNull(result)
        } finally {
            core.close()
            outerExecutor.shutdownNow()
        }
    }
}

private fun ToolCommand.toolNameForTest(): String = when (this) {
    ToolCommand.ReadDeviceState -> "device_state"
    is ToolCommand.WebSearch -> "web_search"
    else -> error("Unexpected tool for this test: $this")
}
