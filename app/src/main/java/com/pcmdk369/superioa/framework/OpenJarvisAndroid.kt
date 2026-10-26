package com.pcmdk369.superioa.framework

import com.pcmdk369.superioa.llm.EngineCaller
import com.pcmdk369.superioa.llm.EngineConfig
import com.pcmdk369.superioa.llm.OpenAiCompatibleCaller
import com.pcmdk369.superioa.memory.TaskLogEntity
import java.util.Locale

/**
 * Android-native adaptation of OpenJarvis's pluggable primitives.
 *
 * This is intentionally Kotlin-only: upstream Python/Rust/desktop components are not
 * copied into the APK. Device-side effects must still go through ActionValidator,
 * SecurityGate and the Android Accessibility executor.
 */
data class ModelSpec(
    val modelId: String,
    val provider: String,
    val protocol: String,
    val configuredEngineIndex: Int,
    val loopbackEndpoint: Boolean = false,
    val contextLength: Int? = null,
    val capabilities: Set<String> = emptySet(),
)

object ModelCatalog {
    /** Reflects actual user configuration; it does not claim an unconfigured model is available. */
    fun fromConfiguredEngines(engines: List<EngineConfig>): List<ModelSpec> =
        engines.filter { it.enabled && it.modelId.isNotBlank() && it.baseUrl.isNotBlank() }
            .sortedBy { it.index }
            .map { engine ->
                ModelSpec(
                    modelId = engine.modelId,
                    provider = providerName(engine.baseUrl),
                    protocol = "openai-compatible-chat-completions",
                    configuredEngineIndex = engine.index,
                    loopbackEndpoint = isLoopback(engine.baseUrl),
                )
            }

    private fun providerName(baseUrl: String): String {
        val host = runCatching { java.net.URI(baseUrl).host.orEmpty().lowercase(Locale.ROOT) }
            .getOrDefault("")
        return when {
            host == "localhost" || host == "127.0.0.1" || host == "::1" -> "local-compatible-server"
            host.contains("openai") -> "openai-compatible"
            host.contains("nvidia") -> "nvidia-compatible"
            host.isBlank() -> "unknown"
            else -> host
        }
    }

    private fun isLoopback(baseUrl: String): Boolean {
        val host = runCatching { java.net.URI(baseUrl).host.orEmpty().lowercase(Locale.ROOT) }
            .getOrDefault("")
        return host == "localhost" || host == "127.0.0.1" || host == "::1"
    }
}

/** Engine adapter registry: implementations are replaceable without rewriting the orchestrator. */
object EngineAdapterRegistry {
    private val factories = linkedMapOf<String, () -> EngineCaller>(
        "openai-compatible" to { OpenAiCompatibleCaller() },
    )

    @Synchronized
    fun register(protocol: String, factory: () -> EngineCaller) {
        require(protocol.isNotBlank()) { "protocol must not be blank" }
        factories[protocol] = factory
    }

    @Synchronized
    fun create(protocol: String): EngineCaller =
        factories[protocol]?.invoke()
            ?: throw IllegalArgumentException("No engine adapter registered for '$protocol'")

    @Synchronized
    fun registeredProtocols(): Set<String> = factories.keys.toSet()
}

/** A small pluggable agent profile; profiles shape planning, never execute device actions. */
data class AgentProfile(
    val id: String,
    val description: String,
    val matches: (String) -> Boolean,
    val systemGuidance: String,
)

object AgentProfileRegistry {
    private val profiles = linkedMapOf<String, AgentProfile>(
        "android-device" to AgentProfile(
            id = "android-device",
            description = "Android UI and device control",
            matches = { q ->
                listOf("open ", "tap ", "click ", "send ", "message ", "call ", "settings", "wifi", "bluetooth",
                    "scroll", "type ", "press ", "notification", "screen").any { q.lowercase(Locale.ROOT).contains(it) }
            },
            systemGuidance = "You control an Android device through the declared action schema only. " +
                "Never claim an action succeeded unless a VERIFY step or observed UI confirms it. " +
                "Never bypass approval checkpoints or invent capabilities.",
        ),
        "general" to AgentProfile(
            id = "general",
            description = "General task planner",
            matches = { true },
            systemGuidance = "Use only supported actions and visible UI evidence. If the task cannot be performed safely, request the user.",
        ),
    )

    @Synchronized
    fun register(profile: AgentProfile) {
        require(profile.id.isNotBlank()) { "profile id must not be blank" }
        profiles[profile.id] = profile
    }

    @Synchronized
    fun resolve(query: String): AgentProfile =
        profiles.values.firstOrNull { it.id != "general" && it.matches(query) }
            ?: profiles.getValue("general")

    @Synchronized
    fun all(): List<AgentProfile> = profiles.values.toList()
}

/**
 * Lightweight trace-driven routing policy over locally stored task outcomes.
 * Successful engines are preferred; failures are penalized, and unseen engines
 * remain available after observed engines. The policy never changes an action plan.
 */
object TraceDrivenRouter {
    fun rankEngines(configured: List<EngineConfig>, traces: List<TaskLogEntity>): List<Int> {
        val available = configured.filter { it.isUsable }.map { it.index }.distinct()
        if (available.size < 2 || traces.isEmpty()) return available.sorted()

        val stats = traces.filter { it.engine in available }
            .groupBy { it.engine }
            .mapValues { (_, rows) ->
                val successes = rows.count { it.status == "DONE" }
                val failures = rows.count { it.status == "FAILED" || it.status == "CANCELLED" }
                val total = successes + failures
                // Laplace smoothing avoids a new engine getting a perfect score from one success.
                (successes + 1.0) / (total + 2.0)
            }
        return available.sortedWith(
            compareByDescending<Int> { stats[it] ?: 0.5 }
                .thenBy { it }
        )
    }
}

/** Local, privacy-preserving telemetry summary computed from the existing Room task trace. */
data class TraceSummary(
    val completed: Int,
    val failed: Int,
    val cancelled: Int,
    val enginesSeen: Int,
    val averageDurationMs: Long,
)

object TraceAnalyzer {
    fun summarize(traces: List<TaskLogEntity>): TraceSummary = TraceSummary(
        completed = traces.count { it.status == "DONE" },
        failed = traces.count { it.status == "FAILED" },
        cancelled = traces.count { it.status == "CANCELLED" },
        enginesSeen = traces.mapNotNull { it.engine.takeIf { id -> id > 0 } }.distinct().size,
        averageDurationMs = if (traces.isEmpty()) 0L else traces
            .map { (it.endedAt - it.startedAt).coerceAtLeast(0L) }
            .average().toLong(),
    )
}
