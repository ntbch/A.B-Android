package com.ab.assistant.agent

import com.ab.assistant.tools.ToolCommand
import com.ab.assistant.tools.ToolCommandParser
import com.ab.assistant.tools.ToolExecutionResult
import com.ab.assistant.tools.ToolExecutor
import com.ab.assistant.tools.ToolResultCode
import com.ab.assistant.tools.ConfirmationPolicy
import com.ab.assistant.tools.TypedToolRegistry
import com.ab.assistant.tools.toToolCall
import com.ab.assistant.tools.toToolExecutionResult
import com.ab.assistant.communication.ApprovedOutboundAction
import com.ab.assistant.communication.OutboundApprovalStore
import com.ab.assistant.communication.PreparedOutboundAction
import com.ab.assistant.state.TaskSessionStore
import com.ab.assistant.state.TaskState
import com.ab.assistant.state.TaskObservation
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class AgentCore(
    private val model: AgentModel,
    private val toolRegistry: ToolExecutor,
    private val promptBuilder: PromptBuilder = PromptBuilder(),
    private val maxSteps: Int = MAX_STEPS,
    private val toolExecutionExecutor: Executor = Executors.newSingleThreadExecutor(),
    private val typedToolRegistry: TypedToolRegistry = TypedToolRegistry(toolRegistry),
    private val pipelineRouter: PipelineRouter = PipelineRouter(),
    private val taskSessionStore: TaskSessionStore = TaskSessionStore(),
    private val skillEngine: SkillEngine = SkillEngine(),
    private val outboundApprovalStore: OutboundApprovalStore = OutboundApprovalStore(),
    private val proceduralSkillLearner: ProceduralSkillLearner? = null,
) {
    private val skillExecutions = ConcurrentHashMap<Long, SkillExecution>()
    private val pendingSkillContinuations = ConcurrentHashMap<Long, (ToolExecutionResult, (AgentResult) -> Unit) -> Unit>()
    private val stuckDetectors = ConcurrentHashMap<Long, StuckDetector>()
    private val preparedOutbound = ConcurrentHashMap<Long, PreparedOutboundAction>()
    private val approvedOutbound = ConcurrentHashMap<Long, ApprovedOutboundAction>()
    private val modelDecisionCounts = ConcurrentHashMap<Long, Int>()
    private val pendingDecisionSteps = ConcurrentHashMap<Long, Int>()
    private val exposedToolGroupsByTask = ConcurrentHashMap<Long, Set<ToolGroup>>()
    private val malformedRepairCounts = ConcurrentHashMap<Long, Int>()
    private val repeatedActionRecoveryCounts = ConcurrentHashMap<Long, Int>()
    private val runningTools = ConcurrentHashMap<Long, Future<ToolExecutionResult>>()
    private val toolWorkerExecutor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "ab-tool-worker").apply { isDaemon = true }
    }

    fun run(userRequest: String, onComplete: (AgentResult) -> Unit) {
        val request = userRequest.trim()
        if (request.isEmpty()) {
            onComplete(AgentResult.Final("Hãy nhập yêu cầu trước."))
            return
        }
        val taskId = taskSessionStore.begin(request)
        if (taskId == null) {
            onComplete(AgentResult.Final("Đang có một tác vụ khác được xử lý."))
            return
        }
        proceduralSkillLearner?.begin(taskId, request)
        modelDecisionCounts[taskId] = 0
        val route = pipelineRouter.route(request)
        stuckDetectors[taskId] = StuckDetector(MAX_STEPS, maxTaskWallMs(route))
        taskSessionStore.setRoute(taskId, routeName(route))
        if (route !is RouteDecision.Direct) {
            taskSessionStore.transition(taskId, TaskState.WAITING_FOR_MODEL)
        }
        when (route) {
            is RouteDecision.Direct -> {
                val command = typedToolRegistry.command(route.call)
                if (command == null) {
                    finishFinal(taskId, "Tool command is not registered.", failed = true, onComplete)
                } else {
                    execute(
                        command,
                        request,
                        taskId,
                        step = 1,
                        permissionAlreadyApproved = false,
                        confirmationAlreadyApproved = false,
                        onComplete = onComplete,
                    )
                }
            }
            is RouteDecision.ModelTool -> {
                exposedToolGroupsByTask[taskId] = route.exposedToolGroups
                generateModel(
                    taskId,
                    promptBuilder.initial(request, route.exposedToolGroups),
                    promptBuilder.exposedToolCount(route.exposedToolGroups),
                ) { modelOutput ->
                    if (taskSessionStore.isActive(taskId)) {
                        handleModelOutput(taskId, request, modelOutput, step = 1, onComplete = onComplete)
                    }
                }
            }
            is RouteDecision.Skill -> {
                val execution = skillEngine.start(route.skillId, request, route.arguments)
                if (execution == null) {
                    finishFinal(taskId, "Skill không được đăng ký.", failed = true, onComplete)
                } else {
                    skillExecutions[taskId] = execution
                    runSkill(taskId, execution, request, onComplete)
                }
            }
            is RouteDecision.Agent -> {
                val toolGroups = ToolGroup.entries.toSet()
                exposedToolGroupsByTask[taskId] = toolGroups
                generateModel(
                    taskId,
                    promptBuilder.agentInitial(request, toolGroups),
                    promptBuilder.exposedToolCount(toolGroups),
                ) { modelOutput ->
                    if (taskSessionStore.isActive(taskId)) {
                        handleModelOutput(taskId, request, modelOutput, step = 1, onComplete = onComplete)
                    }
                }
            }
        }
    }

    fun executeApproved(command: ToolCommand, onComplete: (AgentResult) -> Unit) {
        val taskId = taskSessionStore.currentTaskId()
        if (taskId == null) {
            onComplete(AgentResult.Final("Không có tác vụ đang chờ phê duyệt."))
            return
        }
        val approvedCommand = approvedOutbound.remove(taskId)?.command ?: command
        val request = taskSessionStore.snapshot().request.orEmpty()
        execute(
            approvedCommand,
            userRequest = request,
            taskId = taskId,
            step = pendingDecisionSteps.remove(taskId) ?: 1,
            permissionAlreadyApproved = true,
            confirmationAlreadyApproved = true,
            onComplete = onComplete,
            recordForStuck = false,
            onToolResult = pendingSkillContinuations.remove(taskId)?.let { continuation ->
                { result -> continuation(result, onComplete) }
            },
        )
    }

    fun executeConfirmed(command: ToolCommand, onComplete: (AgentResult) -> Unit) {
        val taskId = taskSessionStore.currentTaskId()
        if (taskId == null) {
            onComplete(AgentResult.Final("Không có tác vụ đang chờ xác nhận."))
            return
        }
        val confirmedCommand = if (command.isOutbound()) {
            val prepared = preparedOutbound.remove(taskId)
            val approved = prepared?.let { outboundApprovalStore.authorize(it.token, command) }
            if (approved == null) {
                finishFinal(taskId, "Xác nhận đã hết hạn hoặc payload không còn khớp.", failed = true, onComplete)
                return
            }
            approvedOutbound[taskId] = approved
            approved.command
        } else {
            command
        }
        val request = taskSessionStore.snapshot().request.orEmpty()
        execute(
            confirmedCommand,
            userRequest = request,
            taskId = taskId,
            step = pendingDecisionSteps.remove(taskId) ?: 1,
            permissionAlreadyApproved = false,
            confirmationAlreadyApproved = true,
            onComplete = onComplete,
            recordForStuck = false,
            onToolResult = pendingSkillContinuations.remove(taskId)?.let { continuation ->
                { result -> continuation(result, onComplete) }
            },
        )
    }

    private fun handleModelOutput(
        taskId: Long,
        userRequest: String,
        modelOutput: String,
        step: Int,
        onComplete: (AgentResult) -> Unit,
    ) {
        if (!taskSessionStore.isActive(taskId)) return
        if (modelOutput == AGENT_DECISION_BUDGET_EXCEEDED) {
            finishFinal(taskId, "Tác vụ đã đạt giới hạn số quyết định AI an toàn.", failed = true, onComplete)
            return
        }
        if (modelOutput.startsWith("ERROR:")) {
            finishFinal(taskId, modelOutput, failed = true, onComplete)
            return
        }
        val command = ToolCommandParser.parse(modelOutput)
        val modelDecision = stuckDetectors[taskId]?.recordModelOutput(modelOutput, command)
        if (modelDecision?.isStuck == true) {
            finishFinal(taskId, modelDecision.message.orEmpty(), failed = true, onComplete)
            return
        }
        if (command == null) {
            if (looksLikeMalformedToolOutput(modelOutput)) {
                val repairs = malformedRepairCounts.merge(taskId, 1) { previous, increment -> previous + increment } ?: 1
                if (repairs <= MAX_MALFORMED_REPAIRS) {
                    val groups = exposedToolGroupsByTask[taskId] ?: ToolGroup.entries.toSet()
                    taskSessionStore.transition(taskId, TaskState.WAITING_FOR_MODEL, "Repairing malformed model output.")
                    generateModel(
                        taskId,
                        promptBuilder.repairMalformedToolOutput(userRequest, modelOutput, groups),
                        promptBuilder.exposedToolCount(groups),
                    ) { repairedOutput ->
                        if (taskSessionStore.isActive(taskId)) {
                            handleModelOutput(taskId, userRequest, repairedOutput, step, onComplete)
                        }
                    }
                    return
                }
            }
            finishFinal(taskId, modelOutput.trim().ifBlank { "Không có phản hồi từ mô hình." }, onComplete = onComplete)
            return
        }
        execute(
            command,
            userRequest,
            taskId,
            step,
            permissionAlreadyApproved = false,
            confirmationAlreadyApproved = false,
            onComplete,
        )
    }

    private fun execute(
        command: ToolCommand,
        userRequest: String,
        taskId: Long,
        step: Int,
        permissionAlreadyApproved: Boolean,
        confirmationAlreadyApproved: Boolean,
        onComplete: (AgentResult) -> Unit,
        onToolResult: ((ToolExecutionResult) -> Unit)? = null,
        recordForStuck: Boolean = true,
    ) {
        if (!taskSessionStore.isActive(taskId)) return
        if (recordForStuck) {
            val actionDecision = stuckDetectors[taskId]?.recordAction(command)
            if (actionDecision?.isStuck == true) {
                if (actionDecision.reason == StuckReason.REPEATED_ACTION &&
                    recoverRepeatedAgentAction(taskId, command, userRequest, step, onComplete)
                ) {
                    return
                }
                finishFinal(taskId, actionDecision.message.orEmpty(), failed = true, onComplete)
                return
            }
        }
        val spec = typedToolRegistry.spec(command)
        if (spec.name.isBlank()) {
            finishFinal(taskId, "Tool command is not registered.", failed = true, onComplete)
            return
        }
        if (!toolRegistry.isAvailable(command)) {
            finishFinal(taskId, toolRegistry.unavailableMessage(command), failed = true, onComplete)
            return
        }
        if (!confirmationAlreadyApproved &&
            (spec.confirmation == ConfirmationPolicy.REQUIRED || toolRegistry.requiresConfirmation(command))
        ) {
            pendingDecisionSteps[taskId] = step
            val prepared = outboundApprovalStore.prepare(command)
            prepared?.let { preparedOutbound[taskId] = it }
            val confirmationMessage = toolRegistry.confirmationMessage(command)
            taskSessionStore.transition(taskId, TaskState.WAITING_FOR_CONFIRMATION, confirmationMessage)
            onComplete(AgentResult.ConfirmationRequired(command, confirmationMessage))
            return
        }
        val requiredPermissions = toolRegistry.requiredPermissions(command)
        if (!permissionAlreadyApproved && requiredPermissions.isNotEmpty()) {
            pendingDecisionSteps[taskId] = step
            taskSessionStore.transition(taskId, TaskState.WAITING_FOR_CONFIRMATION, "Cần cấp quyền để tiếp tục.")
            onComplete(
                AgentResult.PermissionRequired(
                    command = command,
                    permissions = requiredPermissions,
                    message = toolRegistry.permissionMessage(command, requiredPermissions.first()),
                ),
            )
            return
        }

        taskSessionStore.transition(taskId, TaskState.WAITING_FOR_TOOL)
        toolExecutionExecutor.execute {
            if (!taskSessionStore.isActive(taskId)) return@execute
            taskSessionStore.transition(taskId, TaskState.EXECUTING)
            approvedOutbound.remove(taskId)
            val result = try {
                executeToolBounded(taskId, command, spec.timeoutMs)
            } catch (_: Exception) {
                ToolExecutionResult(
                    message = "Không thể thực hiện yêu cầu một cách an toàn.",
                    ok = false,
                    code = ToolResultCode.NOT_AVAILABLE,
                )
            }
            proceduralSkillLearner?.recordStep(taskId, command, result)
            completeExecution(taskId, command, userRequest, step, result, onComplete, onToolResult)
        }
    }

    private fun executeToolBounded(
        taskId: Long,
        command: ToolCommand,
        timeoutMs: Long,
    ): ToolExecutionResult {
        val future = toolWorkerExecutor.submit<ToolExecutionResult> {
            typedToolRegistry.execute(command).toToolExecutionResult()
        }
        runningTools[taskId] = future
        if (!taskSessionStore.isActive(taskId)) {
            runningTools.remove(taskId, future)
            future.cancel(true)
        }
        return try {
            future.get(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            ToolExecutionResult("Tool execution timed out safely.", ok = false, code = ToolResultCode.TIMEOUT)
        } catch (_: CancellationException) {
            ToolExecutionResult("Tool execution was canceled.", ok = false, code = ToolResultCode.CANCELLED)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            future.cancel(true)
            ToolExecutionResult("Tool execution was canceled.", ok = false, code = ToolResultCode.CANCELLED)
        } catch (_: ExecutionException) {
            ToolExecutionResult("Tool execution failed safely.", ok = false, code = ToolResultCode.NOT_AVAILABLE)
        } finally {
            runningTools.remove(taskId, future)
        }
    }

    private fun completeExecution(
        taskId: Long,
        command: ToolCommand,
        userRequest: String,
        step: Int,
        result: ToolExecutionResult,
        onComplete: (AgentResult) -> Unit,
        onToolResult: ((ToolExecutionResult) -> Unit)?,
    ) {
        if (!taskSessionStore.isActive(taskId)) return
        taskSessionStore.recordObservation(
            taskId,
            TaskObservation(
                step = step,
                action = command.toToolCall().name,
                summary = result.message.take(MAX_OBSERVATION_CHARACTERS),
                ok = result.ok,
                verified = result.verified,
                code = result.code.name,
            ),
        )
        val resultDecision = stuckDetectors[taskId]?.recordResult(result)
        if (resultDecision?.isStuck == true) {
            finishFinal(taskId, resultDecision.message.orEmpty(), failed = true, onComplete)
            return
        }
        if (onToolResult != null) {
            onToolResult(result)
            return
        }
        val isAgentTask = taskSessionStore.snapshot().route == ROUTE_AGENT
        val needsNextDecision = isAgentTask || result.requiresFollowUp
        if (needsNextDecision && userRequest.isNotBlank() && step >= maxSteps) {
            stuckDetectors[taskId]?.stepBudgetDecision(step)
            finishFinal(taskId, result.message, failed = !result.ok, onComplete)
            return
        }
        if (!needsNextDecision || step >= maxSteps || userRequest.isBlank()) {
            val transitioned = if (result.ok) {
                taskSessionStore.complete(taskId, result.message)
            } else {
                taskSessionStore.fail(taskId, result.message)
            }
            if (!transitioned) return
            proceduralSkillLearner?.complete(taskId, successful = result.ok)
            modelDecisionCounts.remove(taskId)
            onComplete(AgentResult.Final(result.message))
            return
        }
        taskSessionStore.transition(taskId, TaskState.WAITING_FOR_MODEL)
        val groups = exposedToolGroupsByTask[taskId] ?: ToolGroup.entries.toSet()
        val prompt = if (isAgentTask) {
            promptBuilder.agentAfterTool(
                userRequest = userRequest,
                observations = taskSessionStore.snapshot().observations,
                remainingToolDecisions = (maxSteps - step).coerceAtLeast(0),
                exposedToolGroups = groups,
            )
        } else {
            promptBuilder.afterTool(userRequest, result)
        }
        generateModel(
            taskId,
            prompt,
            exposedToolCount = if (isAgentTask) promptBuilder.exposedToolCount(groups) else 0,
        ) { output ->
            if (taskSessionStore.isActive(taskId)) {
                handleModelOutput(taskId, userRequest, output, step + 1, onComplete)
            }
        }
    }

    private fun runSkill(
        taskId: Long,
        execution: SkillExecution,
        userRequest: String,
        onComplete: (AgentResult) -> Unit,
        result: ToolExecutionResult? = null,
    ) {
        if (!taskSessionStore.isActive(taskId)) return
        val deadlineDecision = stuckDetectors[taskId]?.deadlineDecision()
        if (deadlineDecision?.isStuck == true) {
            finishFinal(taskId, deadlineDecision.message.orEmpty(), failed = true, onComplete)
            return
        }
        when (val decision = skillEngine.next(execution, result)) {
            is SkillDecision.Call -> {
                val command = typedToolRegistry.command(decision.call)
                if (command == null) {
                    finishFinal(taskId, "Skill tạo ra tool call không hợp lệ.", failed = true, onComplete)
                    return
                }
                val continuation: (ToolExecutionResult, (AgentResult) -> Unit) -> Unit = { toolResult, completion ->
                    pendingSkillContinuations.remove(taskId)
                    runSkill(taskId, execution, userRequest, completion, toolResult)
                }
                pendingSkillContinuations[taskId] = continuation
                execute(
                    command,
                    userRequest = "",
                    taskId = taskId,
                    step = 1,
                    permissionAlreadyApproved = false,
                    confirmationAlreadyApproved = false,
                    onComplete = onComplete,
                    onToolResult = { toolResult -> continuation(toolResult, onComplete) },
                )
            }
            is SkillDecision.Wait -> {
                taskSessionStore.transition(taskId, TaskState.WAITING_FOR_TOOL)
                toolExecutionExecutor.execute {
                    try {
                        Thread.sleep(decision.millis)
                    } catch (_: InterruptedException) {
                        return@execute
                    }
                    runSkill(taskId, execution, userRequest, onComplete)
                }
            }
            is SkillDecision.NeedsModel -> {
                taskSessionStore.transition(taskId, TaskState.WAITING_FOR_MODEL)
                generateModel(taskId, decision.prompt, exposedToolCount = null) { output ->
                    if (taskSessionStore.isActive(taskId)) {
                        handleModelOutput(taskId, userRequest, output, step = 1, onComplete = onComplete)
                    }
                }
            }
            is SkillDecision.Failed -> finishFinal(taskId, decision.message, failed = true, onComplete)
            SkillDecision.Complete -> {
                val lastResult = execution.currentContext.lastResult
                finishFinal(
                    taskId,
                    lastResult?.message ?: "Skill đã hoàn tất.",
                    failed = lastResult != null && !lastResult.ok,
                    onComplete = onComplete,
                )
            }
        }
    }

    fun cancel(): Boolean {
        val taskId = taskSessionStore.snapshot().taskId
        val cancelled = taskSessionStore.cancel()
        if (cancelled && taskId != null) {
            runningTools.remove(taskId)?.cancel(true)
            skillExecutions.remove(taskId)
            pendingSkillContinuations.remove(taskId)
            stuckDetectors.remove(taskId)
            preparedOutbound.remove(taskId)?.let { outboundApprovalStore.cancel(it.token) }
            approvedOutbound.remove(taskId)
            proceduralSkillLearner?.complete(taskId, successful = false)
            modelDecisionCounts.remove(taskId)
            pendingDecisionSteps.remove(taskId)
            exposedToolGroupsByTask.remove(taskId)
            malformedRepairCounts.remove(taskId)
            repeatedActionRecoveryCounts.remove(taskId)
        }
        return cancelled
    }

    private fun generateModel(
        taskId: Long,
        prompt: String,
        exposedToolCount: Int?,
        onComplete: (String) -> Unit,
    ) {
        val decisionIndex = modelDecisionCounts.merge(taskId, 1) { previous, increment -> previous + increment }
        val boundedDecisionIndex = decisionIndex ?: 0
        if (taskSessionStore.snapshot().route == ROUTE_AGENT && boundedDecisionIndex > maxSteps) {
            onComplete(AGENT_DECISION_BUDGET_EXCEEDED)
            return
        }
        val instrumented = model as? InstrumentedAgentModel
        if (instrumented == null) {
            model.generate(prompt, onComplete)
        } else {
            instrumented.generateWithMetadata(
                prompt,
                ModelRequestMetadata(
                    promptCharacters = prompt.length,
                    exposedToolCount = exposedToolCount,
                    modelDecisionIndex = decisionIndex,
                ),
                onComplete,
            )
        }
    }

    fun close() {
        cancel()
        toolWorkerExecutor.shutdownNow()
        (toolExecutionExecutor as? ExecutorService)?.shutdownNow()
    }

    private fun finishFinal(
        taskId: Long,
        message: String,
        failed: Boolean = false,
        onComplete: (AgentResult) -> Unit,
    ) {
        skillExecutions.remove(taskId)
        pendingSkillContinuations.remove(taskId)
        stuckDetectors.remove(taskId)
        preparedOutbound.remove(taskId)?.let { outboundApprovalStore.cancel(it.token) }
        approvedOutbound.remove(taskId)
        proceduralSkillLearner?.complete(taskId, successful = !failed)
        modelDecisionCounts.remove(taskId)
        pendingDecisionSteps.remove(taskId)
        exposedToolGroupsByTask.remove(taskId)
        malformedRepairCounts.remove(taskId)
        repeatedActionRecoveryCounts.remove(taskId)
        val transitioned = if (failed) {
            taskSessionStore.fail(taskId, message)
        } else {
            taskSessionStore.complete(taskId, message)
        }
        if (transitioned) onComplete(AgentResult.Final(message))
    }

    private fun ToolCommand.isOutbound(): Boolean = this is ToolCommand.SendSms || this is ToolCommand.DialContact

    private fun routeName(route: RouteDecision): String = when (route) {
        is RouteDecision.Direct -> ROUTE_DIRECT
        is RouteDecision.Skill -> ROUTE_SKILL
        is RouteDecision.ModelTool -> ROUTE_MODEL_TOOL
        is RouteDecision.Agent -> ROUTE_AGENT
    }

    private fun maxTaskWallMs(route: RouteDecision): Long =
        if (route is RouteDecision.Agent) MAX_AGENT_TASK_WALL_MS else MAX_TASK_WALL_MS

    private fun recoverRepeatedAgentAction(
        taskId: Long,
        command: ToolCommand,
        userRequest: String,
        step: Int,
        onComplete: (AgentResult) -> Unit,
    ): Boolean {
        if (userRequest.isBlank() || taskSessionStore.snapshot().route != ROUTE_AGENT) return false
        val recoveries = repeatedActionRecoveryCounts.merge(taskId, 1) { previous, increment -> previous + increment } ?: 1
        if (recoveries > MAX_REPEATED_ACTION_RECOVERIES) return false
        val groups = exposedToolGroupsByTask[taskId] ?: ToolGroup.entries.toSet()
        taskSessionStore.transition(taskId, TaskState.WAITING_FOR_MODEL, "Recovering repeated agent action.")
        generateModel(
            taskId,
            promptBuilder.recoverRepeatedAction(
                userRequest = userRequest,
                repeatedAction = command.toToolCall().name,
                observations = taskSessionStore.snapshot().observations,
                exposedToolGroups = groups,
            ),
            promptBuilder.exposedToolCount(groups),
        ) { output ->
            if (taskSessionStore.isActive(taskId)) {
                handleModelOutput(taskId, userRequest, output, step, onComplete)
            }
        }
        return true
    }

    private fun looksLikeMalformedToolOutput(output: String): Boolean {
        val trimmed = output.trim()
        return trimmed.isBlank() || trimmed.startsWith("{") || trimmed.contains("\"tool\"")
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
        const val MAX_TASK_WALL_MS = 60_000L
        const val MAX_AGENT_TASK_WALL_MS = 120_000L
        private const val MAX_MALFORMED_REPAIRS = 1
        private const val MAX_REPEATED_ACTION_RECOVERIES = 1
        private const val MAX_OBSERVATION_CHARACTERS = 480
        private const val ROUTE_DIRECT = "DIRECT"
        private const val ROUTE_SKILL = "SKILL"
        private const val ROUTE_MODEL_TOOL = "MODEL_TOOL"
        private const val ROUTE_AGENT = "AGENT"
        private const val AGENT_DECISION_BUDGET_EXCEEDED = "__AB_AGENT_DECISION_BUDGET_EXCEEDED__"
    }
}
