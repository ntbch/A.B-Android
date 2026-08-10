package com.ab.assistant.agent

import com.ab.assistant.tools.ToolCommand
import com.ab.assistant.tools.toToolCall
import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalMessageRouterTest {
    @Test
    fun routesTheFixedCorpusMessageDirectlyToTheConfirmationPolicy() {
        assertEquals(
            RouteDecision.Direct(ToolCommand.SendSms("Nam", "10 phut nua toi").toToolCall()),
            PipelineRouter().route("nhan Nam la 10 phut nua toi"),
        )
    }
}
