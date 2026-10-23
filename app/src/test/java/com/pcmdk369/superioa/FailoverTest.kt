package com.pcmdk369.superioa

import com.pcmdk369.superioa.llm.AllEnginesFailedException
import com.pcmdk369.superioa.llm.EngineCaller
import com.pcmdk369.superioa.llm.EngineConfig
import com.pcmdk369.superioa.llm.EngineError
import com.pcmdk369.superioa.llm.FailoverManager
import com.pcmdk369.superioa.llm.SecurityDenied
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FailoverTest {
    private fun eng(i: Int, on: Boolean = true, key: String = "key-$i-secret") =
        EngineConfig(i, key, "https://e$i.example.com/v1", "m$i", on, 10)

    private fun mgr(engines: List<EngineConfig>, retries: Int = 1, caller: EngineCaller) =
        FailoverManager(engines, caller, maxRetriesPerEngine = retries, delayFn = {})

    @Test fun triesEnginesInOrderAndReturnsFirstSuccess() = runBlocking {
        val order = mutableListOf<Int>()
        val m = mgr((1..5).map { eng(it) }, retries = 0) { e, _, _ ->
            order += e.index
            if (e.index < 3) throw EngineError.Retryable("HTTP 503") else "ok-${e.index}"
        }
        val r = m.complete("s", "u")
        assertEquals(listOf(1, 2, 3), order)
        assertEquals(3, r.engineIndex)
        assertEquals("ok-3", r.text)
        assertEquals(3, r.attempts.size)
        assertFalse(r.attempts[0].success)
        assertTrue(r.attempts[2].success)
    }

    @Test fun retriesAreBounded() {
        var calls = 0
        val m = mgr(listOf(eng(1)), retries = 2) { _, _, _ -> calls++; throw EngineError.Retryable("timeout") }
        assertThrows(AllEnginesFailedException::class.java) { runBlocking { m.complete("s", "u") } }
        assertEquals(3, calls) // 1 try + 2 retries
    }

    @Test fun configErrorMarksUnhealthyWithoutRetry() = runBlocking {
        val calls = mutableListOf<Int>()
        val m = mgr(listOf(eng(1), eng(2)), retries = 3) { e, _, _ ->
            calls += e.index
            if (e.index == 1) throw EngineError.Config("HTTP 401") else "ok"
        }
        val r = m.complete("s", "u")
        assertEquals(listOf(1, 2), calls)
        assertEquals(2, r.engineIndex)
        assertTrue(1 in m.unhealthyEngines)
        // second call skips the unhealthy engine entirely
        m.complete("s", "u")
        assertEquals(listOf(1, 2, 2), calls)
    }

    @Test fun disabledAndIncompleteEnginesAreSkipped() = runBlocking {
        val seen = mutableListOf<Int>()
        val engines = listOf(eng(1, on = false), eng(2, key = ""), eng(3))
        val r = mgr(engines) { e, _, _ -> seen += e.index; "ok" }.complete("s", "u")
        assertEquals(listOf(3), seen)
        assertEquals(3, r.engineIndex)
    }

    @Test fun noUsableEngineGivesActionableError() {
        val m = mgr(listOf(eng(1, on = false))) { _, _, _ -> "never" }
        val ex = assertThrows(AllEnginesFailedException::class.java) { runBlocking { m.complete("s", "u") } }
        assertTrue(ex.noneUsable)
        assertTrue(ex.message!!.contains("Settings"))
    }

    @Test fun cancellationIsNotFailedOver() {
        val calls = mutableListOf<Int>()
        val m = mgr(listOf(eng(1), eng(2))) { e, _, _ -> calls += e.index; throw CancellationException("user stop") }
        assertThrows(CancellationException::class.java) { runBlocking { m.complete("s", "u") } }
        assertEquals(listOf(1), calls)
    }

    @Test fun securityDenialIsNotFailedOver() {
        val calls = mutableListOf<Int>()
        val m = mgr(listOf(eng(1), eng(2))) { e, _, _ -> calls += e.index; throw SecurityDenied("no") }
        assertThrows(SecurityDenied::class.java) { runBlocking { m.complete("s", "u") } }
        assertEquals(listOf(1), calls)
    }

    @Test fun errorsNeverLeakApiKeys() {
        val m = mgr(listOf(eng(1, key = "sk-supersecretvalue123")), retries = 0) { e, _, _ ->
            throw EngineError.Retryable("failed with key ${e.apiKey}")
        }
        val ex = assertThrows(AllEnginesFailedException::class.java) { runBlocking { m.complete("s", "u") } }
        assertFalse(ex.message!!.contains("supersecretvalue123"))
        assertFalse(ex.attempts.any { it.error?.contains("supersecretvalue123") == true })
    }
}
