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

        // Optional build-time defaults are provider-neutral. No vendor or model is selected for users.
        if (i == 1) {
            val envKey = BuildConfig.LLM_ENGINE_API
            val envUrl = BuildConfig.LLM_PROVIDER_URL
            val envModel = BuildConfig.LLM_MODEL
            if (key.isBlank() && envKey.isNotBlank() && envKey != "LLM_ENGINE_API_DEFAULT_VALUE") key = envKey
            if (url.isBlank() && envUrl.isNotBlank() && envUrl != "LLM_PROVIDER_URL_DEFAULT_VALUE") url = envUrl
            if (model.isBlank() && envModel.isNotBlank() && envModel != "LLM_MODEL_DEFAULT_VALUE") model = envModel
            if (!prefs.contains("e1_on") && key.isNotBlank() && url.isNotBlank() && model.isNotBlank()) enabled = true
        }

        EngineConfig(
            index = i,
            apiKey = key,
            baseUrl = url.trim().trimEnd('/'),
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

    var realtimeGatewayUrl: String
        get() = prefs.getString("realtime_gateway_url", "") ?: ""
        set(value) = prefs.edit().putString("realtime_gateway_url", value.trim()).apply()

    var realtimeClientToken: String
        get() = prefs.getString("realtime_client_token", "") ?: ""
        set(value) = prefs.edit().putString("realtime_client_token", value.trim()).apply()

    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(v) = prefs.edit().putBoolean("onboarded", v).apply()

    var ttsEnabled: Boolean
        get() = prefs.getBoolean("tts", true)
        set(v) = prefs.edit().putBoolean("tts", v).apply()
}
