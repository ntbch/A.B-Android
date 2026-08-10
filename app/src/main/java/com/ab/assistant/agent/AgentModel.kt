package com.ab.assistant.agent

/** A serial text generator. Implementations must invoke the callback exactly once. */
fun interface AgentModel {
    fun generate(prompt: String, onComplete: (String) -> Unit)
}

data class ModelRequestMetadata(
    val promptCharacters: Int,
    val exposedToolCount: Int?,
    val modelDecisionIndex: Int?,
)

interface InstrumentedAgentModel : AgentModel {
    fun generateWithMetadata(
        prompt: String,
        metadata: ModelRequestMetadata,
        onComplete: (String) -> Unit,
    )
}
