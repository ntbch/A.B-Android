package com.ab.assistant.voice

import com.ab.assistant.agent.AgentCore
import com.ab.assistant.tools.ToolCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSessionCoordinatorTest {
    @Test
    fun pttFlowsFromSpeechToAgentToSpeechOutput() {
        val speech = FakeSpeech()
        var agentCallback: ((AgentCore.AgentResult) -> Unit)? = null
        val states = mutableListOf<VoiceSessionState>()
        val coordinator = VoiceSessionCoordinator(
            speechToText = speech,
            textToSpeech = TextToSpeechPort { _, onComplete -> onComplete() },
            runAgent = { _, callback -> agentCallback = callback },
            cancelAgent = {},
        )
        coordinator.observe { states += it }

        assertTrue(coordinator.start())
        speech.result("bật đèn pin")
        assertEquals(VoiceSessionState.PROCESSING, coordinator.state())
        agentCallback?.invoke(AgentCore.AgentResult.Final("Đã bật đèn pin."))

        assertEquals(VoiceSessionState.IDLE, coordinator.state())
        assertTrue(states.contains(VoiceSessionState.LISTENING))
        assertTrue(states.contains(VoiceSessionState.SPEAKING))
    }

    @Test
    fun outboundVoiceResultStopsAtConfirmation() {
        val speech = FakeSpeech()
        var agentCallback: ((AgentCore.AgentResult) -> Unit)? = null
        val coordinator = VoiceSessionCoordinator(
            speechToText = speech,
            textToSpeech = TextToSpeechPort { _, onComplete -> onComplete() },
            runAgent = { _, callback -> agentCallback = callback },
            cancelAgent = {},
        )

        coordinator.start()
        speech.result("nhắn tin")
        agentCallback?.invoke(
            AgentCore.AgentResult.ConfirmationRequired(
                ToolCommand.SendSms("Nam", "hello"),
                "Xác nhận gửi",
            ),
        )

        assertEquals(VoiceSessionState.WAITING_FOR_CONFIRMATION, coordinator.state())
    }

    @Test
    fun confirmationCanResumeThroughTheSameAgentGate() {
        val speech = FakeSpeech()
        var agentCallback: ((AgentCore.AgentResult) -> Unit)? = null
        var confirmed: ToolCommand? = null
        val coordinator = VoiceSessionCoordinator(
            speechToText = speech,
            textToSpeech = TextToSpeechPort { _, onComplete -> onComplete() },
            runAgent = { _, callback -> agentCallback = callback },
            cancelAgent = {},
            executeConfirmedAgent = { command, callback ->
                confirmed = command
                callback(AgentCore.AgentResult.Final("Đã gửi"))
            },
        )

        coordinator.start()
        speech.result("nhắn tin")
        val command = ToolCommand.SendSms("Nam", "hello")
        agentCallback?.invoke(AgentCore.AgentResult.ConfirmationRequired(command, "Xác nhận gửi"))

        assertTrue(coordinator.pendingAction() is AgentCore.AgentResult.ConfirmationRequired)
        assertTrue(coordinator.confirmPending())
        assertEquals(command, confirmed)
        assertEquals(VoiceSessionState.IDLE, coordinator.state())
    }

    @Test
    fun stopCancelsInFlightSpeechOutput() {
        val speech = FakeSpeech()
        val output = FakeSpeechOutput()
        var agentCallback: ((AgentCore.AgentResult) -> Unit)? = null
        val coordinator = VoiceSessionCoordinator(
            speechToText = speech,
            textToSpeech = output,
            runAgent = { _, callback -> agentCallback = callback },
            cancelAgent = {},
        )

        coordinator.start()
        speech.result("bật đèn pin")
        agentCallback?.invoke(AgentCore.AgentResult.Final("Đã bật đèn pin."))
        assertEquals(VoiceSessionState.SPEAKING, coordinator.state())

        coordinator.stop()
        output.complete()

        assertEquals(1, output.stopCount)
        assertEquals(VoiceSessionState.IDLE, coordinator.state())
        assertFalse(output.completed)
    }

    private class FakeSpeech : StoppableSpeechToTextPort {
        private var onResult: ((String) -> Unit)? = null
        override fun start(onResult: (String) -> Unit, onError: (String) -> Unit) {
            this.onResult = onResult
        }

        fun result(value: String) = onResult?.invoke(value)

        override fun stop() {
            onResult = null
        }
    }

    private class FakeSpeechOutput : StoppableTextToSpeechPort {
        var stopCount = 0
        var completed = false
        private var onComplete: (() -> Unit)? = null

        override fun speak(text: String, onComplete: () -> Unit) {
            this.onComplete = onComplete
        }

        override fun stop() {
            stopCount += 1
            onComplete = null
        }

        fun complete() {
            val callback = onComplete ?: return
            callback()
            completed = true
        }
    }
}
