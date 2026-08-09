package com.ab.assistant.model

import com.ab.assistant.NativeBridge
import com.ab.assistant.agent.AgentModel
import java.io.Closeable
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MnnModelRuntime(
    private val nativeBridge: NativeBridge,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val timeoutExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(),
) : Closeable, AgentModel {
    private val nativeGenerationRunning = AtomicBoolean(false)

    fun load(filesDir: File, onComplete: (String) -> Unit) {
        executor.execute {
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

            val cacheDir = File(filesDir, "mnn-cache")
            if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                onComplete("ERROR: Cannot create MNN cache directory.")
                return@execute
            }

            onComplete(nativeBridge.loadModel(ModelFiles.configFile(filesDir).absolutePath, cacheDir.absolutePath))
        }
    }

    override fun generate(prompt: String, onComplete: (String) -> Unit) {
        if (!nativeGenerationRunning.compareAndSet(false, true)) {
            onComplete("ERROR: The local model is still finishing the previous request.")
            return
        }
        val completed = AtomicBoolean(false)
        try {
            val timeout = timeoutExecutor.schedule({
                if (completed.compareAndSet(false, true)) {
                    onComplete("ERROR: Local generation timed out. Please try a shorter request.")
                }
            }, GENERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            executor.execute {
                try {
                    val result = nativeBridge.generate(prompt, 64)
                    if (completed.compareAndSet(false, true)) onComplete(result)
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

    companion object {
        private const val GENERATION_TIMEOUT_SECONDS = 35L
    }
}
