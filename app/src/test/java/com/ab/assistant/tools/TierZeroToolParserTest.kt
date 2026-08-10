package com.ab.assistant.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class TierZeroToolParserTest {
    @Test
    fun parsesVolumeAdjustmentAndDeviceStateCalls() {
        assertEquals(
            ToolCommand.AdjustVolume(VolumeStream.MUSIC, VolumeAdjustment.DOWN),
            ToolCommandParser.parse("{\"tool\":\"adjust_volume\",\"stream\":\"music\",\"direction\":\"down\"}"),
        )
        assertEquals(
            ToolCommand.ReadDeviceState,
            ToolCommandParser.parse("{\"tool\":\"device_state\"}"),
        )
    }
}
