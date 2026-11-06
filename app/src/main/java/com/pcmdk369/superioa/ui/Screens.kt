package com.pcmdk369.superioa.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.pcmdk369.superioa.voice.RealtimeVoiceState
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pcmdk369.superioa.exec.SuperiAccessibilityService
import com.pcmdk369.superioa.llm.EngineConfig
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    if (!vm.onboarded) {
        OnboardingScreen(vm)
        return
    }
    var tab by remember { mutableStateOf(0) }
    Scaffold(
        containerColor = Color(0xFF070B14),
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF0B1321), contentColor = Color(0xFFB8D2F3)) {
                listOf("Voice", "Activity", "Settings").forEachIndexed { i, label ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Text(listOf("\u25B6", "\u23F1", "\u2699")[i]) },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> HomeScreen(vm)
                1 -> HistoryScreen(vm)
                else -> SettingsScreen(vm)
            }
        }
    }
}

@Composable
private fun rememberA11yEnabled(): Boolean {
    var enabled by remember { mutableStateOf(SuperiAccessibilityService.instance != null) }
    LaunchedEffect(Unit) {
        while (true) {
            enabled = SuperiAccessibilityService.instance != null
            delay(1000)
        }
    }
    return enabled
}

@Composable
fun OnboardingScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val a11y = rememberA11yEnabled()
    var agreed by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Welcome to SuperiOA", style = MaterialTheme.typography.headlineMedium)
        Text("An assistant that plans a task with an LLM and then performs it on your phone through Android Accessibility.")
        Text("What you must know", style = MaterialTheme.typography.titleMedium)
        Text(
            "\u2022 SuperiOA reads on-screen text and can tap, type and scroll, but only while a task you started is running.\n" +
                "\u2022 Screen text (never password fields) is sent to the LLM provider YOU configure so it can plan. Obvious secrets are redacted first.\n" +
                "\u2022 It stops and asks you for CAPTCHA, OTP, passwords, biometrics, payments and high-impact taps (send, delete, buy...).\n" +
                "\u2022 A Stop button is always available. Your API keys are stored encrypted on this device."
        )
        Text(
            if (a11y) "Accessibility service: ENABLED" else "Accessibility service: not enabled yet",
            style = MaterialTheme.typography.titleSmall
        )
        OutlinedButton(onClick = {
            ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) { Text("Open Accessibility settings") }
        OutlinedButton(onClick = {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) { Text("Open SuperiOA app info") }
        Text("If Android shows Restricted setting, open SuperiOA app info and look for Allow restricted settings in the app-info menu. If your phone does not offer that option, Android/OEM policy may block Accessibility for this install source; the app cannot enable it by itself.")
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(checked = agreed, onCheckedChange = { agreed = it })
            Text("I understand and agree")
        }
        Button(enabled = agreed, onClick = { vm.finishOnboarding() }) { Text("Continue") }
    }
}

@Composable
fun HomeScreen(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    val voiceState = vm.realtimeState
    val a11y = rememberA11yEnabled()
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startRealtimeVoice()
    }
    val connected = voiceState !in setOf(RealtimeVoiceState.DISCONNECTED, RealtimeVoiceState.ERROR)
    val label = when (voiceState) {
        RealtimeVoiceState.CONNECTING -> "CONNECTING"
        RealtimeVoiceState.LISTENING -> "LISTENING"
        RealtimeVoiceState.THINKING -> "THINKING"
        RealtimeVoiceState.SPEAKING -> "SPEAKING"
        RealtimeVoiceState.EXECUTING -> "EXECUTING TASK"
        RealtimeVoiceState.ERROR -> "CONNECTION ERROR"
        RealtimeVoiceState.DISCONNECTED -> "READY"
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF070B14)).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("SUPERIOA", color = Color(0xFFEAF4FF), style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, letterSpacing = 2.sp)
                Text("REALTIME INTELLIGENCE", color = Color(0xFF7188A8), style = MaterialTheme.typography.labelSmall, letterSpacing = 2.sp)
            }
            Surface(color = if (connected) Color(0xFF102A2A) else Color(0xFF30231F), shape = RoundedCornerShape(50)) {
                Text(if (connected) "LIVE SESSION" else "VOICE OFFLINE", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = if (connected) Color(0xFF71E4C1) else Color(0xFFFFB18C), style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(when (voiceState) {
            RealtimeVoiceState.CONNECTING -> "Connecting to SuperiOA"
            RealtimeVoiceState.LISTENING -> "I am listening"
            RealtimeVoiceState.THINKING -> "Thinking"
            RealtimeVoiceState.SPEAKING -> "Speaking"
            RealtimeVoiceState.EXECUTING -> "Working on your device"
            RealtimeVoiceState.ERROR -> "Voice connection needs attention"
            RealtimeVoiceState.DISCONNECTED -> "Talk to SuperiOA"
        }, color = Color(0xFFF1F7FF), style = MaterialTheme.typography.headlineMedium)
        Text(when (voiceState) {
            RealtimeVoiceState.CONNECTING -> "Preparing a secure, temporary audio session."
            RealtimeVoiceState.LISTENING -> "Speak naturally. You can interrupt the assistant."
            RealtimeVoiceState.THINKING -> "Understanding your request."
            RealtimeVoiceState.SPEAKING -> "Live voice response is streaming."
            RealtimeVoiceState.EXECUTING -> "The Android agent is performing and verifying the task."
            RealtimeVoiceState.ERROR -> "Check the gateway and microphone configuration in Settings."
            RealtimeVoiceState.DISCONNECTED -> "Full-duplex voice conversation. No typing required."
        }, color = Color(0xFF91A8C8), style = MaterialTheme.typography.bodyMedium)
        VoiceOrb(active = connected, thinking = voiceState == RealtimeVoiceState.THINKING || voiceState == RealtimeVoiceState.EXECUTING, speaking = voiceState == RealtimeVoiceState.SPEAKING)
        Surface(color = Color(0xFF101A2B), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("VOICE SESSION", color = Color(0xFFB8CDE8), style = MaterialTheme.typography.labelMedium, letterSpacing = 1.5.sp)
                    Text(label, color = Color(0xFF65D9FF), style = MaterialTheme.typography.labelSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    VoiceStage("01", "LISTEN", voiceState == RealtimeVoiceState.LISTENING)
                    VoiceStage("02", "THINK", voiceState == RealtimeVoiceState.THINKING || voiceState == RealtimeVoiceState.EXECUTING)
                    VoiceStage("03", "SPEAK", voiceState == RealtimeVoiceState.SPEAKING)
                }
            }
        }
        if (!a11y) Surface(color = Color(0xFF2B1B24), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("DEVICE CONTROL NOT READY", color = Color(0xFFFFB2C0), style = MaterialTheme.typography.labelMedium)
                Text("Enable SuperiOA Accessibility in Android settings to let voice commands operate your device. Voice conversation itself can work without it.", color = Color(0xFFE8C8D0), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Open Accessibility settings") }
            }
        }
        vm.realtimeError?.let { Text(it, color = Color(0xFFFFA6A6), style = MaterialTheme.typography.bodySmall) }
        if (vm.realtimeTranscript.isNotBlank()) Surface(color = Color(0xFF0E1726), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Text(vm.realtimeTranscript.takeLast(1200), modifier = Modifier.padding(14.dp), color = Color(0xFF9EB6D5), style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = {
            if (connected) vm.stopRealtimeVoice()
            else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.startRealtimeVoice()
            else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }, modifier = Modifier.fillMaxWidth().height(60.dp), shape = RoundedCornerShape(20.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2869E8), contentColor = Color.White)) {
            Text(if (connected) "END VOICE SESSION" else "START VOICE", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, letterSpacing = 1.sp)
        }
        state.approval?.let { reason -> Surface(color = Color(0xFF2A2418), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("YOUR APPROVAL IS REQUIRED", color = Color(0xFFFFD58A), style = MaterialTheme.typography.labelMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text(reason, color = Color(0xFFF2E4C8), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { vm.approve(true) }) { Text("Allow") }
                    OutlinedButton(onClick = { vm.approve(false) }) { Text("Deny & stop") }
                }
            }
        } }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun VoiceStage(number: String, label: String, active: Boolean) {
    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(number, color = if (active) Color(0xFF6DDCFF) else Color(0xFF52647D), style = MaterialTheme.typography.labelSmall)
        Text(label, color = if (active) Color(0xFFE7F6FF) else Color(0xFF7488A4), style = MaterialTheme.typography.labelSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
    }
}

@Composable
private fun VoiceOrb(active: Boolean, thinking: Boolean, speaking: Boolean) {
    val transition = rememberInfiniteTransition(label = "voice-orb")
    val pulse by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(if (active) 900 else 2200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "orb-pulse"
    )
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(if (thinking) 5000 else 14000, easing = LinearEasing), RepeatMode.Restart),
        label = "orb-rotation"
    )
    Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(280.dp)) {
            val center = this.center
            val scale = pulse
            drawCircle(Color(0xFF145DCC).copy(alpha = 0.10f), radius = size.minDimension * 0.47f * scale)
            drawCircle(Color(0xFF247DFF).copy(alpha = 0.12f), radius = size.minDimension * 0.39f * scale)
            drawCircle(Color(0xFF62D8FF).copy(alpha = 0.38f), radius = size.minDimension * 0.31f, style = Stroke(width = 1.5.dp.toPx()))
            drawCircle(Color(0xFF5D9DFF).copy(alpha = 0.42f), radius = size.minDimension * 0.39f, style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 8.dp.toPx()), rotation)))
            drawCircle(
                brush = Brush.radialGradient(listOf(Color(0xFFE4FFFF), if (speaking) Color(0xFF55E5FF) else Color(0xFF50B9FF), Color(0xFF2455D8), Color(0xFF101B55)), center, size.minDimension * 0.27f),
                radius = size.minDimension * 0.255f * scale,
                center = center
            )
            drawCircle(Color.White.copy(alpha = 0.20f), radius = size.minDimension * 0.07f, center = Offset(center.x - size.minDimension * 0.075f, center.y - size.minDimension * 0.10f))
        }
    }
}

@Composable
fun HistoryScreen(vm: MainViewModel) {
    val logs by vm.history.collectAsState(initial = emptyList())
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Execution history", style = MaterialTheme.typography.headlineSmall)
        if (logs.isEmpty()) {
            Text("Your completed voice workflows will appear here. No screen contents are stored.", color = Color(0xFF91A8C8))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(logs) { h ->
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF101A2B))) {
                        Column(Modifier.padding(12.dp)) {
                            Text(h.goal, color = Color(0xFFEAF4FF), style = MaterialTheme.typography.titleSmall)
                            Text("${h.status}  \u00B7  ${DateFormat.getDateTimeInstance().format(Date(h.startedAt))}" +
                                if (h.engine > 0) "  \u00B7  engine #${h.engine}" else "")
                            Text(h.summary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val workflows by vm.workflowCount.collectAsState(initial = 0)
    val a11y = rememberA11yEnabled()
    val anyEngine = vm.engines.any { it.isUsable }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF070B14)).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Control center", color = Color(0xFFEAF4FF), style = MaterialTheme.typography.headlineMedium)
            Text("Configure your voice assistant, intelligence and device access.", color = Color(0xFF91A8C8), style = MaterialTheme.typography.bodyMedium)
        }

        Surface(color = Color(0xFF101A2B), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("SYSTEM HEALTH", color = Color(0xFFB8CDE8), style = MaterialTheme.typography.labelMedium, letterSpacing = 1.5.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Accessibility", color = Color(0xFFDAE7F8))
                    Text(if (a11y) "ENABLED" else "DISABLED", color = if (a11y) Color(0xFF71E4C1) else Color(0xFFFFB18C), style = MaterialTheme.typography.labelSmall)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("AI engine", color = Color(0xFFDAE7F8))
                    Text(if (anyEngine) "CONFIGURED" else "NOT CONFIGURED", color = if (anyEngine) Color(0xFF71E4C1) else Color(0xFFFFB18C), style = MaterialTheme.typography.labelSmall)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }, modifier = Modifier.weight(1f)) { Text("Accessibility") }
                    OutlinedButton(onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }, modifier = Modifier.weight(1f)) { Text("App permissions") }
                }
            }
        }

        Surface(color = Color(0xFF101A2B), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Spoken responses", color = Color(0xFFEAF4FF), style = MaterialTheme.typography.titleMedium)
                    Text("SuperiOA speaks task results aloud using Android voice.", color = Color(0xFF91A8C8), style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = vm.tts, onCheckedChange = { vm.setTts(it) })
            }
        }

        var gatewayUrl by remember { mutableStateOf(vm.realtimeGatewayUrl) }
        var gatewayToken by remember { mutableStateOf(vm.realtimeClientToken) }
        Surface(color = Color(0xFF101A2B), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("REALTIME VOICE GATEWAY", color = Color(0xFFB8CDE8), style = MaterialTheme.typography.labelMedium, letterSpacing = 1.2.sp)
                Text("The gateway keeps the long-lived provider key on the server and issues short-lived session credentials to this device.", color = Color(0xFF91A8C8), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = gatewayUrl, onValueChange = { gatewayUrl = it }, label = { Text("Gateway HTTPS URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = gatewayToken, onValueChange = { gatewayToken = it }, label = { Text("Device access token") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(enabled = gatewayUrl.startsWith("https://") && gatewayToken.isNotBlank(), onClick = { vm.saveRealtimeSettings(gatewayUrl, gatewayToken) }) { Text("Save Realtime settings") }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("INTELLIGENCE ENGINES", color = Color(0xFF8FA8C9), style = MaterialTheme.typography.labelMedium, letterSpacing = 1.5.sp)
            Text("Provider-neutral configuration. Use any service that supports the OpenAI-compatible HTTPS chat completions API. No company, provider or model is preselected. Configure up to five independent engines.", color = Color(0xFF91A8C8), style = MaterialTheme.typography.bodySmall)
            vm.engines.forEach { engine -> EngineCard(engine) { vm.saveEngine(it) } }
        }

        Surface(color = Color(0xFF101A2B), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("LOCAL MEMORY & PRIVACY", color = Color(0xFFB8CDE8), style = MaterialTheme.typography.labelMedium, letterSpacing = 1.2.sp)
                Text("Saved workflows: $workflows", color = Color(0xFFEAF4FF), style = MaterialTheme.typography.titleMedium)
                Text("Workflow history stays on this device. Passwords, OTPs, tokens and screen contents are not stored as reusable workflows. Sensitive tasks are excluded from workflow replay.", color = Color(0xFF91A8C8), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { vm.clearWorkflows() }, modifier = Modifier.weight(1f)) { Text("Clear memory") }
                    OutlinedButton(onClick = { vm.clearHistory() }, modifier = Modifier.weight(1f)) { Text("Clear activity") }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun EngineCard(e: EngineConfig, onSave: (EngineConfig) -> Unit) {
    var key by remember(e) { mutableStateOf(e.apiKey) }
    var url by remember(e) { mutableStateOf(e.baseUrl) }
    var model by remember(e) { mutableStateOf(e.modelId) }
    var on by remember(e) { mutableStateOf(e.enabled) }
    var timeout by remember(e) { mutableStateOf(e.timeoutSec.toString()) }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF101A2B))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Switch(checked = on, onCheckedChange = { on = it })
                Text("  Custom provider ${e.index}", style = MaterialTheme.typography.titleSmall)
            }
            OutlinedTextField(
                value = key, onValueChange = { key = it }, label = { Text(if (e.index == 1) "LLM_ENGINE_API · API key / token" else "LLM_ENGINE_${e.index}_API · API key / token") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = url, onValueChange = { url = it }, label = { Text(if (e.index == 1) "LLM_PROVIDER_URL · Base URL" else "LLM_PROVIDER_${e.index}_URL · Base URL") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = model, onValueChange = { model = it }, label = { Text(if (e.index == 1) "LLM_MODEL · Model ID" else "LLM_MODEL_${e.index} · Model ID") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = timeout, onValueChange = { timeout = it.filter(Char::isDigit).take(3) },
                label = { Text("Timeout (seconds, 5-120)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            val urlOk = url.isBlank() || url.trim().startsWith("https://")
            if (!urlOk) Text("Base URL must start with https://", color = MaterialTheme.colorScheme.error)
            Button(enabled = urlOk, onClick = {
                onSave(EngineConfig(e.index, key, url, model, on, timeout.toIntOrNull() ?: 30))
            }) { Text("Save engine ${e.index}") }
        }
    }
}
