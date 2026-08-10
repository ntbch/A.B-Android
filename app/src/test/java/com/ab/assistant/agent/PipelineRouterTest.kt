package com.ab.assistant.agent

import com.ab.assistant.tools.ToolCommand
import com.ab.assistant.tools.VolumeAdjustment
import com.ab.assistant.tools.VolumeStream
import com.ab.assistant.tools.toToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineRouterTest {
    private val router = PipelineRouter()

    @Test
    fun explicitTierZeroCommandsRouteDirectly() {
        assertEquals(RouteDecision.Direct(ToolCommand.FlashlightOn.toToolCall()), router.route("bat den pin"))
        assertEquals(
            RouteDecision.Direct(ToolCommand.AdjustVolume(VolumeStream.MUSIC, VolumeAdjustment.UP).toToolCall()),
            router.route("volume up"),
        )
        assertEquals(RouteDecision.Direct(ToolCommand.ReadDeviceState.toToolCall()), router.route("battery status"))
    }

    @Test
    fun ambiguousParaphrasesFallThroughToModelRoute() {
        assertTrue(router.route("cho cai den phia sau may sang len") is RouteDecision.ModelTool)
        assertTrue(router.route("mo cai app toi hay xem video") is RouteDecision.ModelTool)
    }

    @Test
    fun naturalMessageParaphraseUsesTheDirectConfirmationPath() {
        assertEquals(
            RouteDecision.Direct(ToolCommand.SendSms("Nam", "10 phut nua toi").toToolCall()),
            router.route("nhan Nam la 10 phut nua toi"),
        )
    }
}
