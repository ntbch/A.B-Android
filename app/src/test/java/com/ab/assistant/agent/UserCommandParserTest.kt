package com.ab.assistant.agent

import com.ab.assistant.tools.MediaAction
import com.ab.assistant.tools.ToolCommand
import com.ab.assistant.tools.VolumeStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserCommandParserTest {
    @Test
    fun parsesExplicitVietnameseDeviceCommands() {
        assertEquals(ToolCommand.FlashlightOff, UserCommandParser.parse("Tắt đèn pin"))
        assertEquals(ToolCommand.OpenApp("Chrome"), UserCommandParser.parse("Mở Chrome"))
        assertEquals(
            ToolCommand.SetVolume(VolumeStream.MUSIC, 35),
            UserCommandParser.parse("Đặt âm lượng nhạc 35%"),
        )
        assertEquals(ToolCommand.Media(MediaAction.NEXT), UserCommandParser.parse("Bài tiếp"))
        assertEquals(ToolCommand.SetTimer(15), UserCommandParser.parse("Hẹn giờ 15 phút"))
        assertEquals(ToolCommand.SetAlarm(7, 30, ""), UserCommandParser.parse("Đặt báo thức 7:30"))
        assertEquals(ToolCommand.WebSearch("thời tiết Hà Nội"), UserCommandParser.parse("Tìm kiếm thời tiết Hà Nội"))
        assertEquals(ToolCommand.FindContact("Nam"), UserCommandParser.parse("Tìm liên hệ Nam"))
        assertEquals(ToolCommand.ReadNotifications("zalo"), UserCommandParser.parse("Đọc thông báo từ Zalo"))
        assertEquals(
            ToolCommand.SendSms("Nam", "Mình đến rồi"),
            UserCommandParser.parse("Nhắn tin Nam: Mình đến rồi"),
        )
        assertEquals(ToolCommand.DialContact("Nam"), UserCommandParser.parse("Gọi Nam"))
    }

    @Test
    fun doesNotGuessUnclearRequests() {
        assertNull(UserCommandParser.parse("Hôm nay trời thế nào?"))
        assertNull(UserCommandParser.parse("Mở"))
        assertNull(UserCommandParser.parse("Đặt âm lượng lớn hơn"))
    }
}
