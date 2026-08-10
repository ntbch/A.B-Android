package com.ab.assistant.agent

import com.ab.assistant.tools.JsonObject
import com.ab.assistant.tools.ToolCall
import com.ab.assistant.tools.toToolCall
import java.text.Normalizer
import java.util.Locale

enum class ToolGroup { DEVICE, INFORMATION, COMMUNICATION }

sealed interface RouteDecision {
    data class Direct(val call: ToolCall) : RouteDecision
    data class Skill(val skillId: String, val arguments: JsonObject) : RouteDecision
    data class ModelTool(val exposedToolGroups: Set<ToolGroup>) : RouteDecision
    data class Agent(val reason: String) : RouteDecision
}

class PipelineRouter(
    private val parser: (String) -> com.ab.assistant.tools.ToolCommand? = UserCommandParser::parse,
    private val skillEngine: SkillEngine = SkillEngine(),
) {
    fun route(request: String): RouteDecision {
        skillEngine.match(request)?.let { return RouteDecision.Skill(it.skill.id, it.arguments) }
        parser(request.trim())?.let { return RouteDecision.Direct(it.toToolCall()) }
        return RouteDecision.ModelTool(exposedToolGroups(request))
    }

    private fun exposedToolGroups(request: String): Set<ToolGroup> {
        val normalized = normalize(request)
        return when {
            normalized.startsWith("nhan ") ||
                containsAny(normalized, "nhan tin", "tin nhan", "gui tin", "sms", "goi", "call") ->
                setOf(ToolGroup.COMMUNICATION)
            containsAny(normalized, "battery", "pin", "device status", "trang thai thiet bi") -> setOf(ToolGroup.DEVICE)
            containsAny(normalized, "tim kiem", "search", "thong bao", "notification", "lien he", "danh ba") -> setOf(ToolGroup.INFORMATION)
            else -> ToolGroup.entries.toSet()
        }
    }

    private fun containsAny(value: String, vararg terms: String): Boolean = terms.any { term ->
        Regex("\\b${Regex.escape(term)}\\b").containsMatchIn(value)
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace("\\p{M}+".toRegex(), "")
        .lowercase(Locale.ROOT)
        .replace('\u0111', 'd')
        .trim()
}
