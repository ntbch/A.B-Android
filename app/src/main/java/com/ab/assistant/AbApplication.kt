package com.ab.assistant

import android.app.Application
import com.ab.assistant.agent.AgentCore
import com.ab.assistant.model.MnnModelRuntime
import com.ab.assistant.tools.ToolRegistry

class AbApplication : Application() {
    val nativeBridge by lazy { NativeBridge() }
    val modelRuntime by lazy { MnnModelRuntime(nativeBridge) }
    val toolRegistry by lazy { ToolRegistry(applicationContext) }
    val agentCore by lazy { AgentCore(modelRuntime, toolRegistry) }

    private val modelLock = Any()
    private val loadCallbacks = mutableListOf<(String) -> Unit>()
    private var loading = false
    private var modelBackend: String? = null

    fun loadModel(onComplete: (String) -> Unit) {
        synchronized(modelLock) {
            modelBackend?.let(onComplete)
            if (modelBackend != null) return
            loadCallbacks += onComplete
            if (loading) return
            loading = true
        }
        modelRuntime.load(getExternalFilesDir(null) ?: filesDir) { result ->
            val callbacks = synchronized(modelLock) {
                loading = false
                if (result == "OPENCL" || result == "CPU") modelBackend = result
                loadCallbacks.toList().also { loadCallbacks.clear() }
            }
            callbacks.forEach { it(result) }
        }
    }
}
