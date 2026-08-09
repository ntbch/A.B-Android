package com.ab.assistant.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolCommandParserTest {

    @Test
    fun parsesOnlyAllowlistedFlashlightCommands() {
        assertEquals(
            ToolCommand.FlashlightOn,
            ToolCommandParser.parse("{\"tool\":\"flashlight\",\"action\":\"on\"}"),
        )
        assertEquals(
            ToolCommand.FlashlightOff,
            ToolCommandParser.parse("{ \"tool\" : \"flashlight\", \"action\" : \"off\" }"),
        )
    }

    @Test
    fun rejectsProseMarkdownAndUnapprovedJson() {
        assertNull(ToolCommandParser.parse("Turned it on: {\"tool\":\"flashlight\",\"action\":\"on\"}"))
        assertNull(ToolCommandParser.parse("```json\n{\"tool\":\"flashlight\",\"action\":\"on\"}\n```"))
        assertNull(ToolCommandParser.parse("{\"tool\":\"contacts\",\"action\":\"on\"}"))
        assertNull(ToolCommandParser.parse("{\"tool\":\"flashlight\",\"action\":\"blink\"}"))
        assertNull(ToolCommandParser.parse("{\"tool\":\"flashlight\",\"action\":\"on\",\"extra\":true}"))
    }

    @Test
    fun parsesPhaseTwoDeviceCommandsWithinTheirSchemas() {
        assertEquals(
            ToolCommand.OpenApp("Chrome"),
            ToolCommandParser.parse("{\"tool\":\"open_app\",\"app\":\"Chrome\"}"),
        )
        assertEquals(
            ToolCommand.SetVolume(VolumeStream.MUSIC, 35),
            ToolCommandParser.parse("{\"tool\":\"set_volume\",\"stream\":\"music\",\"level\":35}"),
        )
        assertEquals(
            ToolCommand.Media(MediaAction.NEXT),
            ToolCommandParser.parse("{\"tool\":\"media\",\"action\":\"next\"}"),
        )
        assertEquals(
            ToolCommand.SetTimer(15),
            ToolCommandParser.parse("{\"tool\":\"set_timer\",\"duration_minutes\":15}"),
        )
        assertEquals(
            ToolCommand.SetAlarm(7, 30, "Di lam"),
            ToolCommandParser.parse("{\"tool\":\"set_alarm\",\"hour\":7,\"minute\":30,\"label\":\"Di lam\"}"),
        )
    }

    @Test
    fun rejectsOutOfRangeAndEscapedDeviceCommands() {
        assertNull(ToolCommandParser.parse("{\"tool\":\"set_volume\",\"stream\":\"music\",\"level\":101}"))
        assertNull(ToolCommandParser.parse("{\"tool\":\"set_timer\",\"duration_minutes\":0}"))
        assertNull(ToolCommandParser.parse("{\"tool\":\"set_alarm\",\"hour\":24,\"minute\":0,\"label\":\"x\"}"))
        assertNull(ToolCommandParser.parse("{\"tool\":\"open_app\",\"app\":\"Chrome\\\" beta\"}"))
    }

    @Test
    fun parsesPhaseThreeInformationCommandsWithinTheirSchemas() {
        assertEquals(
            ToolCommand.ReadNotifications("Zalo"),
            ToolCommandParser.parse("{\"tool\":\"read_notifications\",\"filter\":\"Zalo\"}"),
        )
        assertEquals(
            ToolCommand.FindContact("Nam"),
            ToolCommandParser.parse("{\"tool\":\"find_contact\",\"name\":\"Nam\"}"),
        )
        assertEquals(
            ToolCommand.WebSearch("thời tiết Hà Nội"),
            ToolCommandParser.parse("{\"tool\":\"web_search\",\"query\":\"thời tiết Hà Nội\"}"),
        )
        assertNull(ToolCommandParser.parse("{\"tool\":\"web_search\",\"query\":\"x\\\"y\"}"))
        assertEquals(
            ToolCommand.SendSms("Nam", "Minh den roi"),
            ToolCommandParser.parse("{\"tool\":\"send_sms\",\"recipient\":\"Nam\",\"message\":\"Minh den roi\"}"),
        )
        assertEquals(
            ToolCommand.DialContact("Nam"),
            ToolCommandParser.parse("{\"tool\":\"dial_contact\",\"recipient\":\"Nam\"}"),
        )
    }
}
