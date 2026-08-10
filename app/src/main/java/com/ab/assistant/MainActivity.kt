package com.ab.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ab.assistant.accessibility.AbAccessibilityService
import com.ab.assistant.agent.AgentCore
import com.ab.assistant.agent.ModelRequestMetadata
import com.ab.assistant.agent.PromptBuilder
import com.ab.assistant.state.Capability
import com.ab.assistant.state.CapabilityState
import com.ab.assistant.state.TaskState
import com.ab.assistant.tools.ToolCommand
import com.ab.assistant.notifications.AbNotificationListenerService
import com.ab.assistant.voice.VoiceSessionState
import com.ab.assistant.voice.WakeWordController

class MainActivity : Activity() {

    companion object {
        private const val runtimePermissionRequestCode = 1001
        private const val voicePermissionRequestCode = 1002
        private const val wakeWordPermissionRequestCode = 1003
        const val backendBenchmarkExtra = "com.ab.assistant.BACKEND_BENCHMARK"
        const val schemaBenchmarkExtra = "com.ab.assistant.SCHEMA_BENCHMARK_COUNT"
    }

    private val app: AbApplication get() = application as AbApplication
    private lateinit var statusView: TextView
    private lateinit var metricsView: TextView
    private lateinit var promptInput: EditText
    private lateinit var runButton: Button
    private lateinit var capabilitiesButton: Button
    private lateinit var notificationSettingsButton: Button
    private lateinit var accessibilitySettingsButton: Button
    private lateinit var voiceButton: Button
    private lateinit var wakeWordButton: Button
    private lateinit var confirmButton: Button
    private lateinit var cancelButton: Button
    private var pendingToolCommand: ToolCommand? = null
    private var pendingWakeWordStart = false
    private var modelBackend = "Loading model..."
    private val schemaBenchmarkCount: Int? by lazy {
        intent.getIntExtra(schemaBenchmarkExtra, -1).takeIf { it in setOf(4, 8, 16) }
    }
    private var removeTaskObserver: (() -> Unit)? = null
    private var removeCapabilityObserver: (() -> Unit)? = null
    private var removeVoiceObserver: (() -> Unit)? = null
    private var removeMetricsObserver: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val nativeStatus = app.nativeBridge.hello()
        val mnnStatus = AppText.mnnStatus(app.nativeBridge.mnnVersion())

        statusView = TextView(this).apply {
            text = getString(R.string.model_loading_status, AppText.title, mnnStatus, nativeStatus, getString(R.string.loading_model))
            textSize = 20f
        }
        metricsView = TextView(this).apply {
            text = "Inference metrics\nChÆ°a cÃ³ dá»¯ liá»‡u"
            textSize = 14f
        }
        promptInput = EditText(this).apply {
            setText(AppText.defaultPrompt)
            isEnabled = false
        }
        runButton = Button(this).apply {
            text = getString(R.string.run_local_assistant)
            isEnabled = false
            setOnClickListener { generate() }
        }
        capabilitiesButton = Button(this).apply {
            text = getString(R.string.device_capabilities)
            setOnClickListener {
                app.refreshCapabilities()
                statusView.text = app.capabilityCoordinator.describe(modelBackend)
            }
        }
        notificationSettingsButton = Button(this).apply {
            text = getString(R.string.enable_notification_access)
            setOnClickListener { openNotificationSettings() }
        }
        accessibilitySettingsButton = Button(this).apply {
            text = getString(R.string.enable_accessibility)
            setOnClickListener { openAccessibilitySettings() }
        }
        voiceButton = Button(this).apply {
            text = getString(R.string.voice_start)
            setOnClickListener { toggleVoice() }
        }
        wakeWordButton = Button(this).apply {
            text = getString(
                if (WakeWordController.isActive(this@MainActivity)) R.string.wake_word_stop else R.string.wake_word_start,
            )
            setOnClickListener { toggleWakeWord() }
        }
        confirmButton = Button(this).apply {
            text = getString(R.string.confirm_action)
            visibility = View.GONE
            setOnClickListener { confirmPendingToolCommand() }
        }
        cancelButton = Button(this).apply {
            text = getString(R.string.cancel_action)
            visibility = View.GONE
            setOnClickListener { cancelPendingToolCommand() }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(32, 32, 32, 32)
            addView(statusView)
            addView(metricsView)
            addView(promptInput)
            addView(runButton)
            addView(capabilitiesButton)
            addView(notificationSettingsButton)
            addView(accessibilitySettingsButton)
            addView(voiceButton)
            addView(wakeWordButton)
            addView(confirmButton)
            addView(cancelButton)
        }
        setContentView(ScrollView(this).apply { addView(content) })

        removeTaskObserver = app.taskSessionStore.observe { snapshot ->
            if (snapshot.state == TaskState.CANCELLED) {
                runOnUiThread {
                    hideConfirmation()
                    pendingToolCommand = null
                    statusView.text = getString(R.string.action_cancelled)
                    runButton.isEnabled = true
                }
            }
        }
        removeCapabilityObserver = app.capabilityCoordinator.observe { snapshot ->
            runOnUiThread {
                val modelReady = snapshot.state(Capability.MODEL) == CapabilityState.READY
                promptInput.isEnabled = modelReady
                val taskState = app.taskSessionStore.snapshot().state
                if (taskState == TaskState.IDLE || taskState == TaskState.COMPLETED ||
                    taskState == TaskState.FAILED || taskState == TaskState.CANCELLED
                ) {
                    runButton.isEnabled = modelReady
                }
                wakeWordButton.text = getString(
                    if (WakeWordController.isActive(this)) R.string.wake_word_stop else R.string.wake_word_start,
                )
            }
        }
        removeVoiceObserver = app.voiceCoordinator.observe { state ->
            runOnUiThread {
                voiceButton.text = when (state) {
                    VoiceSessionState.IDLE -> getString(R.string.voice_start)
                    VoiceSessionState.LISTENING -> getString(R.string.voice_stop_listening)
                    VoiceSessionState.PROCESSING -> getString(R.string.voice_processing)
                    VoiceSessionState.SPEAKING -> getString(R.string.voice_speaking)
                    VoiceSessionState.WAITING_FOR_CONFIRMATION -> {
                        handleVoicePendingAction()
                        getString(R.string.voice_waiting_confirmation)
                    }
                    VoiceSessionState.FAILED -> getString(R.string.voice_start)
                }
            }
        }
        removeMetricsObserver = app.inferenceMetricsStore.observe { metrics ->
            runOnUiThread {
                metricsView.text = metrics?.toDebugText() ?: "Inference metrics\nChÆ°a cÃ³ dá»¯ liá»‡u"
            }
        }

        val benchmarkBackend = intent.getStringExtra(backendBenchmarkExtra)
        val loadModel: ((String) -> Unit) -> Unit = if (benchmarkBackend.isNullOrBlank()) {
            app::loadModel
        } else {
            { callback -> app.loadModelBackend(benchmarkBackend, callback) }
        }
        loadModel { result ->
            runOnUiThread {
                statusView.text = getString(R.string.model_ready_status, AppText.title, mnnStatus, result)
                val loaded = result == "OPENCL" || result == "VULKAN" || result == "CPU"
                modelBackend = if (loaded) result else "Không tải được: $result"
                promptInput.isEnabled = loaded
                runButton.isEnabled = loaded
            }
        }
    }

    override fun onResume() {
        super.onResume()
        app.refreshCapabilities()
        if (::wakeWordButton.isInitialized) {
            wakeWordButton.text = getString(
                if (WakeWordController.isActive(this)) R.string.wake_word_stop else R.string.wake_word_start,
            )
        }
    }

    override fun onDestroy() {
        removeTaskObserver?.invoke()
        removeTaskObserver = null
        removeCapabilityObserver?.invoke()
        removeCapabilityObserver = null
        removeVoiceObserver?.invoke()
        removeVoiceObserver = null
        removeMetricsObserver?.invoke()
        removeMetricsObserver = null
        if (!WakeWordController.isActive(this)) {
            app.voiceCoordinator.stop()
        }
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == voicePermissionRequestCode) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                app.voiceCoordinator.start()
            } else {
                statusView.text = getString(R.string.permission_denied)
            }
            return
        }
        if (requestCode == wakeWordPermissionRequestCode) {
            val granted = grantResults.isNotEmpty() &&
                grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (granted && pendingWakeWordStart) {
                pendingWakeWordStart = false
                if (!WakeWordController.openAssistantSettings(this)) {
                    statusView.text = getString(R.string.wake_word_settings_unavailable)
                }
            } else {
                pendingWakeWordStart = false
                statusView.text = getString(R.string.permission_denied)
            }
            return
        }
        if (requestCode != runtimePermissionRequestCode) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            executePendingToolCommand()
        } else {
            app.agentCore.cancel()
            pendingToolCommand = null
            statusView.text = getString(R.string.permission_denied)
        }
    }

    private fun generate() {
        val userRequest = promptInput.text.toString()
        runButton.isEnabled = false
        cancelButton.visibility = View.VISIBLE
        statusView.text = getString(R.string.generating_locally)
        val benchmarkCount = schemaBenchmarkCount
        if (benchmarkCount != null) {
            val prompt = PromptBuilder().benchmarkInitial(userRequest, benchmarkCount)
            app.modelRuntime.generateWithMetadata(
                prompt,
                ModelRequestMetadata(
                    promptCharacters = prompt.length,
                    exposedToolCount = benchmarkCount,
                    modelDecisionIndex = 1,
                ),
            ) { output ->
                runOnUiThread {
                    statusView.text = output
                    runButton.isEnabled = true
                    cancelButton.visibility = View.GONE
                }
            }
            return
        }
        app.agentCore.run(userRequest) { result ->
            runOnUiThread {
                handleAgentResult(result)
                runButton.isEnabled = true
            }
        }
    }

    private fun handleAgentResult(result: AgentCore.AgentResult) {
        when (result) {
            is AgentCore.AgentResult.Final -> {
                hideConfirmation()
                statusView.text = result.message
                runButton.isEnabled = true
            }
            is AgentCore.AgentResult.PermissionRequired -> {
                hideConfirmation()
                pendingToolCommand = result.command
                statusView.text = result.message
                runButton.isEnabled = false
                if (result.permissions.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
                    requestPermissions(result.permissions.toTypedArray(), runtimePermissionRequestCode)
                } else {
                    executePendingToolCommand()
                }
            }
            is AgentCore.AgentResult.ConfirmationRequired -> {
                pendingToolCommand = result.command
                statusView.text = result.message
                runButton.isEnabled = false
                confirmButton.visibility = View.VISIBLE
                cancelButton.visibility = View.VISIBLE
            }
        }
    }

    private fun executePendingToolCommand() {
        val command = pendingToolCommand ?: return
        pendingToolCommand = null
        statusView.text = getString(R.string.executing_device_command)
        if (app.voiceCoordinator.pendingAction() is AgentCore.AgentResult.PermissionRequired) {
            app.voiceCoordinator.approvePending()
            return
        }
        app.agentCore.executeApproved(command) { result -> runOnUiThread {
            handleAgentResult(result)
            runButton.isEnabled = true
        } }
    }

    private fun confirmPendingToolCommand() {
        val command = pendingToolCommand ?: return
        pendingToolCommand = null
        hideConfirmation()
        runButton.isEnabled = false
        statusView.text = getString(R.string.executing_device_command)
        if (app.voiceCoordinator.pendingAction() is AgentCore.AgentResult.ConfirmationRequired) {
            app.voiceCoordinator.confirmPending()
            return
        }
        app.agentCore.executeConfirmed(command) { result -> runOnUiThread {
            handleAgentResult(result)
            runButton.isEnabled = true
        } }
    }

    private fun cancelPendingToolCommand() {
        pendingToolCommand = null
        if (app.voiceCoordinator.pendingAction() != null) {
            app.voiceCoordinator.denyPending()
            hideConfirmation()
            statusView.text = getString(R.string.action_cancelled)
            runButton.isEnabled = true
            return
        }
        app.agentCore.cancel()
        hideConfirmation()
        statusView.text = getString(R.string.action_cancelled)
        runButton.isEnabled = true
    }

    private fun hideConfirmation() {
        confirmButton.visibility = View.GONE
        cancelButton.visibility = View.GONE
    }

    private fun handleVoicePendingAction() {
        when (val pending = app.voiceCoordinator.pendingAction()) {
            is AgentCore.AgentResult.ConfirmationRequired -> {
                pendingToolCommand = pending.command
                statusView.text = pending.message
                runButton.isEnabled = false
                confirmButton.visibility = View.VISIBLE
                cancelButton.visibility = View.VISIBLE
            }
            is AgentCore.AgentResult.PermissionRequired -> {
                pendingToolCommand = pending.command
                statusView.text = pending.message
                runButton.isEnabled = false
                if (pending.permissions.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
                    requestPermissions(pending.permissions.toTypedArray(), runtimePermissionRequestCode)
                } else {
                    pendingToolCommand = null
                    app.voiceCoordinator.approvePending()
                }
            }
            null,
            is AgentCore.AgentResult.Final -> Unit
        }
    }

    private fun openNotificationSettings() {
        val intent: Intent = AbNotificationListenerService.settingsIntent()
        if (intent.resolveActivity(packageManager) == null) {
            statusView.text = getString(R.string.notification_settings_unavailable)
            return
        }
        startActivity(intent)
    }

    private fun openAccessibilitySettings() {
        val intent = AbAccessibilityService.settingsIntent()
        if (intent.resolveActivity(packageManager) == null) {
            statusView.text = getString(R.string.accessibility_settings_unavailable)
            return
        }
        startActivity(intent)
    }

    private fun toggleVoice() {
        when (app.voiceCoordinator.state()) {
            VoiceSessionState.LISTENING,
            VoiceSessionState.PROCESSING,
            VoiceSessionState.SPEAKING,
            VoiceSessionState.WAITING_FOR_CONFIRMATION
            -> app.voiceCoordinator.stop()
            VoiceSessionState.IDLE,
            VoiceSessionState.FAILED
            -> if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), voicePermissionRequestCode)
            } else {
                app.voiceCoordinator.start()
            }
        }
    }

    private fun toggleWakeWord() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingWakeWordStart = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), wakeWordPermissionRequestCode)
            return
        }
        if (!WakeWordController.openAssistantSettings(this)) {
            statusView.text = getString(R.string.wake_word_settings_unavailable)
        }
    }

}
