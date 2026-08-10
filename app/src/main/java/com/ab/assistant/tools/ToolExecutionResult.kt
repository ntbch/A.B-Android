package com.ab.assistant.tools

enum class ToolResultCode {
    OK,
    TIMEOUT,
    CANCELLED,
    PERMISSION_MISSING,
    NOT_AVAILABLE,
    NOT_FOUND,
    AMBIGUOUS,
    NETWORK_UNAVAILABLE,
    NETWORK_ERROR,
}

data class ToolExecutionResult(
    val message: String,
    val ok: Boolean = true,
    val code: ToolResultCode = ToolResultCode.OK,
    /** Current Phase 2 tools are terminal. This supports bounded multi-step agent flows. */
    val requiresFollowUp: Boolean = false,
    /** True only when the executor verified the requested postcondition. */
    val verified: Boolean = ok,
)
