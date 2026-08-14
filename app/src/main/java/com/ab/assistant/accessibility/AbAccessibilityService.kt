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
    private val actionExecutor = UiActionExecutor()

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        instance = this
        synchronized(snapshotMonitor) {
            latestSnapshot = null
            snapshotMonitor.notifyAll()
        }
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
        synchronized(snapshotMonitor) {
            latestSnapshot = collector.collect(root, packageName)
            snapshotMonitor.notifyAll()
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        instance = null
        synchronized(snapshotMonitor) {
            latestSnapshot = null
            snapshotMonitor.notifyAll()
        }
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

        /** Waits briefly for an event-produced screen snapshot; never treats performAction alone as success. */
        fun awaitSnapshotAfter(snapshotId: Long, timeoutMs: Long): SemanticUiSnapshot? = synchronized(snapshotMonitor) {
            val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(0)
            while (latestSnapshot?.snapshotId?.let { it > snapshotId } != true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return@synchronized null
                try {
                    snapshotMonitor.wait(remaining)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@synchronized null
                }
            }
            latestSnapshot
        }

        fun execute(action: UiAction): UiActionResult =
            instance?.actionExecutor?.execute(latestSnapshot, instance?.rootInActiveWindow, action)
                ?: UiActionResult(false, "Accessibility service is not connected.")

        fun settingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

        @Volatile
        private var instance: AbAccessibilityService? = null

        private val snapshotMonitor = Object()
    }
}
