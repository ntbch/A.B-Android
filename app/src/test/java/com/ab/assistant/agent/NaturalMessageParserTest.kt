package com.ab.assistant.agent

import com.ab.assistant.tools.ToolCommand
import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalMessageParserTest {
    @Test
    fun parsesUnambiguousVietnameseMessageWithoutColon() {
        assertEquals(
            ToolCommand.SendSms("Nam", "10 phut nua toi"),
            UserCommandParser.parse("nhan Nam la 10 phut nua toi"),
        )
        assertEquals(
            ToolCommand.SendSms("Nam", "gap luc 8 gio"),
            UserCommandParser.parse("nhan tin cho Nam la gap luc 8 gio"),
        )
    }
}
