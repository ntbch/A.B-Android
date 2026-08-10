package com.ab.assistant.voice

import android.os.Bundle
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
    private val app: AbApplication?
        get() = service.application as? AbApplication

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val voice = app?.voiceCoordinator
        if (voice == null || !voice.start()) {
            hide()
        }
    }

    override fun onHide() {
        app?.voiceCoordinator?.stop()
        super.onHide()
    }

    override fun onDestroy() {
        app?.voiceCoordinator?.stop()
        super.onDestroy()
    }
}
