package com.ab.assistant.agent

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
    fun exposedToolCountMatchesScopedSchemas() {
        assertTrue(builder.exposedToolCount(setOf(ToolGroup.COMMUNICATION)) == 2)
        assertTrue(builder.exposedToolCount(setOf(ToolGroup.DEVICE, ToolGroup.INFORMATION)) == 11)
    }

    @Test
    fun benchmarkProfilesExposeOnlyTheRequestedSchemaCount() {
        for (count in listOf(4, 8, 16)) {
            val prompt = builder.benchmarkInitial("đo prompt", count)
            assertTrue(prompt.lines().count { it.startsWith("{\"tool\":") } == count)
        }
        assertTrue(builder.benchmarkSchemaCount() >= 16)
    }
}
