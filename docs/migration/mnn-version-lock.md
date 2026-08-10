# MNN version lock

The Android app consumes the MNN source through the `third_party/MNN` Git submodule. The repository tree pins the submodule at commit `d407447ed56c4121a11ccbd266dc184ca1ead0c2`; this is the source revision used by the current build.

The checked-in headers report MNN `3.6.1` (`MNN_VERSION_MAJOR=3`, `MNN_VERSION_MINOR=6`, `MNN_VERSION_PATCH=1`). The submodule URL is `https://github.com/alibaba/MNN.git` from `.gitmodules`.

The Android CMake build enables OpenCL, Vulkan, and ARM82. Runtime selection is now CPU first, then OpenCL, then Vulkan fallback, based on the final POCO warm-repeat evidence: CPU produced four valid model-generated SMS confirmations in 7.191–8.149 seconds, while OpenCL remained valid but materially slower. The explicit benchmark entrypoint evaluates each backend independently and records actual backend, fallback, latency, output status, and native generation metrics in `InferenceMetrics`. Vulkan loaded but hit the bounded generation timeout; CPU thermal/battery qualification remains a release gate.

MNN temporary/cache directories are request-scoped (`mnn-cache-opencl_vulkan_cpu` for the default fallback sequence, plus `mnn-cache-opencl`, `mnn-cache-vulkan`, and `mnn-cache-cpu` for explicit runs) so one backend cannot reuse or overwrite another backend's compiled artifacts.

The native bridge sets `max_all_tokens=2048`, `max_new_tokens=64`, `sampler_type=greedy`, `reuse_kv=true`, and `jinja.context.enable_thinking=false`; Kotlin caps a single model decision at 32 new tokens. The structured path renders the model chat template before tokenization, matching MNN's `response(string)` semantics rather than bypassing the template.

The app-level prefix cache stores the rendered prompt token IDs, requires a common prefix of at least 32 tokens, erases only the stale suffix with `Llm::eraseHistory`, and feeds the new suffix into the resident MNN context. It is intentionally separate from MNN's disk-backed `prompt_cache`/`setPrefixCacheFile` feature because the Android boundary supplies a rendered string rather than `ChatMessages`; native metrics expose `promptCacheHit` and `cachedPromptTokens` for the bounded probe.
