package com.ab.assistant.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceMetricsTest {
    @Test
    fun serializesMeasuredAndUnavailableFields() {
        val metrics = InferenceMetrics(
            requestId = "req-1",
            backendRequested = "OPENCL,VULKAN,CPU",
            backendActual = "CPU",
            fallbackReason = "OpenCL and Vulkan load failed; CPU fallback selected",
            coldStart = true,
            modelLoadMs = 1234,
            promptTokens = null,
            prefillMs = null,
            ttftMs = null,
            generatedTokens = null,
            decodeTokensPerSecond = null,
            generationMs = 0,
            totalMs = 1234,
        )

        assertEquals(
            "{\"requestId\":\"req-1\",\"backendRequested\":\"OPENCL,VULKAN,CPU\",\"backendActual\":\"CPU\",\"fallbackReason\":\"OpenCL and Vulkan load failed; CPU fallback selected\",\"coldStart\":true,\"modelLoadMs\":1234,\"promptTokens\":null,\"prefillMs\":null,\"ttftMs\":null,\"generatedTokens\":null,\"decodeTokensPerSecond\":null,\"generationMs\":0,\"totalMs\":1234,\"promptCharacters\":null,\"exposedToolCount\":null,\"modelDecisionIndex\":null,\"promptCacheHit\":null,\"cachedPromptTokens\":null}",
            metrics.toJson(),
        )
    }

    @Test
    fun debugTextKeepsUnavailableMeasurementsExplicitlyNull() {
        val text = InferenceMetrics(
            requestId = "req-1",
            backendRequested = "OPENCL,CPU",
            backendActual = "OPENCL",
            fallbackReason = null,
            coldStart = true,
            modelLoadMs = 1234,
            promptTokens = null,
            prefillMs = null,
            ttftMs = null,
            generatedTokens = null,
            decodeTokensPerSecond = null,
            generationMs = 0,
            totalMs = 1234,
        ).toDebugText()

        assertTrue(text.contains("Backend: OPENCL"))
        assertTrue(text.contains("Tokens/prefill/TTFT/decode: null/null/null/null/null"))
    }
}
