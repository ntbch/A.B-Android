package com.ab.assistant.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskSessionStoreTest {
    @Test
    fun lifecycleIsSingleOwnerAndObservable() {
        val store = TaskSessionStore()
        val states = mutableListOf<TaskState>()
        val removeObserver = store.observe { states += it.state }

        val taskId = store.begin("test") ?: error("task was not started")
        assertEquals(null, store.begin("second"))
        assertTrue(store.transition(taskId, TaskState.WAITING_FOR_MODEL))
        assertTrue(store.complete(taskId, "done"))
        assertEquals(TaskState.COMPLETED, store.snapshot().state)
        assertEquals(
            listOf(TaskState.IDLE, TaskState.ROUTING, TaskState.WAITING_FOR_MODEL, TaskState.COMPLETED),
            states,
        )

        removeObserver()
    }

    @Test
    fun cancellationPublishesStoppingThenCancelledAndAllowsNextTask() {
        val store = TaskSessionStore()
        val states = mutableListOf<TaskState>()
        store.observe { states += it.state }
        assertNotNull(store.begin("cancel me"))

        assertTrue(store.cancel())
        assertFalse(store.cancel())
        assertEquals(TaskState.CANCELLED, store.snapshot().state)
        assertTrue(states.contains(TaskState.STOPPING))
        assertTrue(states.contains(TaskState.CANCELLED))
        assertNotNull(store.begin("next"))
    }

    @Test
    fun storesBoundedVerifiedObservationsForTheActiveTask() {
        val store = TaskSessionStore()
        val taskId = store.begin("multi-step") ?: error("task was not started")

        assertTrue(store.setRoute(taskId, "AGENT"))
        repeat(6) { index ->
            assertTrue(
                store.recordObservation(
                    taskId,
                    TaskObservation(
                        step = index + 1,
                        action = "tool_$index",
                        summary = "result_$index",
                        ok = true,
                        verified = true,
                        code = "OK",
                    ),
                ),
            )
        }

        val snapshot = store.snapshot()
        assertEquals("AGENT", snapshot.route)
        assertEquals(6, snapshot.decisionStep)
        assertEquals(5, snapshot.observations.size)
        assertEquals(2, snapshot.observations.first().step)
    }
}
