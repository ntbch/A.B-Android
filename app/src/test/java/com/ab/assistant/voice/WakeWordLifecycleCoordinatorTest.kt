package com.ab.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordLifecycleCoordinatorTest {
    @Test
    fun wakeWordStartsVoiceAndRearmsAfterSpokenResult() {
        val detector = FakeDetector()
        val voice = FakeVoiceSession()
        val states = mutableListOf<WakeWordLifecycleState>()
        val coordinator = WakeWordLifecycleCoordinator(detector, voice)
        coordinator.observe(states::add)

        assertTrue(coordinator.arm())
        detector.emitWakeWord()
        assertEquals(1, voice.startCount)
        assertEquals(WakeWordLifecycleState.LISTENING, coordinator.state())

        voice.emit(VoiceSessionState.PROCESSING)
        voice.emit(VoiceSessionState.SPEAKING)
        voice.emit(VoiceSessionState.IDLE)

        assertEquals(WakeWordLifecycleState.ARMED, coordinator.state())
        assertEquals(2, detector.startCount)
        assertTrue(states.contains(WakeWordLifecycleState.PROCESSING))
        assertTrue(states.contains(WakeWordLifecycleState.SPEAKING))
        coordinator.close()
    }

    @Test
    fun staleWakeWordAfterStopDoesNotStartVoice() {
        val detector = FakeDetector()
        val voice = FakeVoiceSession()
        val coordinator = WakeWordLifecycleCoordinator(detector, voice)

        coordinator.arm()
        coordinator.stop()
        detector.emitWakeWord()

        assertEquals(0, voice.startCount)
        assertEquals(WakeWordLifecycleState.IDLE, coordinator.state())
    }

    @Test
    fun detectorFailureIsTruthfulAndRetryRequiresExplicitStop() {
        val detector = FakeDetector()
        val coordinator = WakeWordLifecycleCoordinator(detector, FakeVoiceSession())

        coordinator.arm()
        detector.emitError("engine unavailable")

        assertEquals(WakeWordLifecycleState.FAILED, coordinator.state())
        assertFalse(coordinator.arm())
        coordinator.stop()
        assertTrue(coordinator.arm())
    }

    @Test
    fun voiceStartFailureStopsVoicePortBeforeReportingFailure() {
        val detector = FakeDetector()
        val voice = FakeVoiceSession(startResult = false)
        val coordinator = WakeWordLifecycleCoordinator(detector, voice)

        assertTrue(coordinator.arm())
        detector.emitWakeWord()

        assertEquals(1, voice.startCount)
        assertEquals(1, voice.stopCount)
        assertEquals(WakeWordLifecycleState.FAILED, coordinator.state())
        coordinator.close()
    }

    private class FakeDetector : WakeWordDetector {
        var startCount = 0
        private var onWakeWord: (() -> Unit)? = null
        private var onError: ((String) -> Unit)? = null

        override fun start(onWakeWord: () -> Unit, onError: (String) -> Unit) {
            startCount += 1
            this.onWakeWord = onWakeWord
            this.onError = onError
        }

        override fun stop() = Unit

        fun emitWakeWord() = onWakeWord?.invoke()

        fun emitError(message: String) = onError?.invoke(message)
    }

    private class FakeVoiceSession(
        private val startResult: Boolean = true,
    ) : VoiceSessionPort {
        var startCount = 0
        var stopCount = 0
        private val listeners = mutableListOf<(VoiceSessionState) -> Unit>()

        override fun start(): Boolean {
            startCount += 1
            if (startResult) emit(VoiceSessionState.LISTENING)
            return startResult
        }

        override fun stop() {
            stopCount += 1
        }

        override fun observe(listener: (VoiceSessionState) -> Unit): () -> Unit {
            listeners += listener
            listener(VoiceSessionState.IDLE)
            return { listeners -= listener }
        }

        fun emit(state: VoiceSessionState) = listeners.toList().forEach { it(state) }
    }
}
