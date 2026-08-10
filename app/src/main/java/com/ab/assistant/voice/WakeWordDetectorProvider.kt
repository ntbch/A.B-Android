package com.ab.assistant.voice

import android.content.Context

/** Registration point for a real system/DSP hotword detector. */
object WakeWordDetectorProvider {
    @Volatile
    var factory: (Context) -> WakeWordDetector = { UnavailableWakeWordDetector() }

    fun create(context: Context): WakeWordDetector = factory(context.applicationContext)
}

private class UnavailableWakeWordDetector : WakeWordDetector {
    override fun start(onWakeWord: () -> Unit, onError: (String) -> Unit) {
        onError("Wake-word engine chưa được cấu hình trên thiết bị này.")
    }

    override fun stop() = Unit
}
