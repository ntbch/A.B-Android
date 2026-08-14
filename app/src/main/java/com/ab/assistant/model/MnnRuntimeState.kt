package com.ab.assistant.model

enum class MnnRuntimeState {
    UNINITIALIZED,
    LOADING,
    READY,
    GENERATING,
    ERROR,
    UNLOADING,
}

data class MnnRuntimeSnapshot(
    val state: MnnRuntimeState = MnnRuntimeState.UNINITIALIZED,
    val backend: String? = null,
    val error: String? = null,
)

/** Thread-safe observable state; UI and agent code never infer readiness from model files. */
class MnnRuntimeStateStore {
    private val lock = Any()
    private val listeners = LinkedHashSet<(MnnRuntimeSnapshot) -> Unit>()
    private var current = MnnRuntimeSnapshot()

    fun snapshot(): MnnRuntimeSnapshot = synchronized(lock) { current }

    fun transition(state: MnnRuntimeState, backend: String? = snapshot().backend, error: String? = null) {
        val next = synchronized(lock) {
            MnnRuntimeSnapshot(state, backend, error).also { current = it }
        }
        val callbacks = synchronized(lock) { listeners.toList() }
        callbacks.forEach { callback -> runCatching { callback(next) } }
    }

    fun observe(listener: (MnnRuntimeSnapshot) -> Unit): () -> Unit {
        val initial = synchronized(lock) { listeners += listener; current }
        listener(initial)
        return { synchronized(lock) { listeners -= listener } }
    }
}
