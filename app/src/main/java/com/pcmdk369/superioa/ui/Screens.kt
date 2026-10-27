package com.pcmdk369.superioa.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pcmdk369.superioa.exec.SuperiAccessibilityService
import com.pcmdk369.superioa.llm.EngineConfig
import com.pcmdk369.superioa.orchestrator.TaskStatus
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
        bottomBar = {
            NavigationBar {
                listOf("Home", "History", "Settings").forEachIndexed { i, label ->
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
    val a11y = rememberA11yEnabled()
    val anyEngine = vm.engines.any { it.isUsable }
    var goal by remember { mutableStateOf("") }
    val running = state.status == TaskStatus.PLANNING || state.status == TaskStatus.RUNNING ||
        state.status == TaskStatus.WAITING_USER

    // Voice-service commands and Home-screen commands now share the same Orchestrator state.
    LaunchedEffect(state.goal) {
        state.goal?.takeIf { it.isNotBlank() }?.let { goal = it }
    }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val spokenCommand = r.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (spokenCommand != null) {
            goal = spokenCommand
            // Voice is a command entry point, not merely dictation: run immediately
            // when the local executor and at least one LLM engine are ready.
            if (a11y && anyEngine && !running) {
                vm.start(spokenCommand)
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("SuperiOA", style = MaterialTheme.typography.headlineSmall)
        Text("Voice starts the command automatically when Accessibility and an LLM engine are ready. Use Run for typed commands.")
        if (!a11y) Text("\u26A0 Accessibility service is off. Enable it in Android settings (see the Settings tab).", color = MaterialTheme.colorScheme.error)
        if (!anyEngine) Text("\u26A0 No LLM engine configured. Open Settings and enable at least one engine.", color = MaterialTheme.colorScheme.error)

        OutlinedTextField(
            value = goal, onValueChange = { goal = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("What should I do?") }, enabled = !running, minLines = 2
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !running, onClick = {
                try {
                    speech.launch(
                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    )
                } catch (ignored: ActivityNotFoundException) {
                }
            }) { Text("\uD83C\uDFA4 Voice") }
            Button(enabled = !running && goal.isNotBlank() && a11y && anyEngine, onClick = { vm.start(goal) }) { Text("Run") }
            Button(
                enabled = running, onClick = { vm.stop() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text("Stop") }
        }
        Text(
            "Status: ${state.status}" + (state.engine?.let { " (engine #$it)" } ?: ""),
            style = MaterialTheme.typography.titleSmall
        )
        state.finalMessage?.let { if (!running) Text(it) }
        if (state.log.isEmpty()) {
            Text("No task yet. Example: \"Open Settings and turn on Wi-Fi, then verify it says On.\"")
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(state.log.reversed()) { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }

    state.approval?.let { reason ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Your approval is needed") },
            text = { Text(reason) },
            confirmButton = { TextButton(onClick = { vm.approve(true) }) { Text("Allow / Done") } },
            dismissButton = { TextButton(onClick = { vm.approve(false) }) { Text("Deny & stop") } }
        )
    }
}

@Composable
fun HistoryScreen(vm: MainViewModel) {
    val logs by vm.history.collectAsState(initial = emptyList())
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Execution history", style = MaterialTheme.typography.headlineSmall)
        if (logs.isEmpty()) {
            Text("Nothing here yet. Finished tasks appear here (goal and result only; no screen contents are stored).")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(logs) { h ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(h.goal, style = MaterialTheme.typography.titleSmall)
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
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Text("Accessibility is a protected Android setting. SuperiOA cannot grant it automatically. If blocked as a restricted setting, use App info to look for the Android-provided allow option.")
        OutlinedButton(onClick = {
            ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) { Text("Open Accessibility settings") }
        OutlinedButton(onClick = {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) { Text("Open SuperiOA app info") }

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Switch(checked = vm.tts, onCheckedChange = { vm.setTts(it) })
            Text("  Speak results aloud (Android TTS)")
        }

        Text("LLM engines (tried in order 1 \u2192 5)", style = MaterialTheme.typography.titleMedium)
        Text(
            "Each engine must expose an OpenAI-style POST {base URL}/chat/completions over https. " +
                "Keys are stored encrypted on this device. Keys inside any app can be extracted from a rooted device; " +
                "for long-lived keys prefer your own backend proxy URL as the base URL."
        )
        vm.engines.forEach { e -> EngineCard(e) { vm.saveEngine(it) } }

        Text("Workflow memory", style = MaterialTheme.typography.titleMedium)
        Text(
            "Stores, per task: normalized goal, app package, validated action steps, success/failure counts, last-used time. " +
                "Never stores passwords, OTPs, tokens or screen contents. Runs that hit a security checkpoint are never saved. " +
                "Saved workflows: $workflows"
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.clearWorkflows() }) { Text("Clear workflows") }
            OutlinedButton(onClick = { vm.clearHistory() }) { Text("Clear history") }
        }
    }
}

@Composable
private fun EngineCard(e: EngineConfig, onSave: (EngineConfig) -> Unit) {
    var key by remember(e) { mutableStateOf(e.apiKey) }
    var url by remember(e) { mutableStateOf(e.baseUrl) }
    var model by remember(e) { mutableStateOf(e.modelId) }
    var on by remember(e) { mutableStateOf(e.enabled) }
    var timeout by remember(e) { mutableStateOf(e.timeoutSec.toString()) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Switch(checked = on, onCheckedChange = { on = it })
                Text("  Engine ${e.index}", style = MaterialTheme.typography.titleSmall)
            }
            OutlinedTextField(
                value = key, onValueChange = { key = it }, label = { Text("LLM_ENGINE_${e.index}_API_KEY") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = url, onValueChange = { url = it }, label = { Text("LLM_ENGINE_${e.index}_BASE_URL (https://...)") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = model, onValueChange = { model = it }, label = { Text("LLM_ENGINE_${e.index}_MODEL_ID") },
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
