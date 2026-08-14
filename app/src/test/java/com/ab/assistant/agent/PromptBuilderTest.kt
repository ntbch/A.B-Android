package com.ab.assistant.agent

import com.ab.assistant.state.TaskObservation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {
    private val builder = PromptBuilder()

    @Test
    fun communicationPromptExposesOnlyCommunicationSchemas() {
        val prompt = builder.initial("Nhắn tin cho Nam", setOf(ToolGroup.COMMUNICATION))

        assertTrue(prompt.contains("\"tool\":\"send_sms\""))
        assertTrue(prompt.contains("\"tool\":\"dial_contact\""))
        assertFalse(prompt.contains("\"tool\":\"flashlight\""))
        assertFalse(prompt.contains("\"tool\":\"web_search\""))
    }

    @Test
    fun devicePromptIncludesNewTierZeroSchemasAndIsSmallerThanFullPrompt() {
        val devicePrompt = builder.initial("Trạng thái thiết bị", setOf(ToolGroup.DEVICE))
        val fullPrompt = builder.initial("Trạng thái thiết bị")

        assertTrue(devicePrompt.contains("\"tool\":\"adjust_volume\""))
        assertTrue(devicePrompt.contains("\"tool\":\"device_state\""))
        assertTrue(devicePrompt.length < fullPrompt.length)
    }

    @Test
    fun conversationalPromptHasNoToolSchemas() {
        val prompt = builder.initial("hello", emptySet())

        assertFalse(prompt.contains("{\"tool\":"))
        assertTrue(prompt.contains("Do not output JSON"))
    }

    @Test
    fun exposedToolCountMatchesScopedSchemas() {
        assertTrue(builder.exposedToolCount(setOf(ToolGroup.COMMUNICATION)) == 2)
        assertTrue(builder.exposedToolCount(setOf(ToolGroup.DEVICE, ToolGroup.INFORMATION)) == 10)
        assertTrue(builder.exposedToolCount(setOf(ToolGroup.DEVICE_STATE)) == 1)
    }

    @Test
    fun benchmarkProfilesExposeOnlyTheRequestedSchemaCount() {
        for (count in listOf(4, 8, 16)) {
            val prompt = builder.benchmarkInitial("đo prompt", count)
            assertTrue(prompt.lines().count { it.startsWith("{\"tool\":") } == count)
        }
        assertTrue(builder.benchmarkSchemaCount() >= 16)
    }

    @Test
    fun agentContinuationCarriesBoundedVerifiedEvidence() {
        val prompt = builder.agentAfterTool(
            userRequest = "kiểm tra pin rồi tìm kiếm thời tiết",
            observations = listOf(
                TaskObservation(1, "device_state", "pin 42%", ok = true, verified = true, code = "OK"),
            ),
            remainingToolDecisions = 4,
            exposedToolGroups = setOf(ToolGroup.DEVICE, ToolGroup.INFORMATION),
        )

        assertTrue(prompt.contains("action=device_state"))
        assertTrue(prompt.contains("at most 4 additional"))
        assertTrue(prompt.contains("\"tool\":\"web_search\""))
    }
}
