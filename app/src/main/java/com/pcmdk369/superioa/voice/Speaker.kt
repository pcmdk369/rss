package com.pcmdk369.superioa.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Android-native TTS with safe asynchronous initialization.
 * Speech requested before the engine is ready is queued instead of silently dropped.
 */
class Speaker(context: Context) : TextToSpeech.OnInitListener {
    companion object {
        private const val TAG = "SuperiOA-TTS"
    }

    private val utteranceIds = AtomicLong(0)
    private enum class State { INITIALIZING, READY, FAILED, STOPPED }

    private val lock = Any()
    private val pending = ArrayDeque<String>()
    @Volatile private var state = State.INITIALIZING
    private val tts = TextToSpeech(context.applicationContext, this)

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "Android TTS initialization failed (status=$status)")
            synchronized(lock) {
                state = State.FAILED
                pending.clear()
            }
            return
        }

        // Prefer the device language, but fall back to English if its voice data is absent.
        var languageStatus = tts.setLanguage(Locale.getDefault())
        if (languageStatus < TextToSpeech.LANG_AVAILABLE) {
            languageStatus = tts.setLanguage(Locale.US)
        }

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = Unit
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "Speech playback failed (utterance=$utteranceId)")
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(TAG, "Speech playback failed (utterance=$utteranceId, error=$errorCode)")
            }
        })

        val queued = synchronized(lock) {
            if (languageStatus < TextToSpeech.LANG_AVAILABLE) {
                Log.e(TAG, "No usable TTS voice for device locale or English fallback")
                state = State.FAILED
                pending.clear()
                emptyList()
            } else {
                state = State.READY
                buildList {
                    while (pending.isNotEmpty()) add(pending.removeFirst())
                }
            }
        }
        queued.forEach(::speakReady)
    }

    fun speak(text: String) {
        val message = text.trim().take(1000)
        if (message.isBlank()) return

        val speakNow = synchronized(lock) {
            when (state) {
                State.INITIALIZING -> {
                    // Bound memory if a caller produces speech faster than TTS initializes.
                    if (pending.size >= 8) pending.removeFirst()
                    pending.addLast(message)
                    false
                }
                State.READY -> true
                State.FAILED, State.STOPPED -> false
            }
        }
        if (speakNow) speakReady(message)
    }

    private fun speakReady(text: String) {
        if (state != State.READY) return
        val utteranceId = "superioa-" + utteranceIds.incrementAndGet()
        val result = tts.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "Android TTS rejected speech request (utterance=$utteranceId)")
        }
    }

    fun shutdown() {
        synchronized(lock) {
            state = State.STOPPED
            pending.clear()
        }
        tts.stop()
        tts.shutdown()
    }
}
