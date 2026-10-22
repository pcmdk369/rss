package com.pcmdk369.superioa.llm

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Engine keys and settings live in EncryptedSharedPreferences (Android Keystore backed). Nothing is hard-coded. */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "superioa_secure_prefs",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun loadEngines(): List<EngineConfig> = (1..5).map { i ->
        EngineConfig(
            index = i,
            apiKey = prefs.getString("e${i}_key", "") ?: "",
            baseUrl = prefs.getString("e${i}_url", "") ?: "",
            modelId = prefs.getString("e${i}_model", "") ?: "",
            enabled = prefs.getBoolean("e${i}_on", false),
            timeoutSec = prefs.getInt("e${i}_timeout", 30),
        )
    }

    fun saveEngine(c: EngineConfig) {
        prefs.edit()
            .putString("e${c.index}_key", c.apiKey.trim())
            .putString("e${c.index}_url", c.baseUrl.trim())
            .putString("e${c.index}_model", c.modelId.trim())
            .putBoolean("e${c.index}_on", c.enabled)
            .putInt("e${c.index}_timeout", c.timeoutSec.coerceIn(5, 120))
            .apply()
    }

    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(v) = prefs.edit().putBoolean("onboarded", v).apply()

    var ttsEnabled: Boolean
        get() = prefs.getBoolean("tts", true)
        set(v) = prefs.edit().putBoolean("tts", v).apply()
}
