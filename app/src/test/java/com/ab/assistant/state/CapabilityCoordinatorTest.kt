package com.ab.assistant.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityCoordinatorTest {
    @Test
    fun startsDisabledAndPublishesOnlyRealStateChanges() {
        val coordinator = CapabilityCoordinator()
        val snapshots = mutableListOf<CapabilitySnapshot>()
        coordinator.observe(snapshots::add)

        assertEquals(CapabilityState.DISABLED, coordinator.state(Capability.MODEL))
        assertTrue(coordinator.set(Capability.MODEL, CapabilityState.CONNECTING))
        assertFalse(coordinator.set(Capability.MODEL, CapabilityState.CONNECTING))
        assertTrue(coordinator.set(Capability.MODEL, CapabilityState.READY))
        assertTrue(coordinator.isReady(Capability.MODEL))
        assertEquals(3, snapshots.size)
        assertTrue(coordinator.describe("OPENCL").contains("Mô hình: OPENCL"))
    }
}
