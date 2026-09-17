package com.rvillarroel.djrodri.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface VoiceState {
    data object Idle : VoiceState
    data object Listening : VoiceState

    /** What was heard so far; [final] means the recognizer finished. */
    data class Heard(val text: String, val final: Boolean) : VoiceState
    data class Failed(val reason: String) : VoiceState
    data object Unavailable : VoiceState
}

/**
 * Speech to text on the phone itself: the audio never leaves the device and it works with no connection.
 *
 * What it produces is only text. Nothing reaches Spotify until you tap "Dale", which is also why the DJ can be
 * asked by voice without becoming a voice remote for Spotify.
 */
class VoiceInput(private val context: Context) {
    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state

    private var recognizer: SpeechRecognizer? = null

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    val onDeviceAvailable: Boolean get() = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** Must be called from the main thread: SpeechRecognizer demands it. */
    fun start() {
        if (!hasPermission) {
            _state.value = VoiceState.Failed("Falta el permiso del micrófono")
            return
        }
        if (!onDeviceAvailable && !SpeechRecognizer.isRecognitionAvailable(context)) {
            _state.value = VoiceState.Unavailable
            return
        }
        stop()
        val created = if (onDeviceAvailable) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            // No offline recognizer on this phone: the system one may send audio to its own service.
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer = created
        created.setRecognitionListener(listener)
        _state.value = VoiceState.Listening
        created.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-BO")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            },
        )
    }

    fun stop() {
        recognizer?.let {
            runCatching { it.stopListening() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }

    fun reset() {
        stop()
        _state.value = VoiceState.Idle
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _state.value = VoiceState.Listening
        }

        override fun onPartialResults(partialResults: Bundle?) {
            text(partialResults)?.let { _state.value = VoiceState.Heard(it, final = false) }
        }

        override fun onResults(results: Bundle?) {
            val heard = text(results)
            _state.value = if (heard.isNullOrBlank()) VoiceState.Failed("No te entendí") else VoiceState.Heard(heard, final = true)
            stop()
        }

        override fun onError(error: Int) {
            // A partial result already shown is better than an error: the party keeps what it heard.
            if (_state.value.let { it is VoiceState.Heard && !it.final }) {
                _state.value = VoiceState.Heard((_state.value as VoiceState.Heard).text, final = true)
            } else {
                _state.value = VoiceState.Failed(message(error))
            }
            stop()
        }

        override fun onEndOfSpeech() = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun text(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    private fun message(error: Int) = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No te entendí, probá de nuevo"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Falta el permiso del micrófono"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
            "Falta el español sin conexión: instálalo en Ajustes de Android → Idiomas → Reconocimiento de voz"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "El reconocedor está ocupado"
        else -> "No se pudo escuchar (error $error)"
    }
}
