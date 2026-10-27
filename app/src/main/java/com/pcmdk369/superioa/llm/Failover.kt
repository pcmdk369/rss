package com.pcmdk369.superioa.llm

import com.pcmdk369.superioa.security.Redactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** One of the five sequential engine slots. Env-style names: LLM_ENGINE_<index>_API_KEY / _BASE_URL / _MODEL_ID. */
data class EngineConfig(
    val index: Int,
    val apiKey: String = "",
    val baseUrl: String = "",
    val modelId: String = "",
    val enabled: Boolean = false,
    val timeoutSec: Int = 30,
) {
    val isUsable: Boolean
        get() = enabled && apiKey.isNotBlank() && baseUrl.isNotBlank() && modelId.isNotBlank()
}

sealed class EngineError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Network error, timeout, 429, 5xx: try again / next engine. */
    class Retryable(message: String, cause: Throwable? = null) : EngineError(message, cause)

    /** Bad key, bad model ID, malformed request: mark engine unhealthy, do not retry. */
    class Config(message: String) : EngineError(message)
}

/** User/security denial: must never trigger failover. */
class SecurityDenied(message: String) : Exception(message)

data class Attempt(val engine: Int, val success: Boolean, val error: String?, val latencyMs: Long)

data class LlmResult(val text: String, val engineIndex: Int, val attempts: List<Attempt>)

class AllEnginesFailedException(val attempts: List<Attempt>, val noneUsable: Boolean) :
    Exception(
        if (noneUsable) "No LLM engine is enabled and fully configured. Open Settings > Engines and fill in API key, base URL and model ID."
        else "All engines failed. " + attempts.filter { !it.success }
            .joinToString("; ") { "#${it.engine}: ${it.error}" }
    )

fun interface EngineCaller {
    suspend fun call(engine: EngineConfig, system: String, user: String): String
}

/**
 * Sequential failover over up to five engines (not parallel).
 * Retryable errors: bounded retries with backoff, then next engine.
 * Config errors: engine marked unhealthy, next engine.
 * Cancellation and SecurityDenied are re-thrown immediately (no failover).
 */
class FailoverManager(
    private val engines: List<EngineConfig>,
    private val caller: EngineCaller,
    private val maxRetriesPerEngine: Int = 1,
    private val backoffMs: Long = 500,
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val enginePriority: List<Int> = emptyList(),
) {
    private val unhealthy = mutableSetOf<Int>()
    val unhealthyEngines: Set<Int> get() = unhealthy.toSet()

    suspend fun complete(system: String, user: String): LlmResult {
        val secrets = engines.map { it.apiKey }
        val attempts = mutableListOf<Attempt>()
        val priority = enginePriority.withIndex().associate { it.value to it.index }
        val candidates = engines.filter { it.isUsable && it.index !in unhealthy }
            .sortedWith(compareBy<EngineConfig> { priority[it.index] ?: Int.MAX_VALUE }.thenBy { it.index })

        for (engine in candidates) {
            var tries = 0
            while (true) {
                val t0 = clock()
                try {
                    val text = caller.call(engine, system, user)
                    attempts += Attempt(engine.index, true, null, clock() - t0)
                    return LlmResult(text, engine.index, attempts)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SecurityDenied) {
                    throw e
                } catch (e: EngineError.Config) {
                    attempts += Attempt(engine.index, false, "config: " + safe(e, secrets), clock() - t0)
                    unhealthy += engine.index
                    break
                } catch (e: Exception) {
                    attempts += Attempt(engine.index, false, safe(e, secrets), clock() - t0)
                    if (tries < maxRetriesPerEngine) {
                        tries++
                        delayFn(backoffMs * (1L shl (tries - 1)))
                        continue
                    }
                    break
                }
            }
        }
        throw AllEnginesFailedException(attempts, candidates.isEmpty())
    }

    private fun safe(e: Exception, secrets: List<String>): String =
        Redactor.redact(e.message ?: e.javaClass.simpleName, secrets).take(200)
}
