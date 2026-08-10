package com.ab.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.ab.assistant.AbApplication
import com.ab.assistant.state.Capability
import com.ab.assistant.state.CapabilityState

class AbAccessibilityService : AccessibilityService() {
    private val collector = AccessibilitySnapshotCollector()

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        latestSnapshot = null
        (application as? AbApplication)?.capabilityCoordinator?.set(Capability.ACCESSIBILITY, CapabilityState.READY)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // A source node is often only the changed leaf. Prefer the active
        // window root so semantic selectors and postconditions see the whole
        // representative screen; use the source only as a degraded fallback.
        val root = rootInActiveWindow ?: event.source ?: return
        val packageName = root.packageName?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: event.packageName?.toString().orEmpty()
        latestSnapshot = collector.collect(root, packageName)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        latestSnapshot = null
        (application as? AbApplication)?.refreshCapabilities()
        return super.onUnbind(intent)
    }

    companion object {
        @Volatile
        private var connected = false

        @Volatile
        private var latestSnapshot: SemanticUiSnapshot? = null

        fun isConnected(): Boolean = connected

        fun latestSnapshot(): SemanticUiSnapshot? = latestSnapshot

        fun settingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }
}
