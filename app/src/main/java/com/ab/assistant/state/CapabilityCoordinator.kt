package com.ab.assistant.state

enum class Capability {
    MODEL,
    NETWORK,
    NOTIFICATIONS,
    CONTACTS,
    ACCESSIBILITY,
    VOICE,
    WAKE_WORD,
}

enum class CapabilityState {
    DISABLED,
    CONNECTING,
    READY,
    DEGRADED,
}

data class CapabilitySnapshot(
    val states: Map<Capability, CapabilityState>,
) {
    fun state(capability: Capability): CapabilityState =
        states[capability] ?: CapabilityState.DISABLED
}

class CapabilityCoordinator {
    private val lock = Any()
    private val listeners = LinkedHashSet<(CapabilitySnapshot) -> Unit>()
    private var current = CapabilitySnapshot(
        Capability.entries.associateWith { CapabilityState.DISABLED },
    )

    fun snapshot(): CapabilitySnapshot = synchronized(lock) { current }

    fun state(capability: Capability): CapabilityState = snapshot().state(capability)

    fun isReady(capability: Capability): Boolean = state(capability) == CapabilityState.READY

    fun set(capability: Capability, state: CapabilityState): Boolean {
        val next: CapabilitySnapshot
        synchronized(lock) {
            if (current.state(capability) == state) return false
            next = CapabilitySnapshot(current.states.toMutableMap().apply { put(capability, state) })
            current = next
        }
        publish(next)
        return true
    }

    fun observe(listener: (CapabilitySnapshot) -> Unit): () -> Unit {
        val initial = synchronized(lock) {
            listeners += listener
            current
        }
        listener(initial)
        return { synchronized(lock) { listeners -= listener } }
    }

    fun describe(modelBackend: String? = null): String = buildString {
        appendLine("Trạng thái capability")
        modelBackend?.let { appendLine("Mô hình: $it") }
        Capability.entries.forEach { capability ->
            appendLine("${capability.label()}: ${state(capability).label()}")
        }
    }

    private fun publish(snapshot: CapabilitySnapshot) {
        val callbacks = synchronized(lock) { listeners.toList() }
        callbacks.forEach { callback -> runCatching { callback(snapshot) } }
    }

    private fun Capability.label(): String = when (this) {
        Capability.MODEL -> "Mô hình"
        Capability.NETWORK -> "Mạng"
        Capability.NOTIFICATIONS -> "Thông báo"
        Capability.CONTACTS -> "Danh bạ"
        Capability.ACCESSIBILITY -> "Accessibility"
        Capability.VOICE -> "Voice"
        Capability.WAKE_WORD -> "Wake word"
    }

    private fun CapabilityState.label(): String = when (this) {
        CapabilityState.DISABLED -> "tắt"
        CapabilityState.CONNECTING -> "đang kết nối"
        CapabilityState.READY -> "sẵn sàng"
        CapabilityState.DEGRADED -> "suy giảm / chưa sẵn sàng"
    }
}
