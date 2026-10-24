package com.pcmdk369.superioa.llm

import com.pcmdk369.superioa.security.Redactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Adapter for providers that expose an OpenAI-style POST {baseUrl}/chat/completions.
 * Providers with a different wire format need their own EngineCaller implementation.
 */
class OpenAiCompatibleCaller(private val http: OkHttpClient = OkHttpClient()) : EngineCaller {

    override suspend fun call(engine: EngineConfig, system: String, user: String): String {
        var base = engine.baseUrl.trim().trimEnd('/')
        if (base.endsWith("/chat/completions", ignoreCase = true)) {
            base = base.substringBeforeLast("/chat/completions").trimEnd('/')
        }
        if (!base.startsWith("https://", ignoreCase = true)) {
            throw EngineError.Config("Engine ${engine.index}: base URL must start with https://")
        }
        val url = try {
            "$base/chat/completions".toHttpUrl()
        } catch (e: IllegalArgumentException) {
            throw EngineError.Config("Engine ${engine.index}: base URL is not valid")
        }
        val body = buildJsonObject {
            put("model", engine.modelId)
            put("temperature", 0)
            put("stream", false)
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", user) })
            }
        }.toString()

        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer " + engine.apiKey)
            .header("Accept", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val client = http.newBuilder()
            .callTimeout(engine.timeoutSec.coerceIn(5, 120).toLong(), TimeUnit.SECONDS)
            .build()

        return withContext(Dispatchers.IO) {
            val resp = try {
                client.newCall(req).await()
            } catch (e: IOException) {
                throw EngineError.Retryable("network error (${e.javaClass.simpleName})", e)
            }
            resp.use { r ->
                val responseText = r.body?.string().orEmpty()
                when {
                    r.code in 200..299 -> parseContent(responseText)
                    r.code == 408 || r.code == 429 || r.code >= 500 ->
                        throw EngineError.Retryable(errorMessage(r.code, responseText, engine))
                    else ->
                        throw EngineError.Config(errorMessage(r.code, responseText, engine))
                }
            }
        }
    }

    private fun errorMessage(code: Int, body: String, engine: EngineConfig): String {
        // Keep provider diagnostics useful without leaking a key or an echoed credential.
        val safeBody = Redactor.redact(body, listOf(engine.apiKey))
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .take(220)
        val detail = safeBody.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
        return "HTTP $code at ${engine.baseUrl.trimEnd('/')}/chat/completions$detail"
    }

    private fun parseContent(text: String): String {
        val content = try {
            Json.parseToJsonElement(text).jsonObject["choices"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            null
        }
        return content ?: throw EngineError.Retryable("malformed provider response")
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }
}
