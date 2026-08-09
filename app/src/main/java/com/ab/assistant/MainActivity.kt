package com.ab.assistant

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
import com.ab.assistant.agent.AgentCore
import com.ab.assistant.tools.ToolCommand
import com.ab.assistant.notifications.AbNotificationListenerService

class MainActivity : Activity() {

    companion object {
        private const val runtimePermissionRequestCode = 1001
    }

    private val app: AbApplication get() = application as AbApplication
    private lateinit var statusView: TextView
    private lateinit var promptInput: EditText
    private lateinit var runButton: Button
    private lateinit var capabilitiesButton: Button
    private lateinit var notificationSettingsButton: Button
    private lateinit var confirmButton: Button
    private lateinit var cancelButton: Button
    private var pendingToolCommand: ToolCommand? = null
    private var modelBackend = "Loading model..."

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val nativeStatus = app.nativeBridge.hello()
        val mnnStatus = AppText.mnnStatus(app.nativeBridge.mnnVersion())

        statusView = TextView(this).apply {
            text = getString(R.string.model_loading_status, AppText.title, mnnStatus, nativeStatus, getString(R.string.loading_model))
            textSize = 20f
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
            setOnClickListener { statusView.text = app.toolRegistry.capabilityStatus(modelBackend) }
        }
        notificationSettingsButton = Button(this).apply {
            text = getString(R.string.enable_notification_access)
            setOnClickListener { openNotificationSettings() }
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
            addView(promptInput)
            addView(runButton)
            addView(capabilitiesButton)
            addView(notificationSettingsButton)
            addView(confirmButton)
            addView(cancelButton)
        }
        setContentView(ScrollView(this).apply { addView(content) })

        app.loadModel { result ->
            runOnUiThread {
                statusView.text = getString(R.string.model_ready_status, AppText.title, mnnStatus, result)
                val loaded = result == "OPENCL" || result == "CPU"
                modelBackend = if (loaded) result else "Không tải được: $result"
                promptInput.isEnabled = loaded
                runButton.isEnabled = loaded
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != runtimePermissionRequestCode) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            executePendingToolCommand()
        } else {
            pendingToolCommand = null
            statusView.text = getString(R.string.permission_denied)
        }
    }

    private fun generate() {
        val userRequest = promptInput.text.toString()
        runButton.isEnabled = false
        statusView.text = getString(R.string.generating_locally)
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
            }
            is AgentCore.AgentResult.PermissionRequired -> {
                hideConfirmation()
                pendingToolCommand = result.command
                statusView.text = result.message
                if (result.permissions.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
                    requestPermissions(result.permissions.toTypedArray(), runtimePermissionRequestCode)
                } else {
                    executePendingToolCommand()
                }
            }
            is AgentCore.AgentResult.ConfirmationRequired -> {
                pendingToolCommand = result.command
                statusView.text = result.message
                confirmButton.visibility = View.VISIBLE
                cancelButton.visibility = View.VISIBLE
            }
        }
    }

    private fun executePendingToolCommand() {
        val command = pendingToolCommand ?: return
        pendingToolCommand = null
        statusView.text = getString(R.string.executing_device_command)
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
        app.agentCore.executeConfirmed(command) { result -> runOnUiThread {
            handleAgentResult(result)
            runButton.isEnabled = true
        } }
    }

    private fun cancelPendingToolCommand() {
        pendingToolCommand = null
        hideConfirmation()
        statusView.text = getString(R.string.action_cancelled)
    }

    private fun hideConfirmation() {
        confirmButton.visibility = View.GONE
        cancelButton.visibility = View.GONE
    }

    private fun openNotificationSettings() {
        val intent: Intent = AbNotificationListenerService.settingsIntent()
        if (intent.resolveActivity(packageManager) == null) {
            statusView.text = getString(R.string.notification_settings_unavailable)
            return
        }
        startActivity(intent)
    }

}
