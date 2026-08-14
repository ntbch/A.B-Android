package com.ab.assistant.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MnnRuntimeStateTest {
    @Test
    fun publishesOrderedRuntimeStatesAndContext() {
        val store = MnnRuntimeStateStore()
        val snapshots = mutableListOf<MnnRuntimeSnapshot>()
        store.observe(snapshots::add)

        store.transition(MnnRuntimeState.LOADING)
        store.transition(MnnRuntimeState.READY, backend = "CPU")
        store.transition(MnnRuntimeState.GENERATING, backend = "CPU")
        store.transition(MnnRuntimeState.READY, backend = "CPU")

        assertEquals(
            listOf(MnnRuntimeState.UNINITIALIZED, MnnRuntimeState.LOADING, MnnRuntimeState.READY, MnnRuntimeState.GENERATING, MnnRuntimeState.READY),
            snapshots.map(MnnRuntimeSnapshot::state),
        )
        assertEquals("CPU", store.snapshot().backend)
    }
}
