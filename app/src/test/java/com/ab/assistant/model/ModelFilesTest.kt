package com.ab.assistant.model

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFilesTest {

    @Test
    fun missingFiles_requiresMnnTextModelBundle() {
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
            .forEach { Files.createFile(modelDir.toPath().resolve(it)) }

        assertEquals(emptyList<String>(), ModelFiles.missingFiles(filesDir))
    }
}
