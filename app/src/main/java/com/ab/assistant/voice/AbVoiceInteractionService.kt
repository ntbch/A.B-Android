package com.ab.assistant.voice

import android.content.ComponentName
import android.service.voice.VoiceInteractionService
import com.ab.assistant.AbApplication
import com.ab.assistant.state.Capability
import com.ab.assistant.state.CapabilityState

/**
 * System-owned, lightweight assistant boundary. Android keeps the selected
 * VoiceInteractionService alive for hotwording; heavy work starts only after
 * WakeWordLifecycleCoordinator accepts a detector event.
 */
class AbVoiceInteractionService : VoiceInteractionService() {
    private var lifecycle: WakeWordLifecycleCoordinator? = null
    private var removeObserver: (() -> Unit)? = null

    override fun onReady() {
        super.onReady()
        isRunning = true
        val app = application as AbApplication
        lifecycle = WakeWordLifecycleCoordinator(
            detector = WakeWordDetectorProvider.create(this),
            voiceSession = app.voiceCoordinator,
        )
        removeObserver = lifecycle?.observe { state ->
            val capability = when (state) {
                WakeWordLifecycleState.ARMED,
                WakeWordLifecycleState.LISTENING,
                WakeWordLifecycleState.PROCESSING,
                WakeWordLifecycleState.SPEAKING,
                WakeWordLifecycleState.WAITING_FOR_CONFIRMATION -> CapabilityState.READY
                WakeWordLifecycleState.FAILED -> CapabilityState.DEGRADED
                WakeWordLifecycleState.IDLE -> CapabilityState.DISABLED
            }
            app.capabilityCoordinator.set(Capability.WAKE_WORD, capability)
        }
        lifecycle?.arm()
    }

    override fun onShutdown() {
        isRunning = false
        removeObserver?.invoke()
        removeObserver = null
        lifecycle?.close()
        lifecycle = null
        (application as? AbApplication)?.capabilityCoordinator?.set(
            Capability.WAKE_WORD,
            CapabilityState.DISABLED,
        )
        super.onShutdown()
    }

    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set

        fun isActive(context: android.content.Context): Boolean =
            isRunning || VoiceInteractionService.isActiveService(
                context,
                ComponentName(context, AbVoiceInteractionService::class.java),
            )
    }
}
