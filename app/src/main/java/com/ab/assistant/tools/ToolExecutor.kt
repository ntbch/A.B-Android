package com.ab.assistant.tools

interface ToolExecutor {
    fun requiredPermission(command: ToolCommand): String?
    fun requiredPermissions(command: ToolCommand): List<String> = listOfNotNull(requiredPermission(command))
    fun isAvailable(command: ToolCommand): Boolean
    fun unavailableMessage(command: ToolCommand): String
    fun execute(command: ToolCommand): ToolExecutionResult
    fun requiresConfirmation(command: ToolCommand): Boolean = false

    fun confirmationMessage(command: ToolCommand): String = "XÃ¡c nháº­n hÃ nh Ä‘á»™ng nÃ y?"

    fun permissionMessage(command: ToolCommand, permission: String): String =
        "Cần cấp quyền để thực hiện yêu cầu này."
}
