# A.B pre-v2 architecture audit

Audit date: 2026-08-09  
Branch: `main`  
Commit: `2f65ddc Initial commit: AB Android offline AI assistant`

## Baseline verification

The first sandboxed `.\gradlew.bat test` attempt did not reach compilation because Gradle could not resolve the Android Gradle Plugin `com.android.application:9.3.0`. After repository access was allowed, `.\gradlew.bat test` and `.\gradlew.bat assembleDebug` passed.

The POCO ADB baseline was captured on a connected `2311DRK48`; measured results are recorded in `docs/migration/performance-baseline.md`. `scripts/verify-legacy-phase4.ps1` remains the repeatable build/install/manual smoke entry point.

## Project shape

The app is a single `com.ab.assistant` Android module. The working runtime is split into:

- Kotlin UI/application wiring in `app/src/main/java/com/ab/assistant`.
- Kotlin agent, model, and tool code under `agent`, `model`, and `tools`.
- JNI/MNN integration in `app/src/main/cpp/native_bridge.cpp`.
- MNN is pinned as the `third_party/MNN` submodule at commit `d407447ed56c4121a11ccbd266dc184ca1ead0c2` (headers report version 3.6.1); see `docs/migration/mnn-version-lock.md`.
- Unit tests are under `app/src/test`; JNI/parser coverage is under `app/src/androidTest`.

The v2 target is now aligned with the plan at compile/target SDK 36. The POCO device gate remains separate from this host-side build alignment.

## Runtime flow

```text
MainActivity
  -> AbApplication.loadModel
  -> MnnModelRuntime.load
  -> NativeBridge.loadModel
  -> native_bridge.cpp: CPU, then OpenCL, then Vulkan fallback
  -> MNN Llm remains resident in the process

MainActivity.generate
  -> AgentCore.run
  -> UserCommandParser.parse
       -> explicit device/information command: ToolExecutor directly
       -> otherwise PromptBuilder.initial
          -> AgentModel.generate
             -> MnnModelRuntime.generate
                -> NativeBridge.generateWithMetrics
                   -> render MNN chat template
                   -> structured native metrics + rendered-prefix KV reuse
                -> ToolCommandParser.parse
                -> ToolExecutor
  -> up to five model/tool steps when a tool requests follow-up
```

`AgentCore` owns the current orchestration and confirmation/permission gates. `ToolRegistry` is the current concrete `ToolExecutor`; it performs availability checks, Android permission mapping, execution, and SMS confirmation policy.

## Concrete ownership

| Concern | Current owner |
|---|---|
| MNN model load/generate | `MnnModelRuntime`, `NativeBridge`, `native_bridge.cpp` |
| Prompt construction | `PromptBuilder` |
| Explicit natural-language parsing | `UserCommandParser` |
| Model JSON parsing | `ToolCommandParser` |
| Agent loop | `AgentCore` (`MAX_STEPS = 5`) |
| Tool command types/results | `ToolCommand`, `ToolExecutionResult` |
| Tool policy and execution | `ToolExecutor`, `ToolRegistry` |
| Flashlight | `FlashlightController`, reached by `ToolRegistry` |
| Apps/volume/media/timer/alarm | `ToolRegistry` using Android APIs/intents |
| Notifications | `AbNotificationListenerService`, `NotificationStore`, `NotificationCache`, `ToolRegistry` |
| Contacts | `ContactLookup`, `ToolRegistry` |
| Web search | `BingRssSearchClient`, `ToolRegistry` |
| SMS/call proposal | `ToolRegistry`, `AgentCore`, `MainActivity` |

## Migration boundary

The smallest safe Upgrade 1 boundary is `MnnModelRuntime.generate`: it serializes calls, invokes the structured native bridge, enforces the 35-second timeout, and is the Kotlin boundary that measures request and native generation durations. `native_bridge.cpp` returns a versioned payload containing prompt tokens, prefill, generated tokens, decode throughput, and rendered-prefix cache reuse; TTFT remains nullable when MNN reports no `ttfa_us`. AgentCore passes prompt-character count, exposed schema count, and per-task model-decision index through `InstrumentedAgentModel`. The latest record is published to `InferenceMetricsStore`, logged as JSON, and shown in the debug UI without replacing unavailable values with guesses.

## Upgrade 2 boundary

`TypedToolRegistry` now wraps the existing `ToolExecutor` implementations. `AgentCore` converts every typed `ToolCommand` to a `ToolCall`, validates the allowlisted name and exact argument shape, applies `ToolSpec` confirmation metadata, delegates to the legacy executor, and maps the result to/from typed `ToolResult`. `ToolExecutionResult.ok` now remains distinct from `verified`: intent handoffs (OpenApp, timer/alarm, media, dial, SMS request) report successful dispatch without claiming a verified postcondition, while readback-capable volume and information paths can remain verified. Unknown tools, malformed arguments, not-found results, and flashlight errors are rejected/fail before they appear successful.

## Upgrade 3 boundary

`PipelineRouter` now runs before Qwen. Explicit Tier-0 commands become `RouteDecision.Direct` and reach `AgentCore` with zero model calls; unrecognized or ambiguous paraphrases become `RouteDecision.ModelTool` and keep the existing Qwen path. Tier-0 covers flashlight, app launch, absolute/relative volume, media, timer/alarm, and battery/device state. The router is conservative for generic app paraphrases instead of guessing an app name.

## Upgrade 4 boundary

`RouteDecision.ModelTool` now carries the relevant `ToolGroup` set into `PromptBuilder`. Device, information, and communication requests receive only their matching schemas; the full prompt remains the fallback for ambiguous requests. The runtime now requests at most 32 new tokens for a single JSON/tool decision while the native configuration retains a 64-token upper bound, `max_all_tokens=2048`, and greedy sampling. The native path renders the MNN chat template with thinking disabled before tokenization and reuses safe rendered-token prefixes during a resident session. `AgentCore` terminates after a successful terminal tool result without sending that result back to Qwen.

The benchmark-only schema sweep supports 4/8/16 inert definitions and never routes its output to a tool parser or executor. On the POCO, 4 definitions measured 121 prompt tokens/15,628 ms prefill, 8 measured 190/20,925 ms, and 16 reached the 35-second Kotlin bound; the 16 profile contains reserved placeholders because the production registry has 13 executable tools.

## Upgrade 5 boundary

`TaskSessionStore` is the single owner for task lifecycle (`ROUTING`, model/tool waits, confirmation, execution, completion, failure, and cancellation). `AgentCore` attaches every asynchronous callback to a task ID, so late model/tool callbacks are ignored after cancellation. `MainActivity` now exposes the same Hủy boundary while a typed model task is still generating; the deployed POCO check observed the button during processing and confirmed it disappeared after cancellation. `CapabilityCoordinator` is the shared readiness source for model, validated network, live notification listener, contacts, and future Accessibility/Voice capabilities. `AbApplication` refreshes system-derived states on startup/resume; notification access is `READY` only while the listener is actually connected, not merely because the system setting is enabled.

## Upgrade 6 boundary

`SkillEngine` now matches known Phase-4 workflows before the model route and executes their deterministic `CallTool` steps through `TypedToolRegistry`/`AgentCore`. The initial skills are `prepare_message_to_contact` and `read_notifications_from_person`; SMS still pauses at the existing confirmation gate and resumes with the same task continuation. The engine contract also supports bounded waits, assertions, result branches, and explicit `AiSlot` handoff without giving skills a bypass around tool validation or policy.

## Upgrade 6 runtime bound

`ToolSpec.timeoutMs` is now enforced by `AgentCore`: each typed tool call runs on a daemon worker, the coordinating task waits only for the declared deadline, and timeout/cancellation returns explicit `TIMEOUT`/`CANCELLED` results. `AgentCore.cancel()` interrupts the active worker future and stale completion is ignored by `TaskSessionStore`. This closes the runtime gap between the typed timeout contract and actual execution.

## Upgrade 7 boundary

`StuckDetector` is attached per task and records repeated actions, repeated failed tool results, malformed tool-shaped model output, wall-clock deadline, and step budget. Repeated loops terminate with a truthful failure message; the existing five-step cap remains a second bound. Approval retries are excluded from action-repeat accounting, and cancellation removes the detector so late callbacks cannot revive the task.

## Upgrade 8 boundary

`AbAccessibilityService` is now registered with `canRetrieveWindowContent=true` and emits compact `SemanticUiSnapshot` records from the active window root (falling back to the event source only when no root is available). `SemanticUiResolver` prioritizes resource IDs, then role/content description/text; `SemanticRef` includes the snapshot ID so stale references are rejected. `UiPostconditionVerifier` covers node existence, text change, and package identity. Unit tests cover launcher/settings, messaging, and media-like semantic screens. The POCO service gate is still pending: the device is reachable, but `enabled_accessibility_services` was blank during the 2026-08-11 recheck, so the service is not treated as live until the user enables it and `onServiceConnected` sets `Capability.ACCESSIBILITY=READY`.

## Upgrade 9 boundary

`OutboundApprovalStore` implements prepare/authorize/commit semantics for SMS and calls. The prepared command is immutable, expires after 60 seconds, is matched against the exact confirmed payload, and is one-use. `AgentCore` retains the authorized payload across runtime permission requests and discards it on cancellation or completion; later model output cannot replace it. The deterministic confirmation gate now covers both `SendSms` and `DialContact`.

## Upgrade 10 boundary

`WebSearchResultParser` keeps the existing 256 KiB response cap and three-result limit while adding bounded stable result IDs and source URLs. `ToolRegistry` exposes those as metadata only; it never opens a browser or grants fetched text tool/policy authority. `PromptBuilder.afterTool` explicitly treats all tool output as untrusted data, including future retrieval summaries.

## Upgrade 11 boundary

`VoiceSessionCoordinator` implements push-to-talk `LISTENING → PROCESSING → SPEAKING` over Android SpeechRecognizer/TTS adapters and delegates recognition text to the existing `AgentCore` pipeline. Voice permission is runtime-gated; outbound SMS/call results stop at `WAITING_FOR_CONFIRMATION` and are never auto-committed by voice. Voice capability is marked READY only when the platform adapters are initialized. Session stop now cancels both in-flight STT and TTS work, so hiding the system voice session cannot leave speech output running after the boundary closes.

## Upgrade 12 boundary

The system-assistant session boundary is now active in code: `AbVoiceInteractionSessionService` creates a session that starts the existing voice coordinator on `onShow` and stops it on `onHide`/`onDestroy`. Wake-word startup failure now stops both detector and voice ports before publishing the degraded state, covered by a regression test. Android SDK 36 exposes no public `AlwaysOnHotwordDetector` API, so this does not claim a DSP wake-word implementation; `WakeWordDetectorProvider` remains the explicit registration point and reports degraded until a real provider is supplied.

`WakeWordLifecycleCoordinator` owns the low-power lifecycle `ARMED → LISTENING → PROCESSING → SPEAKING → ARMED` and stops the detector before STT/AgentCore work begins. `AbVoiceInteractionService` is the Android system-owned screen-off assistant boundary, with a companion `VoiceInteractionSessionService` for future heavy sessions; both manifest actions were resolved in the deployed POCO package dump. The app no longer uses a self-managed always-on microphone foreground service. The default detector intentionally reports `DEGRADED` until a real system/DSP wake-word engine is registered through `WakeWordDetectorProvider`; SpeechRecognizer is not used as an always-on detector. During the 2026-08-11 POCO recheck, Google remained the selected `voice_interaction_service`, so A.B wake-word latency/battery/background-kill evidence is still pending until the user selects A.B and supplies a real detector provider.

## Upgrade 13 boundary

`ProceduralSkillLearner` records successful AgentCore trajectories in memory and groups repeated exact request/tool signatures into DRAFT candidates. A candidate must be inspected, replayed through an explicit executor, and approved before `SkillEngine.register()` adds its versioned recipe. Failed or cancelled tasks are discarded; no candidate is persisted automatically, no capability is expanded, and outbound actions still pass the existing confirmation policy.

## Upgrade 14 boundary

`scripts/verify-upgrade-v2.ps1` runs the unit/build gate, installs and launches the debug APK when ADB exposes a device, prints structured Accessibility/system-assistant gate evidence from secure settings plus InferenceMetrics, and drives the manual acceptance corpus. `scripts/benchmark-poco.ps1` supplies the repeatable real-device corpus/metrics run and now emits before/after PSS, swap, CPU usage, temperature, battery, and thermal-status snapshots; every outbound confirmation in the full corpus is asserted and canceled. `scripts/benchmark-backends-poco.ps1` loads and compares OpenCL/Vulkan/CPU explicitly. The final 2026-08-11 POCO evidence promoted CPU to the default order: the latest same-process ten-run sequence returned valid SMS confirmation in `9,488 ms` cold and `9,247–9,481 ms` warm with no outbound commit; the full corpus also returned truthful direct results and a valid cold model confirmation at `10,968 ms`, while the open-app case stopped at the POCO microG battery-optimization prompt. OpenCL remained valid but slower (`25,933–29,202 ms` in the explicit comparison), and Vulkan hit the bounded timeout. The bounded CPU snapshot moved from `TOTAL PSS=1,482,226 kB`, `TOTAL SWAP PSS=166 kB`, CPU `47.9 °C`, skin `43.7 °C`, battery `39.8 °C` to `TOTAL PSS=1,450,177 kB`, `TOTAL SWAP PSS=154,332 kB`, CPU `53.3 °C`, skin `45.7 °C`, battery `40.0 °C`, with after-run CPU usage `12%`, battery `30%`, and thermal status `0`. The latest prefix probe recorded a 114-token reuse hit; strict parsing rejected a fenced `send_sms` response in that separate cache probe. The acceptance gate remains pending for the recorded timer/microG environment limitations, Accessibility, system assistant selection, wake-word provider, and manual voice/outbound/device flows. The script exits `2` when POCO/device or manual gates are pending, so those limitations cannot be reported as a release pass.
The final-APK targeted CPU recheck kept the same safety boundary: two same-process model requests produced valid SMS confirmations and both were canceled (`generationMs=10,832 ms` cold and `8,675 ms` warm; actual backend `CPU`; thermal status `0`). The full-corpus rerun was separately recorded as a harness timeout at `web-route`, not as a release pass.

The bounded ADB/UI-dump watchdog was then exercised against the complete corpus. It finished in `201.8 s`; the open-app row reproduced the microG GmsCore battery-optimization prompt rather than a verified YouTube postcondition and is intentionally retained as pending, while the direct device, bounded web, truthful timer, and canceled outbound evidence remained intact.

The step-by-step physical procedure and evidence fields are maintained in `docs/migration/device-acceptance-checklist.md`.
