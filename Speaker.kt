package com.pcmdk369.superioa.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Android-native TTS. STT is handled in the UI through the system RecognizerIntent. */
class Speaker(context: Context) : TextToSpeech.OnInitListener {
    private var ready = false
    private val tts = TextToSpeech(context.applicationContext, this)

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) tts.language = Locale.getDefault()
    }

    fun speak(text: String) {
        if (ready && text.isNotBlank()) tts.speak(text.take(300), TextToSpeech.QUEUE_FLUSH, null, "superioa")
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
