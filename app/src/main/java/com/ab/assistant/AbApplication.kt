package com.ab.assistant

import android.Manifest
import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.ContactsContract
import android.util.Log
import com.ab.assistant.accessibility.AbAccessibilityService
import com.ab.assistant.agent.AgentCore
import com.ab.assistant.agent.PipelineRouter
import com.ab.assistant.agent.ProceduralSkillLearner
import com.ab.assistant.agent.SkillEngine
import com.ab.assistant.model.MnnModelRuntime
import com.ab.assistant.model.AbModelRelease
import com.ab.assistant.model.ModelFiles
import com.ab.assistant.model.ModelPackageManager
import com.ab.assistant.model.ModelPackageState
import com.ab.assistant.model.InferenceMetricsStore
import com.ab.assistant.notifications.AbNotificationListenerService
import com.ab.assistant.state.Capability
import com.ab.assistant.state.CapabilityCoordinator
import com.ab.assistant.state.CapabilityState
import com.ab.assistant.state.TaskSessionStore
import com.ab.assistant.tools.ToolRegistry
import com.ab.assistant.voice.AndroidSpeechToTextPort
import com.ab.assistant.voice.AndroidTextToSpeechPort
import com.ab.assistant.voice.VoiceSessionCoordinator
import com.ab.assistant.voice.AbVoiceInteractionService

class AbApplication : Application() {
    val nativeBridge by lazy { NativeBridge() }
    val inferenceMetricsStore = InferenceMetricsStore()
    val modelPackageManager by lazy {
        ModelPackageManager(listOfNotNull(getExternalFilesDir(null), filesDir).distinct())
    }
    val modelRuntime by lazy {
        MnnModelRuntime(
            nativeBridge = nativeBridge,
            metricsSink = { metrics ->
                inferenceMetricsStore.publish(metrics)
                Log.i("MnnModelRuntime", metrics.toJson())
            },
            packageVerifier = { base ->
                val status = modelPackageManager.inspect(AbModelRelease.manifest)
                if (status.state == ModelPackageState.READY && status.directory == ModelFiles.directory(base)) {
                    null
                } else {
                    status.reason ?: "Untrusted or incomplete model package."
                }
            },
        )
    }
    val taskSessionStore = TaskSessionStore()
    val capabilityCoordinator = CapabilityCoordinator()
    val skillEngine = SkillEngine()
    val proceduralSkillLearner = ProceduralSkillLearner()
    val toolRegistry by lazy { ToolRegistry(applicationContext, capabilityCoordinator = capabilityCoordinator) }
    val pipelineRouter by lazy { PipelineRouter(skillEngine = skillEngine) }
    val agentCore by lazy {
        AgentCore(
            modelRuntime,
            toolRegistry,
            taskSessionStore = taskSessionStore,
            pipelineRouter = pipelineRouter,
            skillEngine = skillEngine,
            proceduralSkillLearner = proceduralSkillLearner,
        )
    }
    val voiceCoordinator by lazy {
        capabilityCoordinator.set(Capability.VOICE, CapabilityState.CONNECTING)
        val speechToText = AndroidSpeechToTextPort(this)
        val textToSpeech = AndroidTextToSpeechPort(this) { ttsReady ->
            capabilityCoordinator.set(
                Capability.VOICE,
                if (ttsReady && speechToText.isAvailable) CapabilityState.READY else CapabilityState.DEGRADED,
            )
        }
        if (!speechToText.isAvailable) {
            capabilityCoordinator.set(Capability.VOICE, CapabilityState.DEGRADED)
        }
        VoiceSessionCoordinator(
            speechToText = speechToText,
            textToSpeech = textToSpeech,
            runAgent = { request, callback -> agentCore.run(request, callback) },
            cancelAgent = { agentCore.cancel() },
            executeApprovedAgent = { command, callback -> agentCore.executeApproved(command, callback) },
            executeConfirmedAgent = { command, callback -> agentCore.executeConfirmed(command, callback) },
        )
    }

    private val modelLock = Any()
    private val loadCallbacks = mutableListOf<(String) -> Unit>()
    private var loading = false
    private var modelBackend: String? = null

    override fun onCreate() {
        super.onCreate()
        refreshCapabilities()
    }

    fun loadModel(onComplete: (String) -> Unit) {
        var existingBackend: String? = null
        var shouldStartLoading = false
        synchronized(modelLock) {
            existingBackend = modelBackend
            if (existingBackend == null) {
                loadCallbacks += onComplete
                if (!loading) {
                    loading = true
                    shouldStartLoading = true
                }
            }
        }
        existingBackend?.let {
            capabilityCoordinator.set(Capability.MODEL, CapabilityState.READY)
            onComplete(it)
            return
        }
        if (!shouldStartLoading) return
        capabilityCoordinator.set(Capability.MODEL, CapabilityState.CONNECTING)
        modelRuntime.load(getExternalFilesDir(null) ?: filesDir, ::completeModelLoad)
    }

    fun loadModelBackend(backend: String, onComplete: (String) -> Unit) {
        val normalized = backend.uppercase(java.util.Locale.ROOT)
        if (normalized !in setOf("OPENCL", "VULKAN", "CPU")) {
            onComplete("ERROR: Unsupported MNN backend request.")
            return
        }
        val shouldStartLoading: Boolean
        synchronized(modelLock) {
            if (loading) {
                onComplete("ERROR: Another model load is already running.")
                return
            }
            loading = true
            shouldStartLoading = true
            loadCallbacks += onComplete
        }
        if (!shouldStartLoading) return
        capabilityCoordinator.set(Capability.MODEL, CapabilityState.CONNECTING)
        modelRuntime.loadBackend(getExternalFilesDir(null) ?: filesDir, normalized, ::completeModelLoad)
    }

    private fun completeModelLoad(result: String) {
        val callbacks = synchronized(modelLock) {
            loading = false
            if (result == "OPENCL" || result == "VULKAN" || result == "CPU") modelBackend = result
            loadCallbacks.toList().also { loadCallbacks.clear() }
        }
        capabilityCoordinator.set(
            Capability.MODEL,
            if (result == "OPENCL" || result == "VULKAN" || result == "CPU") CapabilityState.READY else CapabilityState.DEGRADED,
        )
        callbacks.forEach { it(result) }
    }

    fun refreshCapabilities() {
        capabilityCoordinator.set(
            Capability.NETWORK,
            if (isNetworkReady()) CapabilityState.READY else CapabilityState.DEGRADED,
        )
        capabilityCoordinator.set(
            Capability.NOTIFICATIONS,
            when {
                !AbNotificationListenerService.isAccessEnabled(this) -> CapabilityState.DISABLED
                AbNotificationListenerService.isConnected() -> CapabilityState.READY
                else -> CapabilityState.DEGRADED
            },
        )
        capabilityCoordinator.set(
            Capability.CONTACTS,
            if (packageManager.resolveContentProvider(ContactsContract.AUTHORITY, 0) != null) {
                CapabilityState.READY
            } else {
                CapabilityState.DEGRADED
            },
        )
        capabilityCoordinator.set(
            Capability.ACCESSIBILITY,
            if (AbAccessibilityService.isConnected()) CapabilityState.READY else CapabilityState.DISABLED,
        )
        capabilityCoordinator.set(
            Capability.WAKE_WORD,
            if (AbVoiceInteractionService.isActive(this)) {
                capabilityCoordinator.state(Capability.WAKE_WORD)
            } else {
                CapabilityState.DISABLED
            },
        )
        toolRegistry.refreshCapabilityStates()
    }

    private fun isNetworkReady(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)
            ?.let { capabilities ->
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } == true
    }
}
