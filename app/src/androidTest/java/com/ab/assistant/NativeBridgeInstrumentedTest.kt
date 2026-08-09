package com.ab.assistant

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeBridgeInstrumentedTest {

    @Test
    fun nativeHello_returnsJniOk() {
        assertEquals("JNI OK", NativeBridge().hello())
    }

    @Test
    fun mnnVersion_returnsPinnedVersion() {
        assertEquals("3.6.1", NativeBridge().mnnVersion())
    }
}
