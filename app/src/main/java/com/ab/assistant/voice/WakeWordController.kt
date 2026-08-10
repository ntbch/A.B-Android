package com.ab.assistant.voice

import android.content.Context
import android.content.Intent
import android.provider.Settings

object WakeWordController {
    fun isActive(context: Context): Boolean = AbVoiceInteractionService.isActive(context)

    fun openAssistantSettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
        if (intent.resolveActivity(context.packageManager) == null) return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }
}
