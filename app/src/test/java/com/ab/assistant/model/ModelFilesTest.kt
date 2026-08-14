package com.ab.assistant.model

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFilesTest {

    @Test
    fun missingFiles_requiresOnlyTextModelBundle() {
        val filesDir = Files.createTempDirectory("ab-model-test").toFile()
        val modelDir = ModelFiles.directory(filesDir)
        assertTrue(modelDir.mkdirs())

        listOf(
            "config.json",
            "llm_config.json",
            "llm.mnn",
            "llm.mnn.weight",
            "tokenizer.txt",
            "visual.mnn",
            "visual.mnn.weight",
        )
            .forEach { Files.write(modelDir.toPath().resolve(it), "test-$it".toByteArray()) }

        assertEquals(emptyList<String>(), ModelFiles.missingFiles(filesDir))
    }

    @Test
    fun emptyRequiredFileIsNotConsideredLoadable() {
        val filesDir = Files.createTempDirectory("ab-empty-model-test").toFile()
        val modelDir = ModelFiles.directory(filesDir)
        assertTrue(modelDir.mkdirs())
        ModelFiles.requiredFileNames.forEach { Files.createFile(modelDir.toPath().resolve(it)) }

        assertEquals(ModelFiles.requiredFileNames, ModelFiles.missingFiles(filesDir))
    }
}
