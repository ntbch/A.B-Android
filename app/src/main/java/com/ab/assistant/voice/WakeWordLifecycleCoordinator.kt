package com.ab.assistant.voice

enum class WakeWordLifecycleState {
    IDLE,
    ARMED,
    LISTENING,
    PROCESSING,
    SPEAKING,
    WAITING_FOR_CONFIRMATION,
    FAILED,
}

/**
 * A low-power wake-word detector. Implementations must not start the LLM while
 * idle and must emit at most one callback per detected phrase.
 */
interface WakeWordDetector {
    fun start(onWakeWord: () -> Unit, onError: (String) -> Unit)
    fun stop()
}

/** Small port so the lifecycle coordinator can be tested without Android APIs. */
interface VoiceSessionPort {
    fun start(): Boolean
    fun stop()
    fun observe(listener: (VoiceSessionState) -> Unit): () -> Unit
}

/**
 * Owns the screen-off interaction lifecycle. The detector is active only in
 * ARMED; the detector is stopped before STT starts and is armed again after a
 * truthful spoken result.
 */
class WakeWordLifecycleCoordinator(
    private val detector: WakeWordDetector,
    private val voiceSession: VoiceSessionPort,
    private val autoRearm: Boolean = true,
) : AutoCloseable {
    private val lock = Any()
    private val listeners = LinkedHashSet<(WakeWordLifecycleState) -> Unit>()
    private var currentState = WakeWordLifecycleState.IDLE
    private var interactionActive = false
    private var removeVoiceObserver: (() -> Unit)? = voiceSession.observe(::onVoiceState)

    fun state(): WakeWordLifecycleState = synchronized(lock) { currentState }

    fun observe(listener: (WakeWordLifecycleState) -> Unit): () -> Unit {
        val initial = synchronized(lock) {
            listeners += listener
            currentState
        }
        listener(initial)
        return { synchronized(lock) { listeners -= listener } }
    }

    fun arm(): Boolean {
        synchronized(lock) {
            if (currentState != WakeWordLifecycleState.IDLE) return false
            currentState = WakeWordLifecycleState.ARMED
        }
        publish(WakeWordLifecycleState.ARMED)
        runCatching {
            detector.start(::onWakeWord, ::fail)
        }.onFailure { error -> fail(error.message ?: "Không thể bật wake word.") }
        return state() == WakeWordLifecycleState.ARMED
    }

    fun stop() {
        val shouldStop = synchronized(lock) {
            if (currentState == WakeWordLifecycleState.IDLE) return
            interactionActive = false
            currentState = WakeWordLifecycleState.IDLE
            true
        }
        if (!shouldStop) return
        runCatching { detector.stop() }
        runCatching { voiceSession.stop() }
        publish(WakeWordLifecycleState.IDLE)
    }

    override fun close() {
        stop()
        removeVoiceObserver?.invoke()
        removeVoiceObserver = null
    }

    private fun onWakeWord() {
        val accepted = synchronized(lock) {
            if (currentState != WakeWordLifecycleState.ARMED) return
            interactionActive = true
            currentState = WakeWordLifecycleState.LISTENING
            true
        }
        if (!accepted) return
        runCatching { detector.stop() }
        publish(WakeWordLifecycleState.LISTENING)
        if (!voiceSession.start()) {
            fail("Không thể bắt đầu phiên giọng nói.")
        }
    }

    private fun onVoiceState(nextVoiceState: VoiceSessionState) {
        var nextLifecycle: WakeWordLifecycleState? = null
        var rearm = false
        synchronized(lock) {
            if (!interactionActive) return
            when (nextVoiceState) {
                VoiceSessionState.LISTENING -> nextLifecycle = WakeWordLifecycleState.LISTENING
                VoiceSessionState.PROCESSING -> nextLifecycle = WakeWordLifecycleState.PROCESSING
                VoiceSessionState.SPEAKING -> nextLifecycle = WakeWordLifecycleState.SPEAKING
                VoiceSessionState.WAITING_FOR_CONFIRMATION -> {
                    nextLifecycle = WakeWordLifecycleState.WAITING_FOR_CONFIRMATION
                }
                VoiceSessionState.FAILED -> nextLifecycle = WakeWordLifecycleState.FAILED
                VoiceSessionState.IDLE -> {
                    interactionActive = false
                    currentState = WakeWordLifecycleState.IDLE
                    rearm = autoRearm
                }
            }
            nextLifecycle?.let { currentState = it }
        }
        nextLifecycle?.let(::publish)
        if (rearm) arm()
    }

    private fun fail(message: String) {
        synchronized(lock) {
            if (currentState == WakeWordLifecycleState.IDLE) return
            interactionActive = false
            currentState = WakeWordLifecycleState.FAILED
        }
        runCatching { detector.stop() }
        runCatching { voiceSession.stop() }
        publish(WakeWordLifecycleState.FAILED)
    }

    private fun publish(state: WakeWordLifecycleState) {
        val callbacks = synchronized(lock) { listeners.toList() }
        callbacks.forEach { callback -> runCatching { callback(state) } }
    }
}
