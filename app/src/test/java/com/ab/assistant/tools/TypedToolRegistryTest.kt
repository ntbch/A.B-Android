package com.ab.assistant.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TypedToolRegistryTest {
    @Test
    fun unknownToolIsRejectedWithoutCallingLegacyExecutor() {
        var executions = 0
        val registry = TypedToolRegistry(fakeExecutor { executions++ })

        val result = registry.execute(ToolCall("not_registered", emptyMap()))

        assertEquals(ToolStatus.REJECTED, result.status)
        assertEquals("UNKNOWN_TOOL", result.errorCode)
        assertEquals(0, executions)
    }

    @Test
    fun malformedArgumentsAreRejectedBeforeExecution() {
        var executions = 0
        val registry = TypedToolRegistry(fakeExecutor { executions++ })

        val result = registry.execute(
            ToolCall(
                "set_volume",
                mapOf("stream" to "music", "level" to 101, "extra" to true),
            ),
        )

        assertEquals(ToolStatus.REJECTED, result.status)
        assertEquals("MALFORMED_ARGUMENTS", result.errorCode)
        assertEquals(0, executions)
    }

    @Test
    fun validCallReachesLegacyExecutorAndMapsResult() {
        var received: ToolCommand? = null
        val registry = TypedToolRegistry(fakeExecutor { command -> received = command })

        val result = registry.execute(ToolCall("set_timer", mapOf("duration_minutes" to 5)))

        assertEquals(ToolStatus.SUCCESS, result.status)
        assertTrue(result.verified)
        assertEquals(ToolCommand.SetTimer(5), received)
    }

    @Test
    fun unverifiedSuccessfulExecutorResultStaysUnverified() {
        val registry = TypedToolRegistry(object : ToolExecutor {
            override fun requiredPermission(command: ToolCommand): String? = null
            override fun isAvailable(command: ToolCommand): Boolean = true
            override fun unavailableMessage(command: ToolCommand): String = "unavailable"
            override fun execute(command: ToolCommand): ToolExecutionResult =
                ToolExecutionResult("Intent dispatched.", verified = false)
        })

        val result = registry.execute(ToolCommand.OpenApp("YouTube"))

        assertEquals(ToolStatus.SUCCESS, result.status)
        assertFalse(result.verified)
    }

    @Test
    fun unverifiedResultSurvivesContractRoundTrip() {
        val original = ToolResult(
            status = ToolStatus.SUCCESS,
            summary = "Intent dispatched.",
            verified = false,
            retryable = false,
        )

        val roundTrip = original.toToolExecutionResult()

        assertTrue(roundTrip.ok)
        assertFalse(roundTrip.verified)
    }

    @Test
    fun smsSpecRequiresConfirmation() {
        val spec = TypedToolRegistry(fakeExecutor {}).spec(ToolCommand.SendSms("Nam", "hello"))

        assertEquals(ConfirmationPolicy.REQUIRED, spec.confirmation)
        assertEquals(ToolRisk.OUTBOUND, spec.risk)
        assertFalse(spec.inputSchema.isEmpty())
    }

    private fun fakeExecutor(onExecute: (ToolCommand) -> Unit): ToolExecutor = object : ToolExecutor {
        override fun requiredPermission(command: ToolCommand): String? = null
        override fun isAvailable(command: ToolCommand): Boolean = true
        override fun unavailableMessage(command: ToolCommand): String = "unavailable"
        override fun execute(command: ToolCommand): ToolExecutionResult {
            onExecute(command)
            return ToolExecutionResult("ok")
        }
    }
}
