package com.ab.assistant.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ToolCommandParserInstrumentedTest {

    @Test
    fun acceptsOnlyThePhaseTwoAllowlistOnAndroid() {
        assertEquals(
            ToolCommand.FlashlightOn,
            ToolCommandParser.parse("{\"tool\":\"flashlight\",\"action\":\"on\"}"),
        )
        assertEquals(
            ToolCommand.FlashlightOff,
            ToolCommandParser.parse("{\"tool\":\"flashlight\",\"action\":\"off\"}"),
        )
        assertEquals(
            ToolCommand.SetVolume(VolumeStream.MUSIC, 50),
            ToolCommandParser.parse("{\"tool\":\"set_volume\",\"stream\":\"music\",\"level\":50}"),
        )
        assertEquals(
            ToolCommand.SetAlarm(6, 45, "Wake"),
            ToolCommandParser.parse("{\"tool\":\"set_alarm\",\"hour\":6,\"minute\":45,\"label\":\"Wake\"}"),
        )
        assertNull(ToolCommandParser.parse("```json {\"tool\":\"flashlight\",\"action\":\"on\"} ```"))
        assertNull(ToolCommandParser.parse("{\"tool\":\"set_timer\",\"duration_minutes\":0}"))
    }
}
