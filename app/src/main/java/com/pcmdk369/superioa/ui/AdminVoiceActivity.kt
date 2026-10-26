package com.pcmdk369.superioa.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getMainExecutor
import com.pcmdk369.superioa.voice.AdminVoiceAuth
import com.pcmdk369.superioa.voice.AdminVoiceService

class AdminVoiceActivity : ComponentActivity() {
    private var afterAuthentication: (() -> Unit)? = null
    private var statusSetter: ((String) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var phrase by remember { mutableStateOf("") }
            var status by remember { mutableStateOf(
                if (AdminVoiceAuth.isConfigured(this)) "Admin passphrase is enrolled. Authenticate to start listening."
                else "Enroll a secret phrase with at least 2 words and 8 characters."
            ) }
            statusSetter = { status = it }
            MaterialTheme(colorScheme = darkColorScheme()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("SuperiOA Admin Voice", style = MaterialTheme.typography.headlineSmall)
                    Text("Voice mode listens only while its ongoing notification is active. Every command must begin with your passphrase.")
                    if (!AdminVoiceAuth.isConfigured(this@AdminVoiceActivity)) {
                        OutlinedTextField(
                            value = phrase,
                            onValueChange = { phrase = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Admin voice passphrase") },
                            supportingText = { Text("Use a private phrase; do not use your account password or PIN.") },
                            singleLine = true
                        )
                        Button(
                            onClick = {
                                val value = phrase.trim()
                                if (value.length < 8 || value.split(Regex("\\s+")).size < 2) {
                                    status = "Use at least two words and 8 characters."
                                } else {
                                    authenticate {
                                        if (AdminVoiceAuth.enroll(this@AdminVoiceActivity, value)) {
                                            status = "Passphrase enrolled. Tap Start Admin Voice and authenticate again."
                                            phrase = ""
                                            recreate()
                                        } else status = "Could not enroll passphrase."
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Enroll passphrase (device authentication required)") }
                    } else {
                        Button(
                            onClick = {
                                if (!ensurePermissions()) {
                                    status = "Grant microphone and notification permissions, then tap Start again."
                                } else {
                                    authenticate {
                                        val grant = AdminVoiceAuth.grantStartAfterDeviceAuth()
                                        val serviceIntent = Intent(this@AdminVoiceActivity, AdminVoiceService::class.java)
                                            .setAction(AdminVoiceService.ACTION_START)
                                            .putExtra(AdminVoiceService.EXTRA_GRANT, grant)
                                        try {
                                            ContextCompat.startForegroundService(this@AdminVoiceActivity, serviceIntent)
                                            status = "Admin Voice foreground service requested. Keep its notification enabled."
                                        } catch (e: Exception) {
                                            status = "Could not start voice service: ${e.javaClass.simpleName}"
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Start 24/7 Admin Voice") }
                        Button(
                            onClick = {
                                startService(Intent(this@AdminVoiceActivity, AdminVoiceService::class.java).setAction(AdminVoiceService.ACTION_STOP))
                                status = "Stop requested."
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Stop Admin Voice") }
                    }
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                    Text("Privacy: audio is sent to Android's configured speech-recognition provider. The spoken passphrase is replayable and is not biometric speaker verification.")
                }
            }
        }
    }

    private fun ensurePermissions(): Boolean {
        val required = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) required += Manifest.permission.POST_NOTIFICATIONS
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 3694)
            return false
        }
        return true
    }

    private fun authenticate(onSuccess: () -> Unit) {
        val authenticators = if (Build.VERSION.SDK_INT >= 30) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Authorize SuperiOA Admin Voice")
            .setSubtitle("Verify the device owner before enabling voice control")
            .setAllowedAuthenticators(authenticators)
            .build()
        afterAuthentication = onSuccess
        val prompt = BiometricPrompt(this, getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                afterAuthentication?.invoke()
                afterAuthentication = null
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                statusSetter?.invoke("Authentication cancelled or failed: $errString")
                afterAuthentication = null
            }

            override fun onAuthenticationFailed() {
                statusSetter?.invoke("Authentication not recognized. Try again.")
            }
        })
        prompt.authenticate(info)
    }
}
