package com.ab.assistant.model

/** Pinned official Qwen 3.5 2B MNN bundle accepted by this application build. */
object AbModelRelease {
    val manifest = ModelPackageManifest(
        version = "qwen3.5-2b-mnn-official-2026-08-11",
        files = listOf(
            ModelPackageFile("config.json", 684, "1502e30865706c124899aa992ceb9c4fab8427bb87405e80962b84e5c587d8b7"),
            ModelPackageFile("llm_config.json", 8_726, "30d1284ec6aa7eab150f282c2c5a9090a9321682163316d9ed4086a15c1c55ab"),
            ModelPackageFile("llm.mnn", 2_148_136, "23df98f8b341b277365e0bbca025c1d192939e3d32d7f79776352c6f32e77960"),
            ModelPackageFile("llm.mnn.weight", 1_176_647_702, "c93f71a2dbecf9328782bd38861656d8faa82e95e7f99607350074768a482054"),
            ModelPackageFile("tokenizer.txt", 6_961_395, "e45a536a5351abd34419f39ea7b695fa6a614f078523d24024e612698723225a"),
            ModelPackageFile("visual.mnn", 488_096, "88fc40a7b676e90eb2cb86d854db15cb90b9eb1f34087ab0f48c5e43572c8dac"),
            ModelPackageFile("visual.mnn.weight", 195_587_264, "8f90e106f5b9ae9a939faed240305cfdd5c6740ae91d3fc418a990bee0cce36b"),
        ),
    )
}
