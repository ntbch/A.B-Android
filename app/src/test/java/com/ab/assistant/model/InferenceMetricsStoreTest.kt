package com.ab.assistant.model

import org.junit.Assert.assertEquals
import org.junit.Test

class InferenceMetricsStoreTest {
    @Test
    fun publishesLatestMetricsAndSupportsRemoval() {
        val store = InferenceMetricsStore()
        val observed = mutableListOf<InferenceMetrics?>()
        val remove = store.observe(observed::add)
        val metrics = InferenceMetrics(
            requestId = "req-1",
            backendRequested = "OPENCL,VULKAN,CPU",
            backendActual = "CPU",
            fallbackReason = "OpenCL load failed",
            coldStart = true,
            modelLoadMs = 10,
            promptTokens = null,
            prefillMs = null,
            ttftMs = null,
            generatedTokens = null,
            decodeTokensPerSecond = null,
            generationMs = 20,
            totalMs = 30,
        )

        store.publish(metrics)
        remove()
        store.publish(metrics.copy(requestId = "req-2"))

        assertEquals(2, observed.size)
        assertEquals(null, observed[0])
        assertEquals(metrics, observed[1])
        assertEquals("req-2", store.latest()?.requestId)
    }
}
