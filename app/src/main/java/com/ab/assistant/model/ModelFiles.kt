package com.ab.assistant.model

import java.io.File

object ModelFiles {
    val requiredFileNames = listOf(
        "config.json",
        "llm_config.json",
        "llm.mnn",
        "llm.mnn.weight",
        "tokenizer.txt",
        "visual.mnn",
        "visual.mnn.weight",
    )

    fun directory(filesDir: File) = File(filesDir, "models/qwen3.5-2b")

    fun configFile(filesDir: File) = File(directory(filesDir), "config.json")

    fun missingFiles(filesDir: File): List<String> = requiredFileNames.filterNot { fileName ->
        File(directory(filesDir), fileName).let { file -> file.isFile && file.length() > 0L }
    }
}
