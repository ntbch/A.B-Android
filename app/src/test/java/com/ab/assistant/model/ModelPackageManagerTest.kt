package com.ab.assistant.model

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPackageManagerTest {
    @Test
    fun downloadVerifiesThenAtomicallyPublishesCompleteBundle() {
        val root = Files.createTempDirectory("ab-model-manager").toFile()
        val payloads = ModelFiles.requiredFileNames.associateWith { name -> "content-$name".toByteArray() }
        val manifest = manifest(payloads)
        val manager = ModelPackageManager(listOf(root))

        val result = manager.downloadAndInstall(manifest, downloader(payloads))

        assertEquals(ModelPackageState.READY, result.state)
        assertEquals(manifest.version, File(ModelFiles.directory(root), ".ab-model-version").readText())
        assertEquals(emptyList<String>(), ModelFiles.missingFiles(root))
        assertFalse(File(ModelFiles.directory(root).parentFile, ".qwen3.5-2b.partial").exists())
    }

    @Test
    fun hashMismatchNeverPublishesPartialBundle() {
        val root = Files.createTempDirectory("ab-model-manager").toFile()
        val payloads = ModelFiles.requiredFileNames.associateWith { name -> "content-$name".toByteArray() }
        val manager = ModelPackageManager(listOf(root))

        val result = manager.downloadAndInstall(manifest(payloads), downloader(payloads + ("llm.mnn" to "tampered".toByteArray())))

        assertEquals(ModelPackageState.BROKEN, result.state)
        assertFalse(ModelFiles.directory(root).exists())
        assertTrue(File(ModelFiles.directory(root).parentFile, ".qwen3.5-2b.partial").exists())
    }

    private fun manifest(payloads: Map<String, ByteArray>): ModelPackageManifest = ModelPackageManifest(
        version = "qwen-1",
        files = ModelFiles.requiredFileNames.map { name ->
            val payload = requireNotNull(payloads[name])
            ModelPackageFile(name, payload.size.toLong(), sha256(payload))
        },
    )

    private fun downloader(payloads: Map<String, ByteArray>) = ModelPackageDownloader { entry, destination, resumeOffset ->
        val payload = requireNotNull(payloads[entry.name])
        if (resumeOffset !in 0..payload.size.toLong()) return@ModelPackageDownloader false
        destination.parentFile?.mkdirs()
        destination.outputStream().buffered().use { output -> output.write(payload, resumeOffset.toInt(), payload.size - resumeOffset.toInt()) }
        true
    }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { byte -> "%02x".format(byte) }
}
