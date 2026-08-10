package com.ab.assistant.model

class InferenceMetricsStore {
    private val lock = Any()
    private val listeners = LinkedHashMap<Long, (InferenceMetrics?) -> Unit>()
    private var nextListenerId = 0L
    private var current: InferenceMetrics? = null

    fun latest(): InferenceMetrics? = synchronized(lock) { current }

    fun publish(metrics: InferenceMetrics) {
        val callbacks = synchronized(lock) {
            current = metrics
            listeners.values.toList()
        }
        callbacks.forEach { callback -> runCatching { callback(metrics) } }
    }

    fun observe(listener: (InferenceMetrics?) -> Unit): () -> Unit {
        val registration = synchronized(lock) {
            val id = ++nextListenerId
            listeners[id] = listener
            id to current
        }
        listener(registration.second)
        return { synchronized(lock) { listeners.remove(registration.first) } }
    }
}
