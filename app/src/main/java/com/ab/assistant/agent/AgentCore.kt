package com.ab.assistant.agent

import com.ab.assistant.tools.ToolCommand
import com.ab.assistant.tools.ToolCommandParser
import com.ab.assistant.tools.ToolExecutionResult
import com.ab.assistant.tools.ToolExecutor
import com.ab.assistant.tools.ToolResultCode
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AgentCore(
    private val model: AgentModel,
    private val toolRegistry: ToolExecutor,
    private val promptBuilder: PromptBuilder = PromptBuilder(),
    private val maxSteps: Int = MAX_STEPS,
    private val toolExecutionExecutor: Executor = Executors.newSingleThreadExecutor(),
) {
    fun run(userRequest: String, onComplete: (AgentResult) -> Unit) {
        val request = userRequest.trim()
        if (request.isEmpty()) {
            onComplete(AgentResult.Final("Hãy nhập yêu cầu trước."))
            return
        }
        UserCommandParser.parse(request)?.let { command ->
            execute(
                command,
                request,
                step = 1,
                permissionAlreadyApproved = false,
                confirmationAlreadyApproved = false,
                onComplete = onComplete,
            )
            return
        }
        model.generate(promptBuilder.initial(request)) { modelOutput ->
            handleModelOutput(request, modelOutput, step = 1, onComplete = onComplete)
        }
    }

    fun executeApproved(command: ToolCommand, onComplete: (AgentResult) -> Unit) {
        execute(
            command,
            userRequest = "",
            step = 1,
            permissionAlreadyApproved = true,
            confirmationAlreadyApproved = true,
            onComplete = onComplete,
        )
    }

    fun executeConfirmed(command: ToolCommand, onComplete: (AgentResult) -> Unit) {
        execute(
            command,
            userRequest = "",
            step = 1,
            permissionAlreadyApproved = false,
            confirmationAlreadyApproved = true,
            onComplete = onComplete,
        )
    }

    private fun handleModelOutput(
        userRequest: String,
        modelOutput: String,
        step: Int,
        onComplete: (AgentResult) -> Unit,
    ) {
        val command = ToolCommandParser.parse(modelOutput)
        if (command == null) {
            onComplete(AgentResult.Final(modelOutput.trim().ifBlank { "Không có phản hồi từ mô hình." }))
            return
        }
        execute(command, userRequest, step, permissionAlreadyApproved = false, confirmationAlreadyApproved = false, onComplete)
    }

    private fun execute(
        command: ToolCommand,
        userRequest: String,
        step: Int,
        permissionAlreadyApproved: Boolean,
        confirmationAlreadyApproved: Boolean,
        onComplete: (AgentResult) -> Unit,
    ) {
        if (!toolRegistry.isAvailable(command)) {
            onComplete(AgentResult.Final(toolRegistry.unavailableMessage(command)))
            return
        }
        if (!confirmationAlreadyApproved && toolRegistry.requiresConfirmation(command)) {
            onComplete(AgentResult.ConfirmationRequired(command, toolRegistry.confirmationMessage(command)))
            return
        }
        val requiredPermissions = toolRegistry.requiredPermissions(command)
        if (!permissionAlreadyApproved && requiredPermissions.isNotEmpty()) {
            onComplete(
                AgentResult.PermissionRequired(
                    command = command,
                    permissions = requiredPermissions,
                    message = toolRegistry.permissionMessage(command, requiredPermissions.first()),
                ),
            )
            return
        }

        toolExecutionExecutor.execute {
            val result = try {
                toolRegistry.execute(command)
            } catch (_: Exception) {
                ToolExecutionResult(
                    message = "Không thể thực hiện yêu cầu một cách an toàn.",
                    ok = false,
                    code = ToolResultCode.NOT_AVAILABLE,
                )
            }
            completeExecution(command, userRequest, step, result, onComplete)
        }
    }

    private fun completeExecution(
        command: ToolCommand,
        userRequest: String,
        step: Int,
        result: ToolExecutionResult,
        onComplete: (AgentResult) -> Unit,
    ) {
        if (!result.requiresFollowUp || step >= maxSteps || userRequest.isBlank()) {
            onComplete(AgentResult.Final(result.message))
            return
        }
        model.generate(promptBuilder.afterTool(userRequest, result)) { output ->
            handleModelOutput(userRequest, output, step + 1, onComplete)
        }
    }

    fun close() {
        (toolExecutionExecutor as? ExecutorService)?.shutdownNow()
    }

    sealed interface AgentResult {
        data class Final(val message: String) : AgentResult
        data class PermissionRequired(
            val command: ToolCommand,
            val permissions: List<String>,
            val message: String,
        ) : AgentResult

        data class ConfirmationRequired(
            val command: ToolCommand,
            val message: String,
        ) : AgentResult
    }

    companion object {
        const val MAX_STEPS = 5
    }
}
