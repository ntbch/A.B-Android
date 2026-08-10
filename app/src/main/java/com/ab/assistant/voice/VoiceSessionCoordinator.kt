package com.ab.assistant.voice

import com.ab.assistant.agent.AgentCore
import com.ab.assistant.tools.ToolCommand

enum class VoiceSessionState {
    IDLE,
    LISTENING,
    PROCESSING,
    SPEAKING,
    WAITING_FOR_CONFIRMATION,
    FAILED,
}

fun interface SpeechToTextPort {
    fun start(onResult: (String) -> Unit, onError: (String) -> Unit)
}

fun interface TextToSpeechPort {
    fun speak(text: String, onComplete: () -> Unit)
}

class VoiceSessionCoordinator(
    private val speechToText: SpeechToTextPort,
    private val textToSpeech: TextToSpeechPort,
    private val runAgent: (String, (AgentCore.AgentResult) -> Unit) -> Unit,
    private val cancelAgent: () -> Unit,
    private val executeApprovedAgent: ((ToolCommand, (AgentCore.AgentResult) -> Unit) -> Unit)? = null,
    private val executeConfirmedAgent: ((ToolCommand, (AgentCore.AgentResult) -> Unit) -> Unit)? = null,
) : VoiceSessionPort {
    private val lock = Any()
    private val listeners = LinkedHashSet<(VoiceSessionState) -> Unit>()
    private var currentState = VoiceSessionState.IDLE
    private var pendingResult: AgentCore.AgentResult? = null

    fun state(): VoiceSessionState = synchronized(lock) { currentState }

    fun pendingAction(): AgentCore.AgentResult? = synchronized(lock) { pendingResult }

    override fun observe(listener: (VoiceSessionState) -> Unit): () -> Unit {
        val initial = synchronized(lock) {
            listeners += listener
            currentState
        }
        listener(initial)
        return { synchronized(lock) { listeners -= listener } }
    }

    override fun start(): Boolean {
        synchronized(lock) {
            if (currentState != VoiceSessionState.IDLE) return false
            currentState = VoiceSessionState.LISTENING
        }
        publish(VoiceSessionState.LISTENING)
        speechToText.start(
            onResult = { transcript ->
                if (transcript.isBlank()) {
                    fail("Không nghe rõ yêu cầu.")
                } else if (changeState(VoiceSessionState.PROCESSING)) {
                    runAgent(transcript, ::handleAgentResult)
                }
            },
            onError = { message -> fail(message) },
        )
        return true
    }

    override fun stop() {
        if (state() == VoiceSessionState.IDLE) return
        synchronized(lock) { pendingResult = null }
        speechToText.stopIfSupported()
        textToSpeech.stopIfSupported()
        cancelAgent()
        changeState(VoiceSessionState.IDLE)
    }

    fun approvePending(): Boolean {
        val result = synchronized(lock) {
            val pending = pendingResult as? AgentCore.AgentResult.PermissionRequired ?: return false
            pendingResult = null
            pending
        }
        val executor = executeApprovedAgent ?: run {
            fail("Không thể tiếp tục cấp quyền cho phiên voice.")
            return false
        }
        if (!changeState(VoiceSessionState.PROCESSING)) return false
        executor(result.command, ::handleAgentResult)
        return true
    }

    fun confirmPending(): Boolean {
        val result = synchronized(lock) {
            val pending = pendingResult as? AgentCore.AgentResult.ConfirmationRequired ?: return false
            pendingResult = null
            pending
        }
        val executor = executeConfirmedAgent ?: run {
            fail("Không thể xác nhận hành động trong phiên voice.")
            return false
        }
        if (!changeState(VoiceSessionState.PROCESSING)) return false
        executor(result.command, ::handleAgentResult)
        return true
    }

    fun denyPending(): Boolean {
        val denied = synchronized(lock) {
            if (pendingResult == null) return false
            pendingResult = null
            true
        }
        if (!denied) return false
        cancelAgent()
        changeState(VoiceSessionState.IDLE)
        return true
    }

    private fun handleAgentResult(result: AgentCore.AgentResult) {
        when (result) {
            is AgentCore.AgentResult.Final -> {
                synchronized(lock) { pendingResult = null }
                speak(result.message, VoiceSessionState.IDLE)
            }
            is AgentCore.AgentResult.PermissionRequired -> {
                synchronized(lock) { pendingResult = result }
                speak(result.message, VoiceSessionState.WAITING_FOR_CONFIRMATION)
            }
            is AgentCore.AgentResult.ConfirmationRequired -> {
                synchronized(lock) { pendingResult = result }
                speak(result.message, VoiceSessionState.WAITING_FOR_CONFIRMATION)
            }
        }
    }

    private fun speak(message: String, finalState: VoiceSessionState) {
        if (!changeState(VoiceSessionState.SPEAKING)) return
        textToSpeech.speak(message) { changeState(finalState) }
    }

    private fun fail(message: String) {
        if (!changeState(VoiceSessionState.FAILED)) return
        synchronized(lock) { pendingResult = null }
        textToSpeech.speak(message) { changeState(VoiceSessionState.IDLE) }
    }

    private fun changeState(next: VoiceSessionState): Boolean {
        synchronized(lock) {
            if (currentState == VoiceSessionState.IDLE && next != VoiceSessionState.LISTENING) return false
            currentState = next
        }
        publish(next)
        return true
    }

    private fun publish(state: VoiceSessionState) {
        val callbacks = synchronized(lock) { listeners.toList() }
        callbacks.forEach { callback -> runCatching { callback(state) } }
    }

    private fun SpeechToTextPort.stopIfSupported() {
        (this as? StoppableSpeechToTextPort)?.stop()
    }
}

interface StoppableSpeechToTextPort : SpeechToTextPort {
    fun stop()
}

interface StoppableTextToSpeechPort : TextToSpeechPort {
    fun stop()
}

private fun TextToSpeechPort.stopIfSupported() {
    (this as? StoppableTextToSpeechPort)?.stop()
}
