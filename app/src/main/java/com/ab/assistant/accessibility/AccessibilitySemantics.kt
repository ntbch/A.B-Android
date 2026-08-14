package com.ab.assistant.accessibility

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicLong

data class SemanticNode(
    val ref: String,
    val parentRef: String?,
    val role: String,
    val text: String?,
    val contentDescription: String?,
    val resourceId: String?,
    val clickable: Boolean,
    val enabled: Boolean,
    val bounds: Rect?,
)

data class SemanticUiSnapshot(
    val snapshotId: Long,
    val packageName: String,
    val nodes: List<SemanticNode>,
    val truncated: Boolean = false,
)

data class UiSelector(
    val resourceId: String? = null,
    val role: String? = null,
    val contentDescription: String? = null,
    val text: String? = null,
    val clickableOnly: Boolean = false,
)

data class SemanticRef(
    val snapshotId: Long,
    val ref: String,
)

sealed interface UiAction {
    data class Tap(val ref: SemanticRef) : UiAction
    data class SetText(val ref: SemanticRef, val text: String) : UiAction
    data class Scroll(val ref: SemanticRef, val forward: Boolean) : UiAction
}

data class UiActionResult(val dispatched: Boolean, val error: String? = null)

object UiActionPostcondition {
    /** A newer bounded snapshot is the generic minimum proof that a UI action had a visible effect. */
    fun screenChanged(beforeSnapshotId: Long, after: SemanticUiSnapshot?): Boolean =
        after?.snapshotId?.let { it > beforeSnapshotId } == true
}

/** Executes only refs from current snapshot; stale refs never reach Android actions. */
class UiActionExecutor {
    fun execute(snapshot: SemanticUiSnapshot?, root: AccessibilityNodeInfo?, action: UiAction): UiActionResult {
        val current = snapshot ?: return UiActionResult(false, "No active UI snapshot.")
        val ref = when (action) {
            is UiAction.Tap -> action.ref
            is UiAction.SetText -> action.ref
            is UiAction.Scroll -> action.ref
        }
        if (ref.snapshotId != current.snapshotId) return UiActionResult(false, "Stale UI reference.")
        val node = root?.findByRef(ref.ref) ?: return UiActionResult(false, "UI node is no longer available.")
        return when (action) {
            is UiAction.Tap -> UiActionResult(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            is UiAction.SetText -> UiActionResult(
                node.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text) },
                ),
            )
            is UiAction.Scroll -> UiActionResult(
                node.performAction(if (action.forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD),
            )
        }.let { result -> if (result.dispatched) result else UiActionResult(false, "Android rejected UI action.") }
    }

    private fun AccessibilityNodeInfo.findByRef(targetRef: String): AccessibilityNodeInfo? {
        var index = 0
        fun visit(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
            if (index >= MAX_NODES || depth > MAX_DEPTH) return null
            val ref = "@e${++index}"
            if (ref == targetRef) return node
            for (childIndex in 0 until node.childCount) {
                if (index >= MAX_NODES) return null
                node.getChild(childIndex)?.let { child -> visit(child, depth + 1)?.let { return it } }
            }
            return null
        }
        return visit(this, depth = 0)
    }

    private companion object {
        const val MAX_NODES = 256
        const val MAX_DEPTH = 24
    }
}

sealed interface UiPostcondition {
    data class NodeExists(val selector: UiSelector) : UiPostcondition
    data class NodeTextContains(val selector: UiSelector, val expected: String) : UiPostcondition
    data class PackageIs(val packageName: String) : UiPostcondition
}

class SemanticUiResolver {
    fun find(snapshot: SemanticUiSnapshot, selector: UiSelector): SemanticNode? {
        val candidates = snapshot.nodes.filter { node ->
            (!selector.clickableOnly || node.clickable) && node.enabled
        }
        val byResource = selector.resourceId?.let { value -> candidates.filter { it.resourceId == value } }.orEmpty()
        if (byResource.isNotEmpty()) return byResource.first()

        val byRole = selector.role?.let { value ->
            candidates.filter { it.role.equals(value, ignoreCase = true) }
        }.orEmpty()
        if (byRole.isNotEmpty() && selector.contentDescription == null && selector.text == null) {
            return byRole.first()
        }

        val byDescription = selector.contentDescription?.let { value ->
            (if (byRole.isNotEmpty()) byRole else candidates)
                .filter { it.contentDescription.equals(value, ignoreCase = true) }
        }.orEmpty()
        if (byDescription.isNotEmpty()) return byDescription.first()

        return selector.text?.let { value ->
            (if (byDescription.isNotEmpty()) byDescription else if (byRole.isNotEmpty()) byRole else candidates)
                .firstOrNull { it.text?.contains(value, ignoreCase = true) == true }
        }
    }

    fun resolve(snapshot: SemanticUiSnapshot, ref: SemanticRef): SemanticNode? =
        if (snapshot.snapshotId != ref.snapshotId) null else snapshot.nodes.firstOrNull { it.ref == ref.ref }
}

class UiPostconditionVerifier(
    private val resolver: SemanticUiResolver = SemanticUiResolver(),
) {
    fun verify(snapshot: SemanticUiSnapshot, postcondition: UiPostcondition): Boolean = when (postcondition) {
        is UiPostcondition.NodeExists -> resolver.find(snapshot, postcondition.selector) != null
        is UiPostcondition.NodeTextContains -> resolver.find(snapshot, postcondition.selector)
            ?.text?.contains(postcondition.expected, ignoreCase = true) == true
        is UiPostcondition.PackageIs -> snapshot.packageName == postcondition.packageName
    }
}

class AccessibilitySnapshotCollector(
    private val maxNodes: Int = 256,
    private val maxDepth: Int = 24,
) {
    private val nextSnapshotId = AtomicLong(0)

    init {
        require(maxNodes > 0) { "maxNodes must be positive" }
        require(maxDepth > 0) { "maxDepth must be positive" }
    }

    fun collect(root: AccessibilityNodeInfo, packageName: String): SemanticUiSnapshot {
        val nodes = mutableListOf<SemanticNode>()
        var nextRef = 0
        var truncated = false

        fun visit(node: AccessibilityNodeInfo, parentRef: String?, depth: Int) {
            if (nodes.size >= maxNodes || depth > maxDepth) {
                truncated = true
                return
            }
            val ref = "@e${++nextRef}"
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            nodes += SemanticNode(
                ref = ref,
                parentRef = parentRef,
                role = roleOf(node.className?.toString()),
                text = node.text?.toString()?.takeIf { it.isNotBlank() },
                contentDescription = node.contentDescription?.toString()?.takeIf { it.isNotBlank() },
                resourceId = node.viewIdResourceName,
                clickable = node.isClickable,
                enabled = node.isEnabled,
                bounds = bounds,
            )
            for (index in 0 until node.childCount) {
                if (nodes.size >= maxNodes) {
                    truncated = true
                    break
                }
                node.getChild(index)?.let { child -> visit(child, ref, depth + 1) }
            }
        }

        visit(root, null, depth = 0)
        return SemanticUiSnapshot(nextSnapshotId.incrementAndGet(), packageName, nodes, truncated)
    }

    private fun roleOf(className: String?): String {
        val simpleName = className?.substringAfterLast('.')?.lowercase().orEmpty()
        return when {
            "button" in simpleName -> "button"
            "edittext" in simpleName -> "text_field"
            "checkbox" in simpleName -> "checkbox"
            "image" in simpleName -> "image"
            "scroll" in simpleName -> "scroll_container"
            "textview" in simpleName -> "text"
            else -> simpleName.ifBlank { "node" }
        }
    }
}
