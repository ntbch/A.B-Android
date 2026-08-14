package com.ab.assistant.voice

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.ab.assistant.AbApplication

/** Heavy voice interaction process boundary for system assistant entrypoints. */
class AbVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        AbVoiceInteractionSession(this)
}

private class AbVoiceInteractionSession(
    private val service: AbVoiceInteractionSessionService,
) : VoiceInteractionSession(service) {
    private var showRequested = false

    private val app: AbApplication?
        get() = service.application as? AbApplication

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        showRequested = true
        val application = app ?: run {
            hide()
            return
        }
        application.loadModel { backend ->
            Handler(Looper.getMainLooper()).post {
                if (!showRequested) return@post
                if (backend !in setOf("CPU", "OPENCL", "VULKAN") || !application.voiceCoordinator.start()) {
                    hide()
                }
            }
        }
    }

    override fun onHide() {
        showRequested = false
        app?.voiceCoordinator?.stop()
        super.onHide()
    }

    override fun onDestroy() {
        showRequested = false
        app?.voiceCoordinator?.stop()
        super.onDestroy()
    }
}
