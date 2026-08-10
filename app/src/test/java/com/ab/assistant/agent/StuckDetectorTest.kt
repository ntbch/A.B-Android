package com.ab.assistant.agent

import com.ab.assistant.tools.ToolCommand
import com.ab.assistant.tools.ToolExecutionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StuckDetectorTest {
    @Test
    fun repeatedActionAndErrorTerminateOnSecondOccurrence() {
        val detector = StuckDetector(maxSteps = 5, maxWallMs = 1_000_000)

        assertTrue(!detector.recordAction(ToolCommand.ReadDeviceState).isStuck)
        assertEquals(StuckReason.REPEATED_ACTION, detector.recordAction(ToolCommand.ReadDeviceState).reason)
        assertTrue(!detector.recordResult(ToolExecutionResult("network failed", ok = false)).isStuck)
        assertEquals(
            StuckReason.REPEATED_ERROR,
            detector.recordResult(ToolExecutionResult("network failed", ok = false)).reason,
        )
    }

    @Test
    fun malformedOutputStepBudgetAndDeadlineAreBounded() {
        var now = 0L
        val detector = StuckDetector(maxSteps = 2, maxWallMs = 100, clockMs = { now })

        assertTrue(!detector.recordModelOutput("{broken", null).isStuck)
        assertEquals(StuckReason.MALFORMED_MODEL_OUTPUT, detector.recordModelOutput("{broken", null).reason)
        assertEquals(StuckReason.STEP_BUDGET, detector.stepBudgetDecision(2).reason)
        now = 101
        assertEquals(StuckReason.DEADLINE, detector.deadlineDecision().reason)
    }
}
