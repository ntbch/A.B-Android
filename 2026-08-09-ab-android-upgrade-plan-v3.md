# A.B Android Upgrade Plan v3 — From Legacy Phase 4 to Architecture v2

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade the existing A.B Android implementation that has completed the original Phase 0–4 into the v2 architecture without rewriting working capabilities, while preserving Qwen3.5-2B INT4 + MNN and improving latency, routing, reliability, safety, and future voice/Accessibility support.

**Architecture:** Treat the current working Phase 0–4 code as the migration base. First audit and benchmark the actual implementation, then insert a deterministic Pipeline Router and typed execution boundary in front of the existing tool set, migrate multi-step flows into Skills, add one authoritative task/capability state layer, and only then continue with Accessibility, voice, wake-word, and learned-skill phases.

**Tech Stack:** Kotlin Android, Qwen3.5-2B INT4, Alibaba MNN, JNI/C++, Gradle 9.5.0, AGP 9.3.0, compile/target SDK 36, NDK 27.2.12479018, CMake 3.22.1, physical POCO X6 Pro 8GB/256GB.

## Global Constraints

- Product/display name: **A.B**.
- Application ID: **`com.ab.assistant`**.
- Current baseline is the user's existing implementation through **original Phase 4**.
- Existing working tools are migrated in place; do not rewrite them merely to match the new package layout.
- Model remains **Qwen3.5-2B INT4** for this plan.
- Runtime remains **MNN** for this plan.
- Normal commands use non-thinking generation.
- Maximum semantic/model decision steps per task: **5**.
- Prefer Android API → Intent/deep link → app-specific integration → Accessibility → future vision fallback.
- Outbound/high-impact actions remain governed by deterministic Kotlin confirmation policy.
- POCO X6 Pro is the performance source of truth.
- Do not copy AGPL source code into A.B. Mobilerun Portal may be used as an architecture/reference source only unless licensing strategy is explicitly changed.
- PokeClaw Apache-2.0 code may be ported selectively with required attribution/NOTICE handling.

---

## Current Baseline Assumption

The original roadmap defined these capabilities by the end of Phase 4:

```text
Phase 1  MNN + Qwen3.5-2B + text input + tool-call parser + flashlight
Phase 2  open_app + volume + media + timer/alarm + bounded agent loop
Phase 3  notifications + contacts + web search + summarization/network handling
Phase 4  compose/send message + call + confirmation + contact ambiguity handling
```

This plan does **not** assume every implementation detail is ideal. It assumes the user has implemented these functional slices and therefore starts with an audit rather than a rebuild.

---

# Upgrade 0 — Freeze, inventory, and establish migration safety

## Outcome

Produce a factual map of the current codebase and a regression baseline before changing architecture.

## Files

Create or update only after inspecting the real repo:

```text
docs/migration/current-architecture.md
docs/migration/tool-inventory.md
docs/migration/performance-baseline.md
scripts/verify-legacy-phase4.ps1
```

Do not move packages or rename working classes in this task.

## Steps

- [x] **Step 1: Capture repository state**

Run from the actual project root:

```powershell
git status --short
git branch --show-current
git log --oneline -20
```

Record the current commit/branch in `docs/migration/current-architecture.md`.

- [x] **Step 2: Inventory source packages and native code**

Capture:

```powershell
Get-ChildItem .\app\src\main\java -Recurse -File | Select-Object FullName
Get-ChildItem .\app\src\main\cpp -Recurse -File | Select-Object FullName
```

Document which classes currently own:

```text
MNN load/generate
prompt building
tool-call parsing
agent loop
tool registry/execution
flashlight
open_app
volume/media
timer/alarm
notifications
contacts
web search
message/call confirmation
```

- [x] **Step 3: Build a tool inventory**

For each existing tool record:

```text
name
current class/function
input shape
permissions
whether it is deterministic
whether it calls Qwen
whether it uses Accessibility
risk level
known failure mode
```

- [x] **Step 4: Create a Phase-4 regression script**

`scripts/verify-legacy-phase4.ps1` must run the existing unit tests/build and provide manual/ADB prompts for the capabilities already implemented. At minimum verify build/install plus smoke checks for the tools that are automated today.

- [x] **Step 5: Run baseline verification on the POCO**

Capture pass/fail rather than fixing unrelated issues yet.

- [ ] **Step 6: Commit only the audit artifacts**

Suggested commit:

```text
docs: capture pre-v2 A.B migration baseline
```

## Exit Gate

- exact current source layout documented;
- existing Phase-4 features mapped to concrete classes/functions;
- baseline build/install status recorded;
- no architecture refactor has started yet.

---

# Upgrade 1 — Diagnose the one-minute latency before architecture changes

## Outcome

Know whether the delay comes from model load, backend fallback, prefill, decode, excessive prompt/tool schemas, repeated model rounds, or app-level orchestration.

## New Contracts

Introduce metrics without replacing the current runtime first:

```kotlin
data class InferenceMetrics(
    val requestId: String,
    val backendRequested: String?,
    val backendActual: String?,
    val fallbackReason: String?,
    val coldStart: Boolean,
    val modelLoadMs: Long?,
    val promptTokens: Int?,
    val prefillMs: Long?,
    val ttftMs: Long?,
    val generatedTokens: Int?,
    val decodeTokensPerSecond: Double?,
    val generationMs: Long,
    val totalMs: Long
)
```

If the existing MNN bridge cannot expose every field immediately, use nullable values and add them incrementally; do not invent measurements.

## Steps

- [x] **Step 1: Write tests for metric record serialization**
- [x] **Step 2: Instrument the existing Qwen invocation boundary**
- [x] **Step 3: Log whether the model is loaded per command or retained**
- [x] **Step 4: Log prompt size and exposed tool count per request**
- [x] **Step 5: Benchmark fixed command corpus on the POCO**

Corpus:

```text
bật đèn pin
mở YouTube
âm lượng 30 phần trăm
đặt hẹn giờ 5 phút
search thời tiết Hà Nội hôm nay
nhắn Nam là 10 phút nữa tới
```

For each run record:

```text
cold/warm
backend requested/actual
model_load_ms
prefill_ms
ttft_ms
decode_tps
generated_tokens
model decision count
tool count exposed
total_ms
```

- [x] **Step 6: Benchmark CPU/OpenCL/Vulkan where current MNN build supports them**

Do not assume GPU wins.

- [x] **Step 7: Identify the dominant latency bucket**

Decision rules:

```text
model load dominates  -> residency/loading fix
prefill dominates     -> shorten prompt/tool schema/context; evaluate prefix cache
decode dominates      -> backend/sampler/output-limit tuning
multiple model calls  -> routing/skill migration is highest priority
fallback to CPU       -> backend integration/fallback investigation
```

- [ ] **Step 8: Commit metrics instrumentation**

Suggested commit:

```text
perf: instrument A.B local inference latency
```

## Exit Gate

A.B has measured evidence for the one-minute delay. Do not change the locked model solely from anecdotal latency.

---

# Upgrade 2 — Introduce typed ToolSpec/ToolResult around existing executors

## Outcome

Keep current implementations but make every tool pass through one deterministic policy/validation boundary.

## Target Contracts

```kotlin
data class ToolCall(
    val name: String,
    val arguments: JsonObject
)

data class ToolSpec(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val risk: ToolRisk,
    val requiredCapabilities: Set<Capability>,
    val confirmation: ConfirmationPolicy,
    val timeoutMs: Long
)

data class ToolResult(
    val status: ToolStatus,
    val summary: String,
    val data: JsonObject? = null,
    val verified: Boolean,
    val retryable: Boolean,
    val errorCode: String? = null
)
```

## Migration Rule

Wrap existing executors first:

```text
LegacyFlashlightExecutor -> Tool adapter -> ToolRegistry
LegacyWebSearch          -> Tool adapter -> ToolRegistry
LegacySendMessage        -> Tool adapter -> ToolRegistry
```

Only refactor executor internals when a real defect requires it.

## Steps

- [x] parser/registry failing tests first;
- [x] implement typed registry;
- [x] wrap low-risk existing tools;
- [x] wrap information tools;
- [x] wrap messaging/call proposal/commit path;
- [x] verify unknown tool/malformed args cannot execute;
- [x] verify existing Phase-4 regression still passes.

## Exit Gate

All existing tools are callable through the typed runtime; legacy direct-call paths are removed or explicitly marked temporary.

---

# Upgrade 3 — Add Pipeline Router v2 in front of Qwen

## Outcome

Simple commands stop invoking Qwen while difficult paraphrases still use the existing model.

## Router Contract

```kotlin
sealed interface RouteDecision {
    data class Direct(val call: ToolCall) : RouteDecision
    data class Skill(val skillId: String, val arguments: JsonObject) : RouteDecision
    data class ModelTool(val exposedToolGroups: Set<ToolGroup>) : RouteDecision
    data class Agent(val reason: String) : RouteDecision
}
```

## Tier 0 Initial Families

```text
flashlight on/off
open app
volume set/up/down
media play/pause/next/previous
timer/alarm
battery/device state
```

## Rules

- deterministic parser must be conservative;
- ambiguous parse falls through, never guesses;
- every direct route still passes ToolRegistry/schema/capability/policy;
- record `modelCalls=0` for Tier 0 regression tests.

## Test Corpus

Direct examples:

```text
bật đèn pin
tắt flash
mở youtube
âm lượng 30%
tăng âm lượng
hẹn giờ 5 phút
```

Fall-through examples:

```text
cho cái đèn phía sau máy sáng lên
mở cái app tôi hay xem video
để âm thanh vừa đủ nghe thôi
```

## Exit Gate

- direct phrases execute with zero Qwen calls;
- ambiguous/natural paraphrases still reach Qwen;
- no existing tool capability is lost;
- latency report proves the difference between direct vs model route.

---

# Upgrade 4 — Reduce Qwen work: scoped tools, terminal short-circuit, prompt/cache tuning

## Outcome

When Qwen is required, one tool decision is as cheap as practical.

## Steps

- [x] expose only relevant tool groups per request;
- [x] cap tool-routing output to ~32–64 tokens initially;
- [x] keep context at 1024 benchmark / 2048 normal unless measured need requires 4096;
- [x] eliminate explanatory preamble before function call;
- [x] do not send successful terminal tool result back to Qwen if no semantic follow-up is needed;
- [x] evaluate MNN prompt/prefix cache using the pinned runtime API;
- [x] keep model resident during active interaction session when memory allows;
- [x] benchmark 4/8/16 exposed tool definitions;
- [x] compare CPU/OpenCL/Vulkan from Upgrade 1 using the optimized prompt.

## Product Gate

Warm single-tool model route target: **<=5 s preferred**. Above **10 s** requires profiling before adding more agent complexity.

---

# Upgrade 5 — Add one authoritative TaskSessionStore and CapabilityCoordinator

## Outcome

Remove scattered task/permission/live-service truth before adding more autonomy.

## Task States

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

## Capability States

```text
DISABLED
CONNECTING
READY
DEGRADED
```

Initial capabilities:

```text
MODEL
NETWORK
NOTIFICATIONS
CONTACTS
ACCESSIBILITY (future but state contract now)
VOICE (future but state contract now)
```

## Exit Gate

UI, notifications, and executors observe one state source; user cancellation is deterministic; stale system-setting state is not mistaken for live capability readiness.

---

# Upgrade 6 — Skill Engine for known multi-step workflows

## Outcome

Existing multi-step operations stop repeatedly querying Qwen for mechanical steps.

## Contract

```kotlin
data class Skill(
    val id: String,
    val version: Int,
    val triggers: List<SkillTrigger>,
    val steps: List<SkillStep>,
    val maxWallMs: Long,
    val risk: ToolRisk
)

sealed interface SkillStep {
    data class CallTool(...) : SkillStep
    data class WaitFor(...) : SkillStep
    data class Assert(...) : SkillStep
    data class BranchOnResult(...) : SkillStep
    data class AiSlot(...) : SkillStep
}
```

## Migrate Existing Phase-4 Workflows First

Candidate skills:

```text
open_and_search_app
prepare_message_to_contact
send_message_after_confirmation
read_notifications_from_person
web_search_and_summarize
```

Do not convert a one-step Android API action into a skill.

## Exit Gate

At least two existing Phase-4 workflows show fewer Qwen invocations than before while preserving success rate.

---

# Upgrade 7 — Stuck detection, postconditions, and recovery

## Outcome

Bounded agent/skills fail safely instead of retrying blindly.

## Reuse Strategy

Port/adapt PokeClaw `StuckDetector` concepts/code only after adding required Apache-2.0 attribution. Do not port its large orchestrator wholesale.

## Signals

```text
same action repeated
same error repeated
expected-mutating tool produced no state change
step budget near/exceeded
wall-clock deadline exceeded
malformed Qwen output repeated
future UI snapshot unchanged
```

## Recovery Levels

```text
1 refresh/retry with explicit evidence
2 switch strategy / escalate skill -> one model decision
3 terminate truthfully
```

## Exit Gate

Synthetic stuck cases terminate within budget and do not cause infinite loops.

---

# Upgrade 8 — Accessibility semantic subsystem

## Outcome

Add Accessibility only after the Phase-4 direct-tool base is migrated and stable.

## Architecture

```text
compact UiSnapshot
semantic temporary refs (@e1, @e2...)
resourceId/role/contentDescription/text selector priority
stale-ref rejection
postcondition verification
full tree only fallback
screenshot/vision later only if needed
```

## License Rule

Mobilerun Portal is reference-only under current A.B licensing plan. Reimplement patterns independently rather than copying AGPL code.

## Exit Gate

At least three representative app screens work on the POCO without blind coordinate macros.

---

# Upgrade 9 — Communication hardening

## Outcome

Keep existing Phase-4 communication capability but migrate it to prepare/confirm/commit semantics.

## Contract

```text
prepare_message
 -> normalized recipient/text preview
 -> deterministic confirmation
 -> one-use authorization token
 -> commit_message
 -> verified/unknown/failure result
```

Equivalent flow for calls.

## Exit Gate

Confirmed payload cannot be changed by a subsequent model generation; ambiguous recipient never auto-sends.

---

# Upgrade 10 — Web/retrieval hardening

## Outcome

Keep existing web search but treat fetched content as untrusted data and minimize model context.

## Requirements

```text
search result IDs
source metadata
response-size cap
network timeout
compact extraction
prompt-injection content never receives tool/policy authority
browser stays closed unless explicitly requested
```

---

# Upgrade 11 — Push-to-talk voice

## Outcome

Add voice input/output on top of the migrated router rather than directly to the old agent loop.

```text
PTT -> STT -> PipelineRouter -> tools/model/skills -> TTS
```

Exit gate: common commands work end-to-end by voice while screen is on.

---

## Implementation status — 2026-08-11

Upgrades 0–13 are implemented in the working tree with unit/build coverage. Upgrade 14 automation is implemented and has been exercised against the physical POCO X6 Pro; it is intentionally not marked as a release pass while the manual/device gates below remain open.

| Area | Evidence | Status |
|---|---|---|
| Build and tests | `.\gradlew.bat --no-daemon test assembleDebug` | PASS |
| Typed routing/tools, task state, skills, stuck recovery | Kotlin unit tests and current architecture audit | PASS in host tests |
| Accessibility semantic layer | service/resolver/postcondition code and tests | Device enablement pending |
| Voice PTT | SpeechRecognizer/TTS coordinator and permission gates | Manual POCO flow pending |
| Wake/assistant | `VoiceInteractionService` + resolved `VoiceInteractionSessionService` manifest action + show/hide session boundary; default detector reports degraded | A.B assistant/provider not configured on POCO |
| Direct POCO commands | flashlight, volume, bounded web, truthful timer limitation, SMS confirmation; open-app reached the POCO microG battery-optimization prompt | Measured with outbound confirmations canceled; microG/YouTube follow-up remains open |
| MNN model route | Latest CPU warm-repeat 10/10 valid confirmations: 9.488 s cold, 9.247–9.481 s warm; OpenCL valid but slower; Vulkan bounded timeout | CPU promoted to default order; bounded 10-run thermal/memory sample recorded; long-duration battery/thermal qualification and broader corpus reliability pending |
| Native metrics/cache/schema sweep | prompt/prefill/decode fields, rendered-prefix cache hit (`114` reused tokens), inert 4/8/16 schema sweep | Measured on POCO |

The final-APK full corpus was rerun with bounded ADB/UI-dump watchdogs and completed in `201.8 s`: direct device, bounded web, truthful timer, and canceled SMS evidence were collected; the open-app row still lacked a verified YouTube postcondition. The latest targeted CPU model rerun remained valid on `2/2` confirmations (`10,832 ms` cold, `8,675 ms` warm), with no outbound commit and thermal status `0`. The corpus is therefore operationally bounded, but the physical acceptance gates and open-app verification remain intentionally pending. The typed runtime now also enforces each `ToolSpec.timeoutMs` and interrupts the active tool future on cancellation, covered by two new AgentCore tests.

Open physical gates are Accessibility enablement, selecting A.B as the system assistant, providing a real wake-word detector, manual PTT/notification/contact/call/YouTube/timer flows, broader model-route corpus reliability, and long-duration thermal/battery/memory qualification. `scripts/verify-upgrade-v2.ps1` exits `2` while these remain pending.

# Upgrade 12 — Wake word and screen-off assistant lifecycle

## Outcome

Low-power idle service detects A.B wake phrase, then starts an interaction session; full LLM inference is not continuously active.

## Exit Gate

```text
screen off
-> wake phrase
-> STT
-> router
-> tool/web action
-> spoken truthful result
-> return to low-power idle
```

Measure battery drain and HyperOS background-kill behavior on the POCO.

---

# Upgrade 13 — Procedural skill learning

## Outcome

Successful repeated trajectories may become **candidate skills**, never silently trusted code.

Flow:

```text
successful repeated trajectory
-> candidate recipe
-> user/developer inspection
-> test replay
-> explicit approval
-> versioned skill registration
```

No automatic persistence from one run.

---

# Upgrade 14 — Release hardening and acceptance suite

## Required Acceptance Corpus

### Device

```text
bật/tắt đèn pin
mở app
volume/media
timer/alarm
battery/device state
```

### Information

```text
notification lookup
contact resolution
web search
```

### Communication

```text
prepare message
ambiguous contact
confirm/deny send
call confirmation
```

### Agent/skills

```text
known skill with zero repeated LLM mechanical steps
conditional workflow
stuck/recovery case
cancellation
```

### Performance

```text
cold model load
warm Qwen route
Tier-0 latency
memory pressure
thermal degradation
idle battery after wake-word phase
```

## Definition of Done

A.B is ready for daily use when:

1. Legacy Phase-4 capabilities still work after migration.
2. Common deterministic commands do not call Qwen.
3. Required Qwen single-tool routes are measured in seconds rather than near one minute under the selected warm profile.
4. Skills reduce repeated model decisions for known workflows.
5. Every tool passes typed validation/capability/policy checks.
6. Outbound actions use deterministic confirmation and immutable approved payloads.
7. Accessibility uses semantic snapshots and postcondition verification.
8. Voice and screen-off sessions return to low-power idle.
9. Failures are truthful, bounded, and cancellable.
10. Normal phone use remains responsive while A.B is idle.

---

# Recommended Execution Order From Today

Do **not** execute all phases at once. The immediate implementation sequence is:

```text
Upgrade 0  audit current Phase-4 code
    ↓
Upgrade 1  profile the ~1 minute latency
    ↓
Upgrade 2  typed ToolRegistry wrapper around existing tools
    ↓
Upgrade 3  Pipeline Router Tier 0/2
    ↓
Upgrade 4  optimize Qwen route
    ↓
Upgrade 5  TaskSession/Capability state
    ↓
Upgrade 6  Skill Engine
    ↓
Upgrade 7  recovery
    ↓
then Accessibility/voice/wake-word
```

The first implementation plan to execute should cover **Upgrade 0 + Upgrade 1 only**, because those two tasks reveal the actual current code structure and performance bottleneck before any invasive refactor.
