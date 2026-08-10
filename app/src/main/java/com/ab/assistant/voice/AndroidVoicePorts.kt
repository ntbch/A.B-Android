package com.ab.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

class AndroidSpeechToTextPort(context: Context) : StoppableSpeechToTextPort {
    private val recognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
    val isAvailable: Boolean = SpeechRecognizer.isRecognitionAvailable(context.applicationContext)
    private var onResult: ((String) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    init {
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                onResult?.invoke(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
            }

            override fun onError(error: Int) {
                onError?.invoke("Speech recognition error: $error")
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
    }

    override fun start(onResult: (String) -> Unit, onError: (String) -> Unit) {
        this.onResult = onResult
        this.onError = onError
        if (!isAvailable) {
            onError("Thiết bị không có dịch vụ nhận dạng giọng nói.")
            return
        }
        recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        })
    }

    override fun stop() {
        recognizer.cancel()
        onResult = null
        onError = null
    }
}

class AndroidTextToSpeechPort(
    context: Context,
    private val onReadyChanged: (Boolean) -> Unit = {},
) : StoppableTextToSpeechPort {
    private var ready = false
    private var pending: Pair<String, () -> Unit>? = null
    private lateinit var textToSpeech: TextToSpeech
    private val pendingCallbacks = mutableMapOf<String, () -> Unit>()

    init {
        textToSpeech = TextToSpeech(context.applicationContext) { status ->
            ready = if (status == TextToSpeech.SUCCESS) {
                val languageStatus = textToSpeech.setLanguage(Locale.forLanguageTag("vi-VN"))
                languageStatus != TextToSpeech.LANG_MISSING_DATA &&
                    languageStatus != TextToSpeech.LANG_NOT_SUPPORTED
            } else {
                false
            }
            onReadyChanged(ready)
            pending?.let { (text, callback) ->
                pending = null
                speak(text, callback)
            }
        }
        textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onDone(utteranceId: String?) {
                pendingCallbacks.remove(utteranceId)?.invoke()
            }

            override fun onError(utteranceId: String?) {
                pendingCallbacks.remove(utteranceId)?.invoke()
            }
            override fun onStart(utteranceId: String?) = Unit
        })
    }

    override fun speak(text: String, onComplete: () -> Unit) {
        if (!ready) {
            pending = text to onComplete
            return
        }
        val id = UUID.randomUUID().toString()
        pendingCallbacks[id] = onComplete
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    override fun stop() {
        textToSpeech.stop()
        pending = null
        pendingCallbacks.clear()
    }
}
