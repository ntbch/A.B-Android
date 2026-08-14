package com.ab.assistant.state

enum class TaskState {
    IDLE,
    ROUTING,
    WAITING_FOR_MODEL,
    WAITING_FOR_CONFIRMATION,
    EXECUTING,
    WAITING_FOR_TOOL,
    STOPPING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class TaskSessionSnapshot(
    val taskId: Long?,
    val request: String?,
    val state: TaskState,
    val message: String? = null,
    val route: String? = null,
    val decisionStep: Int = 0,
    val observations: List<TaskObservation> = emptyList(),
)

/** Bounded, verified evidence retained only for the active task. */
data class TaskObservation(
    val step: Int,
    val action: String,
    val summary: String,
    val ok: Boolean,
    val verified: Boolean,
    val code: String,
)

class TaskSessionStore {
    private val lock = Any()
    private val listeners = LinkedHashSet<(TaskSessionSnapshot) -> Unit>()
    private var nextTaskId = 0L
    private var current = TaskSessionSnapshot(null, null, TaskState.IDLE)

    fun snapshot(): TaskSessionSnapshot = synchronized(lock) { current }

    fun begin(request: String): Long? {
        val next: TaskSessionSnapshot
        synchronized(lock) {
            if (current.state.isActive) return null
            next = TaskSessionSnapshot(++nextTaskId, request, TaskState.ROUTING)
            current = next
        }
        publish(next)
        return next.taskId
    }

    fun transition(taskId: Long, state: TaskState, message: String? = null): Boolean {
        val next: TaskSessionSnapshot
        synchronized(lock) {
            if (current.taskId != taskId || !current.state.isActive) return false
            next = current.copy(state = state, message = message)
            current = next
        }
        publish(next)
        return true
    }

    fun setRoute(taskId: Long, route: String): Boolean = update(taskId) { current ->
        current.copy(route = route)
    }

    fun recordObservation(taskId: Long, observation: TaskObservation): Boolean = update(taskId) { current ->
        current.copy(
            decisionStep = observation.step,
            observations = (current.observations + observation).takeLast(MAX_OBSERVATIONS),
        )
    }

    fun complete(taskId: Long, message: String? = null): Boolean =
        transition(taskId, TaskState.COMPLETED, message)

    fun fail(taskId: Long, message: String? = null): Boolean =
        transition(taskId, TaskState.FAILED, message)

    fun cancel(): Boolean {
        val stopping: TaskSessionSnapshot
        val cancelled: TaskSessionSnapshot
        synchronized(lock) {
            if (!current.state.isActive) return false
            stopping = current.copy(state = TaskState.STOPPING)
            cancelled = stopping.copy(state = TaskState.CANCELLED)
            current = cancelled
        }
        publish(stopping)
        publish(cancelled)
        return true
    }

    fun currentTaskId(): Long? = snapshot().takeIf { it.state.isActive }?.taskId

    fun isActive(taskId: Long): Boolean = synchronized(lock) {
        current.taskId == taskId && current.state.isActive
    }

    fun observe(listener: (TaskSessionSnapshot) -> Unit): () -> Unit {
        val initial = synchronized(lock) {
            listeners += listener
            current
        }
        listener(initial)
        return { synchronized(lock) { listeners -= listener } }
    }

    private fun update(
        taskId: Long,
        transform: (TaskSessionSnapshot) -> TaskSessionSnapshot,
    ): Boolean {
        val next: TaskSessionSnapshot
        synchronized(lock) {
            if (current.taskId != taskId || !current.state.isActive) return false
            next = transform(current)
            current = next
        }
        publish(next)
        return true
    }

    private fun publish(snapshot: TaskSessionSnapshot) {
        val callbacks = synchronized(lock) { listeners.toList() }
        callbacks.forEach { callback -> runCatching { callback(snapshot) } }
    }

    private val TaskState.isActive: Boolean
        get() = this != TaskState.IDLE &&
            this != TaskState.COMPLETED &&
            this != TaskState.FAILED &&
            this != TaskState.CANCELLED

    private companion object {
        const val MAX_OBSERVATIONS = 5
    }
}
