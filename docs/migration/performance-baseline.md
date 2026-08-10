# A.B pre-v2 performance baseline

## Current evidence

The baseline unit-test command was attempted on 2026-08-09:

```text
.\gradlew.bat test
FAIL: plugin com.android.application:9.3.0 could not be resolved
```

The first sandboxed attempt failed at repository resolution before compilation. The same command passed after repository access was allowed, and `.\gradlew.bat assembleDebug` also passed. A real-device POCO X6 Pro baseline was captured below using ADB.

## Existing latency-relevant behavior

- `AbApplication` starts one `MnnModelRuntime` load and fans the result out to waiting callbacks.
- `native_bridge.cpp` now tries CPU first, then OpenCL, then Vulkan. Explicit backend requests use backend-scoped MNN caches; the generic fallback sequence uses one sequence cache because the native loader chooses the first backend that loads successfully. This order was promoted after the final POCO recheck showed CPU as the only measured warm profile below the 10-second model-route investigation threshold with repeated valid output.
- The native `Llm*` is process-resident until `MnnModelRuntime.close()` calls `unloadModel()`.
- The native `Llm*` remains resident during a process session. A generation allows at most 32 new tokens on the Kotlin runtime boundary; native configuration keeps a 64-token upper bound, `max_all_tokens=2048`, and greedy sampling for deterministic tool JSON.
- `MnnModelRuntime` serializes generation on one executor and times out after 35 seconds.
- Explicit commands parsed by `UserCommandParser` do not call Qwen. Model-routed requests use `PromptBuilder` and `ToolCommandParser`.
- Model-routed requests expose only the relevant device, information, or communication tool schemas when the router can classify the intent; ambiguous requests expose the full current catalog.
- Native generation is requested with at most 32 new tokens at the Kotlin runtime boundary, with the MNN chat template rendered before tokenization so `enable_thinking=false` takes effect; the terminal tool path does not perform a follow-up model call when `requiresFollowUp=false`.
- `MnnModelRuntime` records prompt character count, exposed tool-definition count, the per-task model-decision index, prompt tokens, prefill, generated tokens, decode throughput, and prompt-prefix cache reuse. MNN does not populate `ttfa_us` on this device, so TTFT remains `null` rather than being guessed.

## Instrumentation added by Upgrade 1

`MnnModelRuntime` now emits one JSON `InferenceMetrics` record for each completed model-load attempt and generation request through Android log tag `MnnModelRuntime`.

Measured fields:

- request ID;
- prompt character count and tokenizer prompt tokens;
- exposed tool-definition count;
- model decision index within the task;
- requested/actual backend and known fallback reason for the OpenCL/Vulkan/CPU sequence;
- model-load duration;
- native prefill duration, generated tokens, decode throughput, and optional TTFT;
- generation wrapper/native duration;
- total request duration;
- first-generation cold-start marker;
- prompt-prefix cache hit and reused token count.

The structured native protocol supplies prompt/token and prefill/decode values. `ttftMs` remains `null` because this MNN build/device reports no positive `LlmContext.ttfa_us`; the benchmark does not substitute a wall-clock estimate.

## Fixed corpus for the POCO run

```text
bật đèn pin
mở YouTube
âm lượng 30 phần trăm
đặt hẹn giờ 5 phút
search thời tiết Hà Nội hôm nay
nhắn Nam là 10 phút nữa tới
```

For each warm/cold run, capture the JSON metrics plus whether the request took the direct parser path or model path. The direct-path expectation is zero Qwen calls; the model path is expected for paraphrases that the conservative parser does not recognize.

## POCO X6 Pro measurement (2026-08-09)

Device: POCO X6 Pro, model `2311DRK48`, product `duchamp_global`, A.B debug APK, actual backend `OPENCL`.

The first observed post-install warm-up run measured `modelLoadMs=8167` and `totalMs=8183`; cold UI readiness was approximately 11,310 ms. After the MNN cache existed, a force-stop/restart measured `modelLoadMs=1443`, `totalMs=1450`, and UI readiness 3,838 ms.

| Corpus route | Input verified | UI wall time | Result |
|---|---:|---:|---|
| flashlight direct | yes | 2,880 ms | `Flashlight turned ON.` |
| open app | yes | 1,941 ms | External app foreground detected |
| volume direct | yes | 2,468 ms | Set music volume to 30% |
| timer direct | yes | 1,933 ms | No clock handler on this device |
| web search direct | yes | 2,761 ms | Search returned three entries |
| natural message route | yes | 38,044 ms | Local generation timed out |

The direct-route UI timings include ADB input, UI-hierarchy polling, and Android activity work; they are harness wall times, not pure executor latency. The model-route record is native-grounded: `generationMs=35000`, `totalMs=35000`, `backendActual=OPENCL`, with the existing 35-second timeout. No model decision completed for that request, so prompt/token throughput fields remain unavailable rather than guessed.

## POCO X6 Pro upgrade-v2 recheck (2026-08-11)

Device: `2311DRK48`, product `duchamp_global`, Android SDK `36`, serial `Y9ZXD6OZ75KBLBTO`, debug APK, actual backend `OPENCL`. The run used `scripts/benchmark-poco.ps1` over ADB after replacing the slow per-key-event prompt reset with one batched ADB key-event call. The elapsed values below include app launch, prompt entry, UI polling, and the configured wait, so they are harness wall times rather than executor-only latency.

| Corpus route | Wall time | Observed result | Model generation |
|---|---:|---|---:|
| flashlight direct | 4,143 ms | `Flashlight turned ON.` | 0 |
| open app (`YouTube`) | 4,184 ms | External microG battery-optimization dialog appeared on this device; app-open result is environment-blocked | 0 |
| volume direct | 4,122 ms | `Đã đặt âm lượng music thành 30%.` | 0 |
| timer direct | 4,112 ms | `Không thể mở ứng dụng Đồng hồ.`; no `ACTION_SET_TIMER` handler is installed | 0 |
| web search direct | 4,147 ms | Three bounded search results with stable source IDs/URLs | 0 |
| natural message/model route | 32,124 ms | Model decision completed; dedicated no-restore run showed SMS confirmation, then the harness cancelled it | 29,844 ms |

The direct cases loaded the resident model on each force-stop, but did not make a model decision. Their observed cold `modelLoadMs` values ranged from 1,332–2,230 ms. The message route recorded `coldStart=true`, `backendActual=OPENCL`, `modelLoadMs=1,532 ms`, `generationMs=35,000 ms`, `totalMs=35,000 ms`, `promptCharacters=1,138`, `exposedToolCount=13`, and `modelDecisionIndex=1`; the timeout is therefore a measured current-device limitation, not an inferred success. The harness restored the flashlight and observed `Flashlight turned OFF.` after the run.

The full-schema/64-token values in the preceding paragraph and the first warm profile were historical pre-optimization measurements. The final APK uses the communication-only prompt and 32-token Kotlin-side cap described below; those final values supersede the earlier timeout profile.

The warm-model profile then kept the process alive for two consecutive model requests. The first request timed out at 35,000 ms with `modelLoadMs=1,462 ms`; the second returned `ERROR: The local model is still finishing the previous request.` after 42,115 ms and emitted no generation metric. This confirms that the native generation is still occupying the serialized runtime after the timeout; the runtime does not start a concurrent unsafe generation. After this profile, `dumpsys meminfo` reported `TOTAL PSS=289,066 kB` and `Native Heap=173,740 kB`, while `dumpsys thermalservice` reported thermal status `0`, CPU/NPU about `45.7°C`, skin `41.4°C`, and battery `37.5°C` (device battery level `26%`). These are post-run snapshots, not a long-duration thermal or battery-drain study.

After the communication-scoping router fix, a rebuilt APK repeated the natural message case at 37,122 ms wall time. The native record remained `generationMs=35,000 ms` and timed out, but the prompt was reduced from `promptCharacters=1,138` and `exposedToolCount=13` to `promptCharacters=500` and `exposedToolCount=2`; `modelDecisionIndex=1` and `backendActual=OPENCL` were unchanged. The optimization is therefore verified in instrumentation, while this POCO/model combination still does not complete the model decision within the runtime limit.

The final rebuilt APK also uses a 32-token Kotlin-side generation request cap. A cold rerun completed the communication model decision in `generationMs=25,834 ms`, `totalMs=25,838 ms`, with the same 500-character/2-schema prompt and produced the expected SMS confirmation UI; the harness then pressed `HỦY`, so no SMS was sent. The two-request warm profile completed both requests without a timeout: warm-1 was `generationMs=29,926 ms`, `coldStart=true`, and warm-2 was `generationMs=29,211 ms`, `coldStart=false` with `modelLoadMs=null`; both used `exposedToolCount=2`. This is materially below the former 35-second timeout, but still a roughly 29-second warm model route rather than a low-latency path.

The final post-run snapshot reported `TOTAL PSS=268,417 kB`, battery level `27%`, thermal status `0`, CPU/NPU about `47.8°C`, skin `43.4°C`, and battery `38.3°C`; these values remained below the device-reported hot-throttling thresholds in the snapshot. This is a bounded smoke snapshot, not a long-duration thermal/battery qualification.

## Backend comparison (historical pre-cache-scoped run, 2026-08-11)

The new `scripts/benchmark-backends-poco.ps1` entrypoint loaded the same pinned model on each backend, then the model-route harness repeated the same 500-character/2-schema communication prompt.

| Backend | Cold load | Model generation | Observed outcome | Selection |
|---|---:|---:|---|---|
| OpenCL | 1,764 ms | 25,834–29,844 ms | Valid SMS confirmation path; no send without user approval | Default |
| CPU | 1,417 ms | 9,907–10,346 ms | Repeated `444444...` final output; invalid tool-call path | Fallback only |
| Vulkan | 20,416–21,195 ms | 35,084 ms | Timed out at the runtime limit | Not selected |

CPU was faster on raw generation, and the post-template/greedy path removed the earlier CPU malformed-output observation. Vulkan is buildable and loadable on this POCO, but is not competitive for this model path.

This is a real-device benchmark recheck, not a full release acceptance pass. The timer limitation, the external microG dialog, the selected system assistant still being Google, Accessibility not enabled, and the default wake-word detector remaining unavailable are recorded as open device gates.

## Backend comparison (final cache-scoped run, 2026-08-11)

## Final structured-generation and prefix-cache run (2026-08-11)

## Final APK targeted CPU recheck (2026-08-11)

The final-APK targeted recheck used `-Backend CPU -WarmModel -WarmRepeatCount 2` on the same POCO. Both runs produced the valid SMS confirmation and the harness canceled both (`2/2`, no outbound commit). Run 1 was `coldStart=true`, `modelLoadMs=1,617 ms`, `prefillMs=8,043 ms`, and `generationMs=10,832 ms`; run 2 was `coldStart=false`, `prefillMs=6,247 ms`, and `generationMs=8,675 ms`. The bounded before/after snapshot reported thermal status `0`, battery `31%`, CPU temperature `49.5 °C` after the run, and skin temperature `44.5 °C` after the run. A separate targeted cold model route completed in `generationMs=10,568 ms` with the same cancellation safety result. A later full-corpus attempt was not counted as a pass because its `web-route` case exceeded the 180-second harness timeout before model cases; the targeted model reruns above are the authoritative final-APK model evidence.

After adding per-command ADB and UI-dump watchdogs (`-AdbTimeoutSeconds 10 -UiDumpTimeoutSeconds 4`), the full corpus completed on the POCO in `201.8 s`. Flashlight and volume returned successful direct results; the timer returned the truthful `Không thể mở ứng dụng Đồng hồ.` limitation; web search returned three bounded sources; and both direct/model SMS confirmations were canceled with no outbound commit. The open-app case reached the microG GmsCore battery-optimization prompt (`Để microG GmsCore hoạt động ổn định...`) rather than a verified YouTube postcondition, so it remains pending. The final snapshot moved from PSS/swap `285,039/128 KB` and CPU/skin/battery temperatures `48.6/43.6/39.5 °C` to `281,827/200 KB` and `50.1/45.4/39.9 °C`; battery remained `32%` and thermal status remained `0`.

A targeted open-app rerun reproduced the same microG battery-optimization prompt in `10,270 ms`; the harness then restored A.B/flashlight state. This is an environment limitation, not evidence that YouTube launched.

The APK rebuilt after per-tool timeout/cancellation enforcement passed a targeted model-route smoke on the POCO: valid SMS confirmation, cancellation, and no outbound commit. The final native record used CPU with `generationMs=12,568 ms`; the bounded after snapshot reported thermal status `0`. Two UI-dump watchdog warnings occurred while the model confirmation was busy, and the harness still completed safely.

## Benchmark-only schema-size sweep (2026-08-11)

The debug APK exposes a benchmark-only intent path that sends the model an inert prompt and never passes its output to `AgentCore`, `ToolCommandParser`, or `ToolRegistry`. The 16-definition profile includes three explicitly reserved benchmark placeholders because the production registry currently has 13 executable tools; these placeholders cannot execute.

All three cold runs used OpenCL on the real POCO and the same request. Values include the 35-second Kotlin bound where applicable:

| Inert schema definitions | Prompt chars | Prompt tokens | Prefill | Generation | Decode |
|---:|---:|---:|---:|---:|---:|
| 4 | 428 | 121 | 15,628 ms | 18,937 ms | 2.885 tok/s |
| 8 | 655 | 190 | 20,925 ms | 23,823 ms | 2.582 tok/s |
| 16 | 1,161 | unavailable before timeout | unavailable | 35,000 ms timeout | unavailable |

The sweep confirms prompt/schema growth is a dominant prefill cost on this device. The 16-definition timeout is a benchmark observation, not a production tool-call failure, because the inert path is intentionally non-executable.

The final APK renders the MNN chat template before the structured token path, passes `enable_thinking=false`, uses greedy sampling, and reuses a common rendered-token prefix while the resident model remains loaded. This supersedes the preceding pre-template/cache measurements for model correctness.

| Backend | Cold load | Prompt tokens | Prefill | Generation | Decode | Observed result |
|---|---:|---:|---:|---:|---:|---|
| OpenCL | 3,235 ms | 124 | 19,666 ms | 29,337 ms | 2.532 tok/s | Valid SMS confirmation; no commit was performed |
| CPU | 1,653 ms | 124 | 7,168 ms | 9,717 ms | 11.532 tok/s | Valid SMS confirmation; no commit was performed |
| Vulkan | 22,642 ms | unavailable before Kotlin timeout | unavailable | 35,007 ms timeout | unavailable | Bounded timeout |

The former default OpenCL route also produced a valid SMS confirmation after chat-template rendering (`modelLoadMs=1,710 ms`, `promptTokens=124`, `prefillMs=18,019 ms`, `generatedTokens=24`, `generationMs=27,347 ms`). The native output no longer contained the earlier `<think>`/fenced malformed payload for this request. CPU is now the default on the measured profile: four warm-repeat runs across two processes all produced the same valid SMS confirmation in `7,191–8,149 ms`; long-duration thermal/battery qualification remains open.

The latest same-process communication prefix probe used two real SMS paraphrases. Lượt 1 produced a valid SMS confirmation in `generationMs=24,524 ms` with `promptTokens=125` and `prefillMs=17,758 ms`; the harness verified the IME was closed, tapped Hủy, and sent no SMS. Lượt 2 recorded `promptTokens=124`, `prefillMs=1,976 ms`, `generatedTokens=32`, `generationMs=11,076 ms`, `promptCacheHit=true`, and `cachedPromptTokens=114`. Its fenced `send_sms` output was rejected by the strict parser, so no second confirmation or send occurred. This proves bounded rendered-prefix reuse and its latency effect while preserving the no-execution safety boundary; repeated valid tool-call reliability remains open.

The direct natural SMS grammar remains the release-safe path: `nhan Nam la 10 phut nua toi` displayed the exact confirmation and the harness did not send it. The cache probe also verified the UI cancellation path after closing the IME; this is evidence for the harness flow, not a replacement for repeated human acceptance of outbound confirmation.

The follow-up warm-repeat run on the same POCO process executed the same model-route request twice after the harness fix. Both runs returned the expected SMS confirmation and were cancelled without sending. Run 1 recorded `coldStart=true`, `modelLoadMs=1,455 ms`, `prefillMs=14,363 ms`, and `generationMs=23,317 ms`; run 2 recorded `coldStart=false`, no model reload, `prefillMs=15,070 ms`, and `generationMs=22,972 ms`. The native prefix cache was not hit for this exact repeated request (`promptCacheHit=false`), so this is resident-model reliability evidence rather than a cache-speed claim. These were the earlier OpenCL-default measurements; the later CPU-first default run is recorded below.

The final `scripts/benchmark-backends-poco.ps1` run loaded the pinned model on the real POCO with an explicit backend. `scripts/benchmark-poco.ps1` then repeated the same 500-character/2-schema communication prompt for inference. Backend caches were isolated (`mnn-cache-opencl`, `mnn-cache-vulkan`, `mnn-cache-cpu`, and the default sequence cache), so these measurements do not rely on cross-backend cache reuse.

| Backend | Cold load | Model generation | Observed outcome |
|---|---:|---:|---|
| OpenCL | 1,410 ms load-only; 1,586 ms inference run | 23,961 ms | Malformed fenced `sms` payload; strict parser did not execute or request confirmation |
| CPU | 7,591 ms load-only; 8,158 ms inference run | 8,650 ms | Natural-language response, not a valid tool call; no SMS action |
| Vulkan | 25,270 ms load-only; 25,940 ms inference run | 35,004 ms | Runtime timeout; UI reported the bounded timeout error |

The default runtime now tries CPU first, then OpenCL, then Vulkan. The deterministic natural-language SMS grammar is reliable on this device: `nhan Nam la 10 phut nua toi` took 3,113 ms wall time and displayed the exact SMS confirmation; the harness pressed `HỦY`, so no SMS was sent. The earlier ambiguous model-route request on default OpenCL returned fenced natural-language text rather than a valid `send_sms` tool call; the later CPU warm-repeat produced valid confirmation on all four runs. These results preserve the safety invariant and put the selected warm profile below the planned 10-second investigation threshold, while the preferred 5-second target remains unmet.

The final direct-device corpus also measured flashlight ON, volume 30%, the truthful timer limitation `Không thể mở ứng dụng Đồng hồ.`, and bounded web search with three source IDs/URLs. The direct natural-message and model-message cases both produced SMS confirmations that the harness canceled; no outbound commit occurred. The open-app case reached the POCO's microG battery-optimization prompt rather than launching YouTube, so that environment limitation remains explicit. The latest CPU-first same-process ten-run sequence produced valid confirmation on 10/10 runs (`generationMs=9,488 ms` cold and `9,247–9,481 ms` warm) and no outbound commit. Its bounded snapshot moved from `TOTAL PSS=1,482,226 kB`, `TOTAL SWAP PSS=166 kB`, CPU `47.9 °C`, skin `43.7 °C`, and battery `39.8 °C` to `TOTAL PSS=1,450,177 kB`, `TOTAL SWAP PSS=154,332 kB`, CPU `53.3 °C`, skin `45.7 °C`, and battery `40.0 °C`; CPU usage after the run was `12%`, battery stayed at `30%`, and thermal status stayed `0`. The latest full-corpus model route recorded a cold `generationMs=10,968 ms`. The explicit OpenCL two-run comparison was also thermally unthrottled but slower (`25,933–29,202 ms`). These are bounded snapshots, not long-duration thermal, battery, or memory qualification.
