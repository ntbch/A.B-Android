package com.ab.assistant

class NativeBridge {

    external fun hello(): String

    external fun mnnVersion(): String

    external fun loadModel(configPath: String, cachePath: String): String

    external fun loadModelWithBackend(configPath: String, cachePath: String, backend: String): String

    external fun generate(prompt: String, maxNewTokens: Int): String

    external fun generateWithMetrics(prompt: String, maxNewTokens: Int): String

    external fun unloadModel()

    companion object {
        init {
            System.loadLibrary("ab_native")
        }
    }
}
