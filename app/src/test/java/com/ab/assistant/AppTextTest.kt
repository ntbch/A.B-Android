package com.ab.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class AppTextTest {

    @Test
    fun title_isPhase4CommunicationAgentLabel() {
        assertEquals("A.B Phase 4: Communication agent", AppText.title)
    }

    @Test
    fun mnnStatus_includesVersion() {
        assertEquals("MNN 3.6.1", AppText.mnnStatus("3.6.1"))
    }
}
