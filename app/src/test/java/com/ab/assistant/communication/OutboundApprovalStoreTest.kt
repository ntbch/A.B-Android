package com.ab.assistant.communication

import com.ab.assistant.tools.ToolCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutboundApprovalStoreTest {
    @Test
    fun approvalIsImmutableAndOneUse() {
        var now = 100L
        val store = OutboundApprovalStore(
            ttlMs = 1_000,
            clockMs = { now },
            tokenFactory = { "token-1" },
        )
        val command = ToolCommand.SendSms("Nam", "hello")
        val prepared = store.prepare(command)!!

        assertEquals("SMS tới Nam:\nhello", prepared.preview)
        assertEquals(command, store.authorize(prepared.token, command)?.command)
        assertNull(store.authorize(prepared.token, command))
    }

    @Test
    fun mismatchedOrExpiredPayloadCannotBeAuthorized() {
        var now = 100L
        val store = OutboundApprovalStore(ttlMs = 10, clockMs = { now }, tokenFactory = { "token-2" })
        val prepared = store.prepare(ToolCommand.DialContact("Nam"))!!

        assertNull(store.authorize(prepared.token, ToolCommand.DialContact("Lan")))
        val second = store.prepare(ToolCommand.DialContact("Nam"))!!
        now = 111
        assertNull(store.authorize(second.token, second.command))
    }
}
