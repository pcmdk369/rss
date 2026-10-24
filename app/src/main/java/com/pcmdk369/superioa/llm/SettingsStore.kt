package com.pcmdk369.superioa.llm

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

import com.pcmdk369.superioa.BuildConfig

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
        var key = prefs.getString("e${i}_key", "") ?: ""
        var url = prefs.getString("e${i}_url", "") ?: ""
        var model = prefs.getString("e${i}_model", "") ?: ""
        var enabled = prefs.getBoolean("e${i}_on", false)

        if (i == 1) {
            val envKey = BuildConfig.LLM_ENGINE_KEY
            if (key.isBlank() && envKey.isNotBlank() && envKey != "LLM_ENGINE_KEY_DEFAULT_VALUE") {
                key = envKey
                if (!prefs.contains("e1_on")) enabled = true
            }
            // NVIDIA accepts the API root; normalize users who paste the full endpoint.
            if (url.isBlank()) url = "https://integrate.api.nvidia.com/v1"
            url = url.trim().trimEnd('/')
            if (url.endsWith("/chat/completions", ignoreCase = true)) {
                url = url.substringBeforeLast("/chat/completions").trimEnd('/')
            }
            if (model.isBlank()) model = "meta/llama-3.3-nemotron-super-49b-v1.5"
        }

        EngineConfig(
            index = i,
            apiKey = key,
            baseUrl = url,
            modelId = model,
            enabled = enabled,
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
