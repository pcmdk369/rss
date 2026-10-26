package com.pcmdk369.superioa.voice

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Device credential/biometric is verified by AdminVoiceActivity.
 * The passphrase hash is stored in Keystore-backed encrypted preferences.
 * Voice passphrases are replayable; this is a second gate, not speaker biometrics.
 */
object AdminVoiceAuth {
    private const val PREFS = "superioa_admin_voice"
    private const val SALT_KEY = "passphrase_salt"
    private const val HASH_KEY = "passphrase_hash"
    private val lock = Any()
    private var pendingGrant: String? = null

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context.applicationContext,
        PREFS,
        MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun isConfigured(context: Context): Boolean {
        val p = prefs(context)
        return !p.getString(SALT_KEY, null).isNullOrBlank() &&
            !p.getString(HASH_KEY, null).isNullOrBlank()
    }

    fun enroll(context: Context, phrase: String): Boolean {
        val normalized = normalize(phrase)
        if (normalized.length < 8 || normalized.split(" ").size < 2) return false
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val hash = hash(normalized, salt)
        prefs(context).edit()
            .putString(SALT_KEY, salt.toHex())
            .putString(HASH_KEY, hash.toHex())
            .apply()
        return true
    }

    /** Return the command only when this utterance starts with the enrolled passphrase. */
    fun authorizedCommand(context: Context, utterance: String): String? {
        val p = prefs(context)
        val salt = p.getString(SALT_KEY, null)?.fromHex() ?: return null
        val expected = p.getString(HASH_KEY, null)?.fromHex() ?: return null
        val rawWords = utterance.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        // Match the normalized prefix, but preserve the recognized command's original casing.
        for (count in 2 until rawWords.size) {
            val phrase = normalize(rawWords.take(count).joinToString(" "))
            if (MessageDigest.isEqual(hash(phrase, salt), expected)) {
                return rawWords.drop(count).joinToString(" ").trim()
                    .trimStart(',', ':', ';')
                    .trim()
                    .takeIf { it.length >= 2 }
            }
        }
        return null
    }

    /** In-memory one-use grant: service cannot be started by an arbitrary external app. */
    fun grantStartAfterDeviceAuth(): String = synchronized(lock) {
        java.util.UUID.randomUUID().toString().also { pendingGrant = it }
    }

    fun consumeStartGrant(value: String?): Boolean = synchronized(lock) {
        if (value.isNullOrBlank() || value != pendingGrant) return@synchronized false
        pendingGrant = null
        true
    }

    private fun normalize(value: String) = value.lowercase()
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        .trim().replace(Regex("\\s+"), " ")

    private fun hash(value: String, salt: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        return digest.digest(value.toByteArray(Charsets.UTF_8))
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun String.fromHex(): ByteArray? {
        if (length % 2 != 0) return null
        return try {
            chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (_: Exception) {
            null
        }
    }
}
