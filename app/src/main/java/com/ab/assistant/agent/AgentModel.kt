package com.ab.assistant.agent

/** A serial text generator. Implementations must invoke the callback exactly once. */
fun interface AgentModel {
    fun generate(prompt: String, onComplete: (String) -> Unit)
}
