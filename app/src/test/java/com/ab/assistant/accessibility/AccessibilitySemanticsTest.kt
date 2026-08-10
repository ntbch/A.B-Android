package com.ab.assistant.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilitySemanticsTest {
    private val resolver = SemanticUiResolver()

    @Test
    fun selectorUsesStableResourceIdBeforeTextAndRejectsStaleRefs() {
        val snapshot = snapshot(
            id = 2,
            packageName = "com.example.video",
            nodes = listOf(
                node("@e1", role = "button", text = "Play", resourceId = "com.example:id/play"),
                node("@e2", role = "button", text = "Play", resourceId = null),
            ),
        )
        val resolved = resolver.find(snapshot, UiSelector(resourceId = "com.example:id/play", text = "wrong"))

        assertEquals("@e1", resolved?.ref)
        assertEquals(null, resolver.resolve(snapshot, SemanticRef(1, "@e1")))
        assertEquals("@e1", resolver.resolve(snapshot, SemanticRef(2, "@e1"))?.ref)
    }

    @Test
    fun postconditionsCoverRepresentativeScreens() {
        val home = snapshot(
            3,
            "com.example.launcher",
            listOf(node("@e1", role = "button", contentDescription = "Settings")),
        )
        val message = snapshot(
            4,
            "com.example.messages",
            listOf(node("@e1", role = "text", text = "Message sent")),
        )
        val video = snapshot(
            5,
            "com.example.video",
            listOf(node("@e1", role = "button", text = "Pause", clickable = true)),
        )
        val verifier = UiPostconditionVerifier(resolver)

        assertTrue(verifier.verify(home, UiPostcondition.NodeExists(UiSelector(contentDescription = "Settings"))))
        assertTrue(verifier.verify(message, UiPostcondition.NodeTextContains(UiSelector(role = "text"), "sent")))
        assertTrue(verifier.verify(video, UiPostcondition.NodeExists(UiSelector(text = "Pause", clickableOnly = true))))
        assertFalse(verifier.verify(video, UiPostcondition.PackageIs("com.example.messages")))
    }

    @Test
    fun snapshotBoundsMustBePositive() {
        var threw = false
        try {
            AccessibilitySnapshotCollector(maxNodes = 0)
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    private fun snapshot(id: Long, packageName: String, nodes: List<SemanticNode>) =
        SemanticUiSnapshot(id, packageName, nodes)

    private fun node(
        ref: String,
        role: String,
        text: String? = null,
        contentDescription: String? = null,
        resourceId: String? = null,
        clickable: Boolean = false,
    ) = SemanticNode(ref, null, role, text, contentDescription, resourceId, clickable, enabled = true, bounds = null)
}
