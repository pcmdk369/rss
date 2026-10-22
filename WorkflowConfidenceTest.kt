package com.pcmdk369.superioa

import com.pcmdk369.superioa.memory.ReuseDecision
import com.pcmdk369.superioa.memory.WorkflowConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowConfidenceTest {
    @Test fun newWorkflowNeedsConfirmation() {
        // one success: (1+1)/(1+2) = 0.667 -> below reuse threshold, above confirm threshold
        assertEquals(ReuseDecision.CONFIRM, WorkflowConfidence.decide(1, 0))
    }

    @Test fun repeatedSuccessIsReusedAutomatically() {
        assertEquals(ReuseDecision.REUSE, WorkflowConfidence.decide(2, 0))
        assertEquals(ReuseDecision.REUSE, WorkflowConfidence.decide(10, 2))
    }

    @Test fun mixedResultsNeedConfirmation() {
        assertEquals(ReuseDecision.CONFIRM, WorkflowConfidence.decide(2, 1))
    }

    @Test fun unreliableWorkflowIsDisabled() {
        assertEquals(ReuseDecision.DISABLED, WorkflowConfidence.decide(1, 4))
    }

    @Test fun scoreIsMonotonic() {
        assertTrue(WorkflowConfidence.score(5, 0) > WorkflowConfidence.score(5, 3))
    }

    @Test fun goalNormalization() {
        assertEquals("open settings turn on wi fi", WorkflowConfidence.normalizeGoal("  Open Settings, turn on Wi-Fi! "))
    }
}
