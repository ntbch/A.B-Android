# A.B Android Full Project Design v2

**Date:** 2026-08-09  
**Status:** Proposed architecture revision after external mobile-agent research  
**Supersedes:** `2026-08-09-ab-android-full-project-design.md` after approval  
**Primary target:** POCO X6 Pro 8GB/256GB  
**Product name:** A.B  
**Application ID:** `com.ab.assistant`

## 1. Product mission

A.B is a **local-first Android personal agent** that understands Vietnamese commands, chooses safe registered actions, executes phone/web workflows, verifies outcomes, and returns a concise truthful result.

The user should eventually be able to leave the phone screen off, invoke A.B by voice, and ask for commands such as:

- open an app;
- control media/volume/flashlight;
- set timers and alarms;
- read selected notifications;
- search the web and summarize;
- find a contact and prepare/send a message with confirmation;
- operate an app through Accessibility when no direct API/Intent exists;
- execute short multi-step workflows;
- reuse known procedural skills without paying a full LLM round for every UI action.

A.B is not intended to be a general unrestricted autonomous computer-use agent. Reliability, latency, privacy, and clear control boundaries are higher priorities than arbitrary autonomy.

---

## 2. Locked technical baseline

The current model is retained exactly as requested:

```text
Model:             Qwen3.5-2B
Deployment format: MNN INT4 package
Runtime:           MNN
Thinking:          disabled / non-thinking path
Target ABI:        arm64-v8a
Primary device:    POCO X6 Pro 8GB/256GB
```

Host/build baseline from the working project:

```text
Host OS:       Windows 11
JDK runtime:   Oracle JDK 26.0.1
Gradle:        9.5.0 via Gradle Wrapper
AGP:           9.3.0
compileSdk:    36
targetSdk:     36
Build Tools:   36.0.0
NDK:           27.2.12479018
CMake:         3.22.1
IDE/editor:    Antigravity or VS Code
Device loop:   ADB + physical POCO
```

Current implementation status:

```text
Phase 0   Android APK shell on POCO       COMPLETE
Phase 0.5 Kotlin -> JNI -> C++ smoke      COMPLETE
```

The next implementation work begins at MNN integration.

---

## 3. Architecture v2 — core change

The old design treated the LLM as the normal first decision-maker. The v2 design treats the LLM as an **expensive semantic router/planner used only when deterministic logic cannot safely solve the request**.

```text
                         +---------------------+
Voice/Text/Intent ------>|   Input Normalizer   |
                         +----------+----------+
                                    |
                                    v
                         +---------------------+
                         |   Pipeline Router    |
                         +----+-----+-----+-----+
                              |     |     |
                  Tier 0 -----+     |     +------ Tier 3
               Direct action        |             Bounded agent
                    0 LLM            |             <= 5 decisions
                                     |
                              Tier 1 | Tier 2
                              Skill  | Qwen tool route
                              0 LLM  | usually 1 generation
                                     v
                              +--------------+
                              | Tool Registry |
                              +------+-------+
                                     |
                          policy/capability/
                         validation/confirmation
                                     |
                                     v
                              +--------------+
                              | Tool Executor |
                              +------+-------+
                                     |
        +----------------------------+----------------------------+
        |                 |                |                       |
  Android API/Intent   Accessibility     Web/API             Device data
        |                 |                |                       |
        +----------------------------+----------------------------+
                                     |
                                     v
                              Verified result
                                     |
                  +------------------+------------------+
                  |                                     |
             task complete                       another decision
                  |                                     |
                  v                                     v
             concise output                     Qwen continuation
```

This architecture keeps Qwen3.5-2B but minimizes repeated inference.

---

## 4. Pipeline tiers

### 4.1 Tier 0 — deterministic direct route

Purpose: execute commands whose intent and arguments can be extracted with high confidence without an LLM.

Initial Tier-0 command families:

- flashlight on/off;
- media play/pause/next/previous;
- relative volume up/down and explicit volume percentage when supported;
- open app by exact/fuzzy installed-app name;
- set simple timer;
- set alarm;
- report battery percentage/charging state;
- report date/time/device state;
- press Home/Back where appropriate;
- open a known URL/deep link.

Tier 0 must be conservative. If parsing is ambiguous, it does **not** guess; it falls through to Tier 2.

Success criterion: zero MNN/Qwen call.

### 4.2 Tier 1 — deterministic skill route

A Skill is a versioned, inspectable recipe of registered tools and postconditions.

Example:

```text
skill: search_in_app
inputs: app, query
steps:
  1 open_app(app)
  2 wait_for_app(app)
  3 locate search field
  4 input_text(query)
  5 press_enter
verify:
  expected package still active
  screen no longer equals pre-search snapshot
```

Skills are not hardcoded pixel-coordinate macros. They use generic tools, semantic selectors, and verification predicates.

A skill may contain one explicitly defined AI slot for an ambiguous subproblem, but known mechanical steps do not repeatedly call Qwen.

### 4.3 Tier 2 — Qwen3.5 single-shot tool route

When deterministic routing cannot understand natural-language intent confidently, Qwen receives:

- concise system policy;
- only relevant tool definitions, not the entire future catalog;
- current user request;
- minimal current task context.

Expected output is a canonical function call.

Example:

```json
{
  "name": "prepare_message",
  "arguments": {
    "contact": "Nam",
    "channel": "zalo",
    "text": "10 phút nữa tao tới"
  }
}
```

The model does not execute anything. Kotlin validates and decides whether confirmation/capability gates allow execution.

### 4.4 Tier 3 — bounded agent loop

Only use when a result genuinely requires another model decision, for example:

- UI state differs from the known skill path;
- a target is ambiguous after deterministic lookup;
- a web result must be interpreted to select a next tool;
- the user asked for a short conditional workflow.

Hard limits:

```text
max model decision steps: 5
max wall-clock deadline: task-family-specific
max repair generations per malformed tool call: 1
stuck detection: mandatory
user cancellation: cooperative and immediate at tool boundaries
```

No unbounded ReAct loop.

---

## 5. Module boundaries

The project remains a single Android application module initially, but source packages are split by responsibility.

```text
app/src/main/java/com/ab/assistant/
|
+-- app/
|   +-- MainActivity.kt
|   +-- AbApplication.kt
|
+-- runtime/
|   +-- AbLlmRuntime.kt
|   +-- MnnRuntimeState.kt
|   +-- InferenceRequest.kt
|   +-- InferenceResult.kt
|   +-- InferenceMetrics.kt
|   +-- BackendBenchmark.kt
|
+-- agent/
|   +-- AgentOrchestrator.kt
|   +-- PipelineRouter.kt
|   +-- TaskSessionStore.kt
|   +-- TaskSession.kt
|   +-- AgentBudget.kt
|   +-- recovery/
|       +-- StuckDetector.kt
|       +-- RecoveryPolicy.kt
|
+-- protocol/
|   +-- ToolCall.kt
|   +-- QwenToolCallParser.kt
|   +-- ToolSchemaValidator.kt
|   +-- PromptBuilder.kt
|
+-- tools/
|   +-- Tool.kt
|   +-- ToolSpec.kt
|   +-- ToolRegistry.kt
|   +-- ToolExecutor.kt
|   +-- ToolResult.kt
|   +-- ToolRisk.kt
|   +-- ConfirmationPolicy.kt
|   +-- impl/
|
+-- skills/
|   +-- Skill.kt
|   +-- SkillRegistry.kt
|   +-- SkillExecutor.kt
|   +-- SkillStep.kt
|   +-- SkillResult.kt
|
+-- capability/
|   +-- Capability.kt
|   +-- CapabilityState.kt
|   +-- CapabilityCoordinator.kt
|
+-- accessibility/
|   +-- AbAccessibilityService.kt
|   +-- UiSnapshot.kt
|   +-- UiNode.kt
|   +-- UiSnapshotBuilder.kt
|   +-- UiActionExecutor.kt
|   +-- UiPostcondition.kt
|
+-- notifications/
|   +-- AbNotificationListener.kt
|   +-- NotificationRepository.kt
|
+-- contacts/
|   +-- ContactResolver.kt
|
+-- web/
|   +-- WebSearchTool.kt
|   +-- WebResult.kt
|
+-- voice/
|   +-- PushToTalkController.kt
|   +-- SpeechToText.kt
|   +-- TextToSpeech.kt
|   +-- AbVoiceInteractionService.kt   # later phase
|
+-- model/
|   +-- ModelPackageManager.kt
|   +-- ModelManifest.kt
|
+-- telemetry/
|   +-- AbLog.kt
|   +-- MetricsStore.kt
|
+-- security/
    +-- ActionAuthorizer.kt
    +-- ConfirmationController.kt
    +-- InstructionTrustPolicy.kt
```

Native layer:

```text
app/src/main/cpp/
+-- CMakeLists.txt
+-- ab_jni.cpp
+-- llm/
|   +-- AbMnnRuntime.hpp
|   +-- AbMnnRuntime.cpp
+-- telemetry/
    +-- NativeMetrics.hpp
```

Avoid a single giant `MainActivity`, `AgentManager`, or JNI source file.

---

## 6. MNN/Qwen runtime design

### 6.1 Runtime state machine

```text
UNINITIALIZED
   -> LOADING
      -> READY
         -> GENERATING
            -> READY
      -> ERROR
   -> UNLOADING
      -> UNINITIALIZED
```

All state transitions are observable. UI/agent code asks the runtime for readiness; it does not infer readiness from whether model files exist.

### 6.2 Model assets

Development model package:

```text
Qwen3.5-2B-MNN INT4
language model only for initial agent phases
```

Do not initialize visual encoder paths in early phases. Vision is outside the first functional slice.

### 6.3 Runtime version

Use a pinned tested MNN stable release at or above the release where Qwen3.5 and current mobile LLM improvements are available. Record the exact tag/hash in the repository.

Do not build production APKs against an unpinned MNN `master`.

### 6.4 Backend benchmark

A.B does not hardcode “GPU is faster.” On POCO, benchmark:

- OpenCL;
- Vulkan;
- CPU.

For each backend, run the same fixed prompts after cold and warm initialization and record:

- initialization/load time;
- time to first token;
- prompt/prefill time;
- decode tokens/sec;
- total time to produce a 32-token and 64-token output;
- stability over repeated runs;
- memory pressure;
- actual backend/fallback reason.

The default backend is selected from those measurements. CPU remains fallback.

### 6.5 Prompt cache

The tool/system prefix changes infrequently. Evaluate MNN text prompt cache early.

Cache key includes:

```text
model package hash
runtime version
system-policy version
tool-schema-set hash
locale profile
```

Any change invalidates the prefix cache.

### 6.6 Context profiles

```text
BENCHMARK: 1024 context
NORMAL:    2048 context
ESCALATED: 4096 only if a measured task requires it
```

Never allocate the model's maximum advertised context on this device.

### 6.7 Output profiles

Tool routing:

```text
max generated tokens: 64 initially
```

Short final response:

```text
max generated tokens: 128-256 depending on use case
```

A.B should not generate explanatory prose before a tool call.

---

## 7. InferenceMetrics and performance truth

Every Qwen invocation produces an `InferenceMetrics` record:

```kotlin
data class InferenceMetrics(
    val requestId: String,
    val backendRequested: String,
    val backendActual: String,
    val fallbackReason: String?,
    val coldStart: Boolean,
    val modelLoadMs: Long?,
    val promptTokens: Int,
    val prefillMs: Long,
    val ttftMs: Long,
    val generatedTokens: Int,
    val decodeTokensPerSecond: Double,
    val generationMs: Long,
    val totalMs: Long
)
```

The debug UI must show these values. “OpenCL selected” is not enough; A.B reports what backend actually ran.

### Project performance targets

These are A.B product gates, not claims about current hardware performance:

- deterministic Tier-0 routing overhead: target < 100 ms excluding Android action latency;
- common direct device action end-to-end: target < 500 ms where Android permits;
- warm Qwen single-tool decision: target <= 5 seconds, hard investigation threshold 10 seconds;
- a one-minute simple tool decision is a blocker, not acceptable normal behavior;
- no known skill should pay an LLM inference on every mechanical step;
- model reload must not occur for every command while an interaction session is active.

If the target is missed, first profile load/prefill/backend/cache before changing the locked model.

---

## 8. Qwen tool-call protocol

### 8.1 Canonical schema

Each tool definition has JSON Schema parameters. Qwen output must be parsed into:

```kotlin
data class ToolCall(
    val name: String,
    val arguments: JsonObject
)
```

A generated tool name not in the currently exposed registry is invalid.

### 8.2 Parser chain

```text
raw generation
 -> canonical envelope extraction
 -> strict JSON parse
 -> registered tool lookup
 -> JSON-schema validation
 -> capability validation
 -> authorization / confirmation
 -> execution
```

### 8.3 Malformed output

On malformed output:

```text
attempt 1: fail parse
attempt 2: one constrained repair prompt
then: terminate with PARSE_FAILED
```

Risky action arguments are never reconstructed by regex/guessing after parser failure.

### 8.4 Tool result continuation

Tool output has a typed result:

```kotlin
data class ToolResult(
    val status: ToolStatus,
    val summary: String,
    val data: JsonObject?,
    val verified: Boolean,
    val retryable: Boolean,
    val errorCode: String?
)
```

Only feed a result back to Qwen if another semantic decision is necessary. A successful terminal tool can end the task immediately without another model round.

---

## 9. Tool Registry and safety boundary

### 9.1 ToolSpec

```kotlin
data class ToolSpec(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val risk: ToolRisk,
    val requiredCapabilities: Set<Capability>,
    val confirmation: ConfirmationPolicy,
    val executionMode: ExecutionMode,
    val timeoutMs: Long,
    val idempotency: Idempotency
)
```

### 9.2 Tool exposure is scoped

Do not show all tools to Qwen on every request. Router/tool catalog selects the smallest relevant set, for example:

```text
DEVICE: flashlight, volume, media, battery
COMMUNICATION: find_contact, prepare_message, make_call
WEB: web_search, open_url
UI: get_ui_snapshot, tap_ref, input_text, scroll
FILES: search_file, read_file
```

Fewer tool definitions mean shorter prefill and fewer confusing choices.

### 9.3 Risk classes

```text
LOW
- read battery
- open app
- media control
- flashlight
- read local non-sensitive state

MEDIUM
- read notifications
- read contact details
- navigate UI into another app

HIGH
- send message
- place call
- post content
- delete data

BLOCKED_INITIAL_RELEASE
- purchase
- financial transfer
- security-setting bypass
- device lock bypass
```

Risk and confirmation are Kotlin policy, never model instructions only.

### 9.4 Confirmation

For outbound/high-impact action:

```text
model/skill proposes action
 -> deterministic preview
 -> user confirmation
 -> executor receives one-use authorization token
 -> action
 -> postcondition / result
```

The confirmation token binds tool name + normalized arguments + task id so the model cannot silently change the action after approval.

---

## 10. Skill Engine

### 10.1 Skill contract

```kotlin
data class Skill(
    val id: String,
    val version: Int,
    val triggers: List<SkillTrigger>,
    val parameters: JsonObject,
    val steps: List<SkillStep>,
    val maxWallMs: Long,
    val risk: ToolRisk
)
```

Steps may be:

```text
CALL_TOOL
WAIT_FOR
ASSERT
BRANCH_ON_RESULT
AI_SLOT  # explicitly allowed only when needed
```

### 10.2 Skill benefits

A known workflow such as “search in YouTube” should not do:

```text
Qwen -> tap
Qwen -> inspect
Qwen -> type
Qwen -> inspect
Qwen -> press enter
```

Instead:

```text
Qwen/deterministic router -> skill(search_in_app)
SkillExecutor -> generic tools + postconditions
```

This is a central latency strategy while retaining Qwen3.5-2B.

### 10.3 Skill learning later

A successful repeated trajectory may be converted to a **candidate** procedural skill, but the user must inspect/approve before persistence. A.B does not self-modify trusted automation from one run.

---

## 11. TaskSessionStore and cancellation

One authoritative store owns live task truth.

States:

```text
IDLE
ROUTING
WAITING_FOR_MODEL
WAITING_FOR_CONFIRMATION
EXECUTING
WAITING_FOR_TOOL
STOPPING
COMPLETED
FAILED
CANCELLED
```

The session stores route, active skill/tool, step count, deadline, cancellation, and last verified result.

Every long-running tool checks cooperative cancellation at safe boundaries.

The UI, notification, voice surface, and future floating indicator observe this store rather than each keeping a separate “task running” boolean.

---

## 12. Stuck detection and recovery

Port/adapt PokeClaw's core detector with attribution and extend it for A.B.

Signals:

- same action repeated;
- UI screen/snapshot unchanged after expected-mutating action;
- zero meaningful diff;
- high action repetition pattern;
- repeated error code;
- step budget nearing limit;
- wall-clock deadline exceeded;
- repeated malformed model output.

Recovery levels:

```text
LEVEL 1 HINT
- refresh snapshot
- remind model of failed action/postcondition

LEVEL 2 STRATEGY_SWITCH
- invalidate stale refs
- switch from skill to bounded model decision
- switch selector strategy

LEVEL 3 TERMINATE
- stop safely
- return truthful failure and evidence
```

No infinite retry.

---

## 13. CapabilityCoordinator

A.B's tools query capability state before execution.

For each capability:

```text
DISABLED
CONNECTING
READY
DEGRADED
```

Examples:

### Accessibility

`READY` requires both configuration in system Settings **and** live service connection/heartbeat.

### Notification access

`READY` requires granted listener access **and** `NotificationListenerService` connected.

### Model

`READY` requires model files verified **and** MNN smoke-load successful.

### Network

`READY` means usable network for the requested web tool, not merely a Wi-Fi icon.

This prevents tools from assuming capability based on stale Settings state.

---

## 14. Accessibility subsystem

### 14.1 Observation hierarchy

```text
1. Direct Android API/Intent result
2. Compact Accessibility snapshot
3. Full Accessibility snapshot
4. Screenshot/vision fallback (future)
```

Vision is not required in v2 early phases.

### 14.2 Compact UiSnapshot

```kotlin
data class UiSnapshot(
    val generation: Long,
    val packageName: String,
    val windowTitle: String?,
    val nodes: List<UiNode>
)

data class UiNode(
    val ref: String,
    val resourceId: String?,
    val role: String,
    val text: String?,
    val contentDescription: String?,
    val bounds: Rect,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean
)
```

Refs are valid only for one snapshot generation. UI changes invalidate them.

### 14.3 Selector priority

Prefer stable selectors in this order:

```text
resourceId
semantic role + tree relation
contentDescription
visible text
bounds/position as last resort
```

Avoid language-specific text selectors when resource IDs or semantics exist.

### 14.4 Postcondition verification

Actions that should change UI verify at least one of:

- package/window change;
- target node state change;
- meaningful snapshot diff;
- expected node appears/disappears;
- expected text field value changes.

A successful `performAction()` return alone does not always mean the user-visible goal succeeded.

---

## 15. Direct Android tools before Accessibility

Order of preference:

```text
native API
 -> explicit Intent/deep link
 -> app-supported public interface
 -> Accessibility
 -> screenshot/vision fallback
```

Examples:

- alarm/timer: AlarmClock intents/API path;
- app launch: PackageManager launch intent;
- browser/navigation: URI intent;
- media: media/session APIs where allowed;
- flashlight: CameraManager;
- contacts: ContactsContract;
- notification reading: NotificationListenerService;
- SMS compose: SENDTO intent first;
- arbitrary third-party chat app: Accessibility skill only when no reliable direct integration exists.

---

## 16. Messaging and communication

Split “prepare” from “commit.”

```text
prepare_message(contact, channel, text)
 -> resolve target
 -> normalize preview
 -> confirmation
 -> commit_message(preparedId, authorizationToken)
```

This prevents a later model generation from changing recipient/text after confirmation.

SMS/API/Intent paths are preferred. Zalo/WhatsApp-style UI automation is implemented as a skill/fallback, not as the fundamental messaging abstraction.

Call placement follows the same preview/confirmation pattern.

---

## 17. Web tools

Web search is an explicit tool, not “open Chrome and scrape results” by default.

```text
web_search(query)
 -> HTTP/search provider
 -> structured results
 -> optional Qwen summarization
 -> final answer
```

Web content is **untrusted data**. It cannot add system instructions or change tool safety policy.

If a search answer requires no additional phone action, it stays off-screen and returns via text/TTS.

---

## 18. Model package lifecycle

Development phase may manually copy the MNN model to the app/device.

Before wider usage, `ModelPackageManager` owns:

- storage selection based on writability and free space;
- resumable download;
- partial file cleanup;
- expected files/sizes/hash manifest;
- SHA-256 verification;
- MNN smoke-load verification;
- version migration;
- user-visible repair/redownload.

Model state:

```text
MISSING
DOWNLOADING
VERIFYING
READY
BROKEN
UPDATING
```

A directory that merely exists is not considered ready.

---

## 19. Voice architecture

### 19.1 First voice milestone

Push-to-talk only:

```text
button
 -> SpeechRecognizer/on-device STT where available
 -> normalized Vietnamese text
 -> same PipelineRouter as typed commands
 -> TTS result
```

Voice is an input/output surface, not a separate agent architecture.

### 19.2 Screen-off/wake-word milestone

Use Android `VoiceInteractionService` architecture rather than a random always-running microphone service.

Always-running part stays lightweight. Heavy STT/model/tool work activates only after interaction begins.

The model itself does **not** continuously generate or listen while the phone is idle.

---

## 20. Instruction trust hierarchy

A.B must enforce instruction priority structurally:

```text
1. compiled platform/tool safety policy
2. explicit user global settings
3. app/channel-scoped user rules
4. approved procedural skill
5. current user request
6. tool results / screen text / notifications / web content (UNTRUSTED DATA)
```

Text read from a website, notification, message, or UI cannot tell A.B to bypass confirmation, expose data, or invoke unrelated tools.

---

## 21. External automation later

Tasker/MacroDroid integration is useful as a deterministic trigger source, but it is disabled by default.

Preferred interface:

```text
explicit exported Activity or tightly scoped receiver
custom signature/permission where practical
enabled only by user setting
allowlisted action: RUN_TASK
optional callback PendingIntent/status token
```

Do not expose a broad LAN server or unauthenticated broadcast surface.

---

## 22. Logging, privacy, and observability

Logs use categories:

```text
AB/RUNTIME
AB/ROUTER
AB/TOOL
AB/SKILL
AB/A11Y
AB/CAP
AB/VOICE
AB/SECURITY
```

Release logs redact:

- message bodies;
- contact details;
- tokens/API secrets;
- full notification contents;
- raw web content where not needed;
- typed passwords/sensitive fields.

Debug mode may collect richer local diagnostics with explicit user control.

Every tool result distinguishes:

```text
EXECUTED
VERIFIED_SUCCESS
FAILED
BLOCKED
NEEDS_CONFIRMATION
CAPABILITY_UNAVAILABLE
CANCELLED
```

Never return “done” solely because the model predicted success.

---

## 23. QA strategy

### 23.1 Test pyramid

```text
Kotlin unit tests
- router
- parser/schema
- safety policy
- skill engine
- stuck detector
- capability state machine

Native unit/smoke
- JNI contract
- MNN runtime load
- deterministic fixed prompt generation

Android instrumentation
- direct tools
- capability service connection
- Accessibility snapshots/actions

Physical POCO acceptance
- repeated real user tasks
- latency/memory/backend metrics
```

### 23.2 Deterministic gate

Deterministic route/tool tests should pass every repetition in the controlled test setup. A flaky deterministic action is treated as a harness bug.

### 23.3 Model gate

For each model-routed task family, run multiple Vietnamese variations and repeated trials. Store:

- routing correctness;
- valid tool-call rate;
- argument correctness;
- total latency;
- success postcondition;
- fallback/recovery count.

Do not report a single successful run as reliability.

### 23.4 A.B POCO acceptance suite

Build at least 20 canonical tasks over time, including direct, model-routed, skill, Accessibility, web, communication, voice, and screen-off categories.

Parameterize names/query text/durations so the model cannot pass by memorizing one exact demo phrase.

---

## 24. Dependency and source-reuse policy

A.B may selectively port permissively licensed code reviewed in the research audit, but copied code must be auditable. PokeClaw (Apache-2.0) is an approved selective-port source. Mobilerun Portal is AGPL-3.0-or-later and is reference-only under the current A.B licensing strategy; its source is not to be copied into A.B unless that licensing decision is explicitly revisited.

Repository files before first upstream port:

```text
THIRD_PARTY_NOTICES.md
licenses/
third_party/versions.toml
```

For each reused source:

- record upstream repository;
- record exact tag/commit;
- retain license header;
- mark modifications;
- include applicable NOTICE/license files;
- maintain A.B naming/branding.

Prefer reimplementation where the upstream file brings many unrelated dependencies.

---

## 25. Non-goals for v2 early phases

Do not add these before the core vertical slices are stable:

- root requirement;
- MediaTek NPU/NeuroPilot dependency;
- multimodal screenshot reasoning for every action;
- arbitrary purchasing/payment automation;
- device lock/security bypass;
- cloud LLM dependency for normal commands;
- large long-term vector memory/RAG;
- broad remote-control server;
- multi-provider runtime abstraction;
- support for many phone models before POCO behavior is reliable.

---

## 26. End-to-end Definition of Done

A.B reaches its main project goal when the POCO can reliably do this:

1. Phone is screen-off and idle with no abnormal continuous LLM inference.
2. User invokes configured wake word or assistant entrypoint.
3. A.B captures a Vietnamese command.
4. Router handles deterministic/skill commands without unnecessary model calls.
5. When needed, Qwen3.5-2B returns a valid registered function call.
6. Kotlin validates schema, capability, risk, and confirmation policy.
7. Direct Android API/Intent is preferred over Accessibility.
8. Accessibility uses compact semantic snapshots and verifies UI effects.
9. Short multi-step tasks remain within hard budgets and stuck recovery.
10. Web queries work without opening a browser unless requested.
11. Outbound/personal actions require deterministic confirmation according to policy.
12. A.B reports actual tool results, not model assumptions.
13. After completion it returns to a low-power idle state.
14. Normal phone usage remains responsive while A.B is idle.
15. The physical-device acceptance suite and release gate pass at the required repetition rate.

---

## 27. Architectural decisions locked for implementation

Unless measurement disproves a device-specific choice, implementation proceeds with:

- Kotlin native Android APK;
- command-line Gradle/ADB workflow;
- physical POCO X6 Pro primary target;
- Qwen3.5-2B INT4 retained;
- MNN retained and pinned to a tested stable version;
- non-thinking model path;
- 1024/2048 initial context profiles;
- deterministic + skill routes before model route;
- canonical Qwen function-call schema;
- strict parser/schema validation;
- typed Tool Registry with risk/capability/confirmation policy;
- one authoritative TaskSessionStore;
- one authoritative CapabilityCoordinator;
- bounded agent loop max 5 model decisions;
- port/adapt StuckDetector with attribution;
- Android API/Intent before Accessibility;
- compact Accessibility snapshot before full snapshot/screenshot;
- push-to-talk before wake word;
- no root/NPU requirement in early phases;
- performance measured on-device before backend assumptions.

---

## 28. Implementation sequencing principle

Do not build all subsystems in parallel. Each phase must yield a testable vertical slice on the actual POCO.

The companion master roadmap is:

`docs/superpowers/plans/2026-08-09-ab-android-master-roadmap-v2.md`.

Detailed coding plans are written per phase only after this v2 design is reviewed and approved.
