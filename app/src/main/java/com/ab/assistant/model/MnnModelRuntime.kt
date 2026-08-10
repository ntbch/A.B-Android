package com.ab.assistant.model

import android.util.Log
import com.ab.assistant.NativeBridge
import com.ab.assistant.agent.AgentModel
import com.ab.assistant.agent.InstrumentedAgentModel
import com.ab.assistant.agent.ModelRequestMetadata
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MnnModelRuntime(
    private val nativeBridge: NativeBridge,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val timeoutExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(),
    private val metricsSink: (InferenceMetrics) -> Unit = { metrics ->
        Log.i(TAG, metrics.toJson())
    },
) : Closeable, InstrumentedAgentModel {
    private val nativeGenerationRunning = AtomicBoolean(false)
    private val firstGeneration = AtomicBoolean(true)
    @Volatile private var backendActual: String? = null
    @Volatile private var lastModelLoadMs: Long? = null
    @Volatile private var activeBackendRequested: String = BACKEND_REQUEST_ORDER

    fun load(filesDir: File, onComplete: (String) -> Unit) = loadInternal(
        filesDir = filesDir,
        backendRequested = BACKEND_REQUEST_ORDER,
        nativeLoad = { configPath, cachePath -> nativeBridge.loadModel(configPath, cachePath) },
        onComplete = onComplete,
    )

    fun loadBackend(filesDir: File, backend: String, onComplete: (String) -> Unit) {
        val normalized = backend.lowercase(Locale.ROOT)
        if (normalized !in SUPPORTED_BACKENDS) {
            onComplete("ERROR: Unsupported MNN backend request.")
            return
        }
        loadInternal(
            filesDir = filesDir,
            backendRequested = normalized.uppercase(Locale.ROOT),
            nativeLoad = { configPath, cachePath ->
                nativeBridge.loadModelWithBackend(configPath, cachePath, normalized)
            },
            onComplete = onComplete,
        )
    }

    private fun loadInternal(
        filesDir: File,
        backendRequested: String,
        nativeLoad: (String, String) -> String,
        onComplete: (String) -> Unit,
    ) {
        val requestId = UUID.randomUUID().toString()
        val totalStartedAt = System.nanoTime()
        executor.execute {
            activeBackendRequested = backendRequested
            val modelDir = ModelFiles.directory(filesDir)
            if (!modelDir.exists() && !modelDir.mkdirs()) {
                onComplete("ERROR: Cannot create model directory.")
                return@execute
            }
            val missingFiles = ModelFiles.missingFiles(filesDir)
            if (missingFiles.isNotEmpty()) {
                onComplete("Model missing: ${missingFiles.joinToString()}")
                return@execute
            }

            val cacheKey = backendRequested.lowercase(Locale.ROOT)
                .replace("[^a-z0-9_-]".toRegex(), "_")
            val cacheDir = File(filesDir, "mnn-cache-$cacheKey")
            if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                onComplete("ERROR: Cannot create MNN cache directory.")
                return@execute
            }

            val loadStartedAt = System.nanoTime()
            val result = nativeLoad(ModelFiles.configFile(filesDir).absolutePath, cacheDir.absolutePath)
            val loadMs = elapsedMs(loadStartedAt)
            backendActual = result.takeIf { it == "OPENCL" || it == "VULKAN" || it == "CPU" }
            lastModelLoadMs = loadMs
            firstGeneration.set(true)
            record(
                InferenceMetrics(
                    requestId = requestId,
                    backendRequested = backendRequested,
                    backendActual = backendActual,
                    fallbackReason = fallbackReason(result, backendRequested),
                    coldStart = true,
                    modelLoadMs = loadMs,
                    promptTokens = null,
                    prefillMs = null,
                    ttftMs = null,
                    generatedTokens = null,
                    decodeTokensPerSecond = null,
                    generationMs = 0,
                    totalMs = elapsedMs(totalStartedAt),
                ),
            )
            onComplete(result)
        }
    }

    override fun generate(prompt: String, onComplete: (String) -> Unit) {
        generateWithMetadata(
            prompt,
            ModelRequestMetadata(
                promptCharacters = prompt.length,
                exposedToolCount = null,
                modelDecisionIndex = null,
            ),
            onComplete,
        )
    }

    override fun generateWithMetadata(
        prompt: String,
        metadata: ModelRequestMetadata,
        onComplete: (String) -> Unit,
    ) {
        if (!nativeGenerationRunning.compareAndSet(false, true)) {
            onComplete("ERROR: The local model is still finishing the previous request.")
            return
        }
        val completed = AtomicBoolean(false)
        val requestId = UUID.randomUUID().toString()
        val totalStartedAt = System.nanoTime()
        try {
            val timeout = timeoutExecutor.schedule({
                if (completed.compareAndSet(false, true)) {
                    recordGeneration(requestId, elapsedMs(totalStartedAt), elapsedMs(totalStartedAt), metadata)
                    onComplete("ERROR: Local generation timed out. Please try a shorter request.")
                }
            }, GENERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            executor.execute {
                val generationStartedAt = System.nanoTime()
                try {
                    val nativePayload = nativeBridge.generateWithMetrics(prompt, MODEL_MAX_NEW_TOKENS)
                    val nativeResult = NativeGenerationResult.parse(nativePayload)
                    val result = nativeResult?.output ?: nativePayload
                    if (completed.compareAndSet(false, true)) {
                        recordGeneration(
                            requestId = requestId,
                            generationMs = elapsedMs(generationStartedAt),
                            totalMs = elapsedMs(totalStartedAt),
                            metadata = metadata,
                            nativeResult = nativeResult,
                        )
                        onComplete(result)
                    }
                } finally {
                    nativeGenerationRunning.set(false)
                    timeout.cancel(false)
                }
            }
        } catch (_: RuntimeException) {
            nativeGenerationRunning.set(false)
            onComplete("ERROR: Local model runtime is unavailable.")
        }
    }

    override fun close() {
        executor.execute(nativeBridge::unloadModel)
        executor.shutdown()
        timeoutExecutor.shutdownNow()
    }

    private fun recordGeneration(
        requestId: String,
        generationMs: Long,
        totalMs: Long,
        metadata: ModelRequestMetadata,
        nativeResult: NativeGenerationResult? = null,
    ) {
        val coldStart = backendActual != null && firstGeneration.compareAndSet(true, false)
        record(
            InferenceMetrics(
                requestId = requestId,
                backendRequested = activeBackendRequested,
                backendActual = backendActual,
                fallbackReason = null,
                coldStart = coldStart,
                modelLoadMs = if (coldStart) lastModelLoadMs else null,
                promptTokens = nativeResult?.promptTokens,
                prefillMs = nativeResult?.prefillMs,
                ttftMs = nativeResult?.ttftMs,
                generatedTokens = nativeResult?.generatedTokens,
                decodeTokensPerSecond = nativeResult?.decodeTokensPerSecond,
                generationMs = generationMs,
                totalMs = totalMs,
                promptCharacters = metadata.promptCharacters,
                exposedToolCount = metadata.exposedToolCount,
                modelDecisionIndex = metadata.modelDecisionIndex,
                promptCacheHit = nativeResult?.promptCacheHit,
                cachedPromptTokens = nativeResult?.cachedPromptTokens,
            ),
        )
    }

    private fun record(metrics: InferenceMetrics) {
        try {
            metricsSink(metrics)
        } catch (_: RuntimeException) {
            Log.w(TAG, "Metrics sink failed")
        }
    }

    private fun fallbackReason(result: String, requested: String): String? {
        if (result.startsWith("ERROR:")) return "Requested MNN backend load failed"
        if (requested != BACKEND_REQUEST_ORDER) return null
        return when (result) {
            "OPENCL" -> "CPU load failed; OpenCL selected"
            "VULKAN" -> "CPU and OpenCL load failed; Vulkan selected"
            else -> null
        }
    }

    private fun elapsedMs(startedAt: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt).coerceAtLeast(0)

    companion object {
        private const val TAG = "MnnModelRuntime"
        private const val BACKEND_REQUEST_ORDER = "CPU,OPENCL,VULKAN"
        private const val MODEL_MAX_NEW_TOKENS = 32
        private const val GENERATION_TIMEOUT_SECONDS = 35L
        private val SUPPORTED_BACKENDS = setOf("opencl", "vulkan", "cpu")
    }
}
