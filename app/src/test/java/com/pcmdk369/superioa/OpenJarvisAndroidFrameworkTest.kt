package com.pcmdk369.superioa

import com.pcmdk369.superioa.framework.AgentProfileRegistry
import com.pcmdk369.superioa.framework.EngineAdapterRegistry
import com.pcmdk369.superioa.framework.ModelCatalog
import com.pcmdk369.superioa.framework.TraceAnalyzer
import com.pcmdk369.superioa.framework.TraceDrivenRouter
import com.pcmdk369.superioa.llm.EngineConfig
import com.pcmdk369.superioa.memory.TaskLogEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenJarvisAndroidFrameworkTest {
    private fun engine(index: Int, url: String = "https://api.example.com/v1") = EngineConfig(
        index = index,
        apiKey = "test-key-$index",
        baseUrl = url,
        modelId = "test-model-$index",
        enabled = true,
    )

    private fun trace(engine: Int, status: String, started: Long, ended: Long) = TaskLogEntity(
        goal = "safe test task",
        status = status,
        engine = engine,
        summary = "test",
        startedAt = started,
        endedAt = ended,
    )

    @Test fun modelCatalogOnlyIncludesConfiguredEnabledModels() {
        val catalog = ModelCatalog.fromConfiguredEngines(
            listOf(
                engine(1, "http://127.0.0.1:11434/v1"),
                engine(2).copy(enabled = false),
                engine(3).copy(modelId = ""),
            )
        )
        assertEquals(1, catalog.size)
        assertEquals("test-model-1", catalog.single().modelId)
        assertTrue(catalog.single().loopbackEndpoint)
    }

    @Test fun traceRoutingPrefersEnginesWithMoreSuccessfulOutcomes() {
        val configured = listOf(engine(1), engine(2))
        val traces = listOf(
            trace(1, "FAILED", 0, 100),
            trace(1, "FAILED", 100, 200),
            trace(1, "FAILED", 200, 300),
            trace(2, "DONE", 300, 400),
            trace(2, "DONE", 400, 500),
            trace(2, "DONE", 500, 600),
        )
        assertEquals(listOf(2, 1), TraceDrivenRouter.rankEngines(configured, traces))
    }

    @Test fun traceRoutingKeepsDeterministicDefaultWithoutHistory() {
        assertEquals(listOf(1, 2), TraceDrivenRouter.rankEngines(listOf(engine(2), engine(1)), emptyList()))
    }

    @Test fun traceAnalyzerSummarizesLocalTaskHistory() {
        val summary = TraceAnalyzer.summarize(
            listOf(
                trace(1, "DONE", 100, 400),
                trace(2, "FAILED", 500, 900),
                trace(2, "CANCELLED", 1000, 1100),
            )
        )
        assertEquals(1, summary.completed)
        assertEquals(1, summary.failed)
        assertEquals(1, summary.cancelled)
        assertEquals(2, summary.enginesSeen)
        assertEquals(266L, summary.averageDurationMs)
    }

    @Test fun defaultEngineAdapterIsRegistered() {
        assertTrue("openai-compatible" in EngineAdapterRegistry.registeredProtocols())
        assertFalse(EngineAdapterRegistry.registeredProtocols().isEmpty())
    }

    @Test fun deviceTasksResolveToDeviceProfile() {
        assertEquals("android-device", AgentProfileRegistry.resolve("Open settings and turn on Wi-Fi").id)
        assertEquals("general", AgentProfileRegistry.resolve("Explain a science concept").id)
    }
}
