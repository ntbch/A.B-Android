# A.B Android — Full Project Design

## 0. Project Identity

- Product/display name: **A.B**
- Android project/repository slug: **`ab-android`**
- Default assistant invocation name in product copy: **“A.B”**
- The exact wake-word acoustic phrase/pronunciation will be tuned during the screen-off voice phase; the product name remains A.B regardless of wake-engine implementation.

## 1. Product Vision

A.B Android is a local-first personal assistant APK designed for a POCO X6 Pro 8GB/256GB. The assistant should understand natural Vietnamese commands, choose and invoke a bounded set of Android and web tools, perform short multi-step workflows, and respond through text or voice.

The product is not intended to be a general autonomous AI that has unrestricted control over the phone. It is an explicit, tool-driven assistant in which the LLM decides *what* action to request, while Kotlin code decides *whether* the action is valid and *how* Android executes it.

The long-term target experience is:

> User says “A.B, xem Nam có nhắn gì không, nếu có thì bảo 10 phút nữa tao tới.”
>
> The phone wakes the assistant, transcribes the command, the local model plans a short tool sequence, reads permitted notifications, asks for confirmation before sending an outbound message, executes the action, and speaks the result back — without opening a chatbot UI and without sending the user's command to a cloud LLM.

## 2. Core Product Principles

### Local-first
The LLM, agent orchestration, tool selection, basic speech processing, and personal state should run on-device whenever practical. Internet access is used only by tools that inherently require it, such as web search or a remote API.

### Fast over clever
The assistant is optimized for short operational commands, not long-form reasoning. It should prefer a small, fast model and deterministic tools over a larger model that spends time reasoning.

### Tools over UI automation
Preferred execution order:

1. Android API
2. Android Intent / deep link
3. app-specific supported integration
4. Accessibility tree
5. screenshot/vision only if a future feature genuinely requires it

### Explicit authority boundaries
The model never receives unrestricted operating-system authority. Kotlin owns permission checks, schema validation, confirmation rules, loop limits, timeouts, and execution.

### Graceful degradation
If a tool is unavailable, a permission is missing, OpenCL fails, network is offline, or a target app changes its UI, the assistant should fail clearly and safely rather than improvising dangerous actions.

## 3. Target Device and Constraints

Primary development and acceptance device:

- POCO X6 Pro
- 8GB RAM
- 256GB storage
- Android / HyperOS
- MediaTek Dimensity 8300-Ultra

Design constraints:

- one active LLM only;
- INT4-class quantization;
- short context, initially 2048 tokens and no more than 4096 unless a measured need appears;
- no persistent full-LLM inference while idle;
- no continuous full speech recognition;
- no vision model in the initial product;
- no dependency on root access;
- no requirement for Android Studio;
- test on real hardware throughout development.

## 4. Locked Technical Stack

### Development environment

- Editor/IDE: Antigravity as the preferred environment, VS Code as a valid alternative
- Host OS: Windows is acceptable
- Build system: Gradle Wrapper from the command line
- Android tooling: Android SDK command-line tools + ADB
- Native build: Android NDK + CMake
- Device testing: physical POCO X6 Pro over USB ADB first, wireless ADB later

### Android application

- Language: Kotlin
- UI: minimal native Android UI for the first phases; Compose may be used if it simplifies the project, but the agent core must not depend on UI technology
- Coroutines: Kotlin coroutines for asynchronous model/tool execution
- Serialization: strict JSON or typed Kotlin serialization for tool calls and tool results

### Local LLM

- Model: Qwen3.5-2B
- Quantization: INT4
- Runtime: Alibaba MNN
- Backend priority: OpenCL first, optimized CPU fallback
- Thinking/reasoning mode: disabled
- Initial context: 2048 tokens
- Maximum normal context target: 4096 tokens
- Tool-call output target: <=128 generated tokens
- Short natural-language response target: <=256 generated tokens
- Maximum agent tool iterations: 5

The model is replaceable behind a stable inference interface. Agent and tool code must not be hard-coded to Qwen-specific implementation details beyond the prompt/tool-call adapter.

## 5. System Architecture

```text
                         ┌──────────────────────┐
                         │        User          │
                         │   Voice / Text / UI  │
                         └──────────┬───────────┘
                                    │
                         ┌──────────▼───────────┐
                         │     Input Layer      │
                         │  Text / STT / Wake   │
                         └──────────┬───────────┘
                                    │
                         ┌──────────▼───────────┐
                         │     Agent Core       │
                         │ Session + Policy +   │
                         │ Prompt + Loop Guard  │
                         └──────────┬───────────┘
                                    │
                         ┌──────────▼───────────┐
                         │    Local LLM Layer   │
                         │ Qwen3.5-2B via MNN   │
                         └──────────┬───────────┘
                                    │
                         structured tool call
                                    │
                 ┌──────────────────▼──────────────────┐
                 │          Tool Runtime               │
                 │ Registry → Validate → Authorize →   │
                 │ Confirm → Execute → Normalize       │
                 └───────┬─────────────┬───────────────┘
                         │             │
            ┌────────────▼──────┐   ┌──▼────────────────┐
            │   Android Tools   │   │   Internet Tools  │
            │ apps/media/alarm  │   │ web/API/network   │
            │ contacts/notifs   │   └───────────────────┘
            │ accessibility     │
            └────────────┬──────┘
                         │
                 structured result
                         │
                         ▼
                 Agent Core / LLM
                         │
                  final short reply
                         │
            ┌────────────▼────────────┐
            │ Output Layer: UI / TTS  │
            └─────────────────────────┘
```

## 6. Major Modules

### 6.1 App Shell

Responsibilities:

- application lifecycle;
- initial setup and permissions screen;
- text command UI;
- settings;
- status/error display;
- model download/import/setup status;
- foreground interaction surface when required.

It must not contain agent logic directly.

### 6.2 Model Runtime Module

Responsibilities:

- initialize MNN;
- select OpenCL or CPU fallback;
- load/unload model;
- tokenize prompt;
- run inference;
- stream or collect generation;
- expose model health and timing metrics;
- cancel generation on timeout/user cancellation.

Public conceptual interface:

```text
generate(request) -> ModelResponse
cancel(requestId)
getStatus() -> ModelStatus
```

The rest of the application should not know whether the underlying runtime is MNN, llama.cpp, MediaTek NeuroPilot, or another backend.

### 6.3 Prompt and Tool-Calling Adapter

Responsibilities:

- build the system prompt;
- serialize available tool schemas;
- encode conversation/tool history;
- force non-thinking behavior;
- parse model output into either a tool call or final reply;
- repair malformed output at most once.

Output types:

```text
ToolCall(name, arguments)
FinalReply(text)
InvalidOutput(raw)
```

### 6.4 Agent Core

The Agent Core is the decision loop around the LLM.

Responsibilities:

- create a bounded session;
- supply only relevant tools when possible;
- call the model;
- validate candidate tool call;
- ask confirmation when required;
- execute tool;
- append normalized result;
- decide whether another model turn is necessary;
- stop at five tool steps;
- create the final user response.

The Agent Core does not directly access Android APIs.

### 6.5 Tool Registry

Every callable capability is represented by a typed tool definition.

Each tool defines:

- stable name;
- human-readable purpose;
- argument schema;
- result schema;
- Kotlin executor;
- required Android permissions;
- risk level;
- confirmation policy;
- whether it can run while screen is locked;
- timeout;
- availability check.

Example conceptual definition:

```text
name: flashlight
args: { enabled: boolean }
risk: LOW
confirmation: NEVER
permissions: camera/torch capability if required by implementation
screenLocked: ALLOWED
```

### 6.6 Tool Runtime

Execution pipeline:

```text
Model ToolCall
   ↓
Tool exists?
   ↓
Arguments parse?
   ↓
Schema valid?
   ↓
Permission available?
   ↓
Risk policy allows action?
   ↓
Confirmation if required
   ↓
Execute Kotlin tool
   ↓
Normalize ToolResult
```

The model cannot bypass any stage.

### 6.7 Voice Module

Eventually contains three separate concerns:

1. Wake-word detector
2. Speech-to-text
3. Text-to-speech

They remain independent modules so a weak component can be replaced without changing the agent.

### 6.8 Accessibility Automation Module

Only introduced after direct tools are stable.

Responsibilities:

- expose a compact representation of visible Accessibility nodes;
- select nodes by stable attributes where possible;
- execute click/scroll/type/back actions;
- verify state change after an action;
- enforce app allowlist/denylist;
- prevent the LLM from receiving an unbounded raw accessibility tree.

Accessibility is a fallback mechanism, not the default integration path.

### 6.9 Web/API Module

Responsibilities:

- web search provider abstraction;
- HTTP GET/POST tool for explicitly allowed APIs;
- network timeout and response-size limits;
- content extraction into short text results;
- never expose secrets/API keys to the LLM prompt.

### 6.10 Local Data and Memory Module

Initial project does not need long-term semantic memory.

Later, this module may store:

- user-defined aliases such as “mẹ” or “nhà”;
- tool preferences;
- safe app mappings;
- recent bounded conversation state;
- confirmation preferences;
- user-created routines.

Sensitive data should use Android-protected storage where appropriate. Raw notification/message content should not be retained by default after the active task ends.

## 7. Tool Catalog

### Phase A — Core device tools

- `flashlight(enabled)`
- `open_app(app)`
- `set_volume(stream, level)`
- `media_play()`
- `media_pause()`
- `media_next()`
- `media_previous()`
- `set_timer(duration)`
- `set_alarm(time, label)`

### Phase B — Information tools

- `read_notifications(filter)`
- `find_contact(name)`
- `get_device_state()`
- `web_search(query)`
- `open_url(url)`

### Phase C — Communication tools

- `compose_message(channel, contact, text)`
- `send_message(channel, contact, text)`
- `make_call(contact)`

Where possible, compose-before-send is preferred. Sending and calling are externally consequential actions and require confirmation by default.

### Phase D — File and personal-data tools

- `search_files(query, type, dateRange)`
- `read_text_file(fileRef)`
- `search_notes(query)` if a supported note source is added
- `calendar_query(range)`
- `calendar_create(event)`

### Phase E — UI automation tools

- `read_screen()`
- `tap(target)`
- `scroll(direction)`
- `type_text(text)`
- `press_back()`

These are enabled only for supported/allowed workflows.

## 8. Example Agent Flows

### Simple one-step action

User:

> “Bật đèn pin.”

Model:

```json
{"tool":"flashlight","arguments":{"enabled":true}}
```

Tool result:

```json
{"ok":true,"state":"on"}
```

Final response:

> “Đã bật đèn pin.”

### Short multi-step command

User:

> “Mở Spotify rồi phát nhạc.”

Possible sequence:

```text
open_app("Spotify")
→ result
media_play()
→ result
final reply
```

### Web search while screen is off

User:

> “A.B, search xem tối nay MU đá lúc mấy giờ.”

Sequence:

```text
wake word
→ STT
→ web_search(query)
→ compact search result
→ local LLM summarizes
→ TTS speaks result
```

No browser needs to be opened unless the user asks to view the source.

### Messaging with confirmation

User:

> “Nhắn cho Nam là 10 phút nữa tao tới.”

Sequence:

```text
find_contact("Nam")
→ resolved contact
→ proposed send_message(...)
→ confirmation UI/voice
→ execute if confirmed
→ final response
```

If two contacts named Nam exist, the tool returns ambiguity and the assistant asks the user to choose. The model must not guess the recipient.

## 9. Confirmation and Risk Policy

### Low-risk — execute immediately

Examples:

- flashlight;
- media controls;
- volume;
- open app;
- timer;
- read already-authorized device state.

### Medium-risk — contextual confirmation may be required

Examples:

- creating alarms/calendar entries;
- opening an external URL;
- typing text into another app without submitting it.

### High-risk — explicit confirmation required

Examples:

- send message;
- place call;
- delete file/data;
- post/publish externally;
- purchase;
- transfer money;
- change security settings;
- submit forms with consequential data.

### Prohibited autonomous behavior

The assistant must not be designed to bypass Android permission/security controls, defeat app protections, silently perform financial transactions, or grant itself elevated authority.

## 10. Permissions Strategy

Permissions are requested progressively, only when a capability needs them.

Initial MVP should request the minimum required for flashlight and basic app functions.

Later permissions/settings may include:

- microphone;
- notification access;
- contacts;
- phone/call capability;
- accessibility service;
- foreground-service-related declarations;
- file/media access where applicable.

The setup screen should show each capability as:

```text
Capability
Status: Enabled / Disabled
Why A.B needs it
Open Android setting / Grant
```

No tool is advertised as available until its actual runtime availability check succeeds.

## 11. Voice and Screen-Off Architecture

### Stage 1 — Push to talk

```text
button
→ start STT
→ recognized text
→ Agent Core
→ TTS response
```

This validates voice without background complexity.

### Stage 2 — Wake word

Idle state:

```text
Wake-word detector: active
STT: inactive
LLM inference: inactive
TTS: inactive
```

On “A.B”:

```text
wake detected
→ activate interaction session
→ STT listens for command
→ local agent handles request
→ TTS reply
→ session closes
→ return to low-power idle
```

### Screen-off principle

The assistant should perform tools that are technically allowed while the screen remains off. It should not wake the display just to execute a web search, timer, media operation, or spoken query.

If Android requires user unlock for a protected operation, A.B should explicitly say that unlock is required rather than trying to bypass the lock screen.

## 12. Model Prompting Policy

System behavior should strongly constrain the model:

- respond in Vietnamese unless another language is requested;
- use tools for real-world actions and fresh external information;
- never claim an action succeeded without a successful tool result;
- never invent tool names;
- emit one tool call at a time unless the runtime explicitly supports a validated batch;
- avoid chain-of-thought style output;
- keep final answers concise;
- ask the user only when a required argument cannot be resolved safely;
- respect tool error messages;
- stop after the configured maximum step count.

The application should provide only the tools relevant to the current capability set to reduce model confusion and prompt size.

## 13. Session and Context Management

A task session contains:

- system/tool policy;
- current user command;
- minimal recent conversational context if needed;
- tool call/result pairs for the current task;
- final answer.

The app should not feed an unlimited chat history into the model.

Default behavior:

- new operational command starts a small task context;
- pronouns/follow-up commands may reuse a short recent session window;
- long-term personalization is stored as structured memory, not raw chat logs injected into every prompt.

## 14. Performance Design

### Goals

The user should experience the assistant as an action interface, not a chatbot waiting for long generation.

Performance tactics:

- INT4 model;
- short context;
- thinking disabled;
- output length capped;
- relevant-tool filtering;
- no natural-language preamble before tool calls;
- model remains resident only while useful and memory pressure permits;
- OpenCL preferred, CPU fallback automatic;
- avoid repeated model calls when a deterministic Kotlin rule can complete the workflow;
- minimize data copied into prompt, especially Accessibility and web results.

### Thermal/battery behavior

The app should monitor long-running sessions and stop runaway loops. It should not continuously run LLM inference while idle.

If performance drops due to thermal throttling, the system should favor completing the current short task and returning to idle, rather than retrying generation repeatedly.

## 15. Failure Handling

### Model failure

- model unavailable: disable agent actions and surface setup status;
- generation timeout: cancel inference and report failure;
- malformed tool call: one strict repair attempt maximum;
- repeated malformed output: stop safely.

### Tool failure

Tool results always have a normalized form such as:

```json
{
  "ok": false,
  "code": "PERMISSION_MISSING",
  "message": "Notification access is disabled"
}
```

The model may choose a recovery action only when the error is recoverable and the agent still has remaining steps.

### Ambiguity

Do not guess high-consequence identifiers.

Examples:

- two contacts named Nam → ask user;
- multiple apps matching an alias → ask or use saved explicit mapping;
- ambiguous payment-related target → do not proceed.

### Accessibility drift

If an app UI changes and the expected node cannot be found, abort that UI sequence cleanly. Never blind-tap coordinates as a general recovery strategy.

## 16. Privacy and Security

### Local processing

Operational commands and personal data should stay local unless a selected tool inherently communicates externally.

### Data minimization

- do not retain raw notifications by default;
- do not save full transcripts indefinitely by default;
- retain only configuration and explicit structured memory;
- redact secrets from logs;
- do not include API tokens in model prompts.

### Execution audit

For development and optionally user-facing history, store a lightweight action record:

```text
time
user command summary
tool requested
confirmation status
result success/failure
```

Do not store sensitive message bodies unless explicitly required by the feature.

## 17. Observability and Developer Diagnostics

Development builds should expose:

- model load time;
- backend used: OpenCL/CPU;
- prompt token count;
- generation token count;
- time-to-first-token;
- generation tokens/second;
- tool selected;
- tool execution duration;
- agent step count;
- memory pressure warning;
- model/parser/tool errors.

Use structured logs with a dedicated tag namespace so `adb logcat` is practical without Android Studio.

Production builds should reduce or disable sensitive verbose logging.

## 18. Proposed Project Structure

```text
ab-android/
├── app/
│   ├── src/main/java/.../app/
│   │   ├── MainActivity.kt
│   │   ├── setup/
│   │   └── settings/
│   │
│   ├── src/main/java/.../agent/
│   │   ├── AgentCore.kt
│   │   ├── AgentSession.kt
│   │   ├── AgentPolicy.kt
│   │   └── AgentResult.kt
│   │
│   ├── src/main/java/.../model/
│   │   ├── ModelRuntime.kt
│   │   ├── MnnModelRuntime.kt
│   │   ├── PromptBuilder.kt
│   │   └── ToolCallParser.kt
│   │
│   ├── src/main/java/.../tools/
│   │   ├── Tool.kt
│   │   ├── ToolRegistry.kt
│   │   ├── ToolExecutor.kt
│   │   ├── device/
│   │   ├── communication/
│   │   ├── web/
│   │   ├── files/
│   │   └── accessibility/
│   │
│   ├── src/main/java/.../voice/
│   │   ├── SpeechToText.kt
│   │   ├── TextToSpeech.kt
│   │   └── WakeWord.kt
│   │
│   ├── src/main/java/.../permissions/
│   ├── src/main/java/.../memory/
│   └── src/main/java/.../diagnostics/
│
├── native/
│   └── mnn/ or native integration glue if required
│
├── docs/
│   ├── architecture/
│   ├── tools/
│   └── superpowers/specs/
│
├── gradle/
├── build.gradle.kts
├── settings.gradle.kts
└── gradlew
```

The exact physical structure may be adjusted once the Android project is scaffolded, but the logical boundaries should remain.

## 19. Testing Strategy

### Unit tests

Highest priority:

- tool-call parser;
- schema validator;
- tool registry;
- confirmation policy;
- agent step limit;
- malformed-output repair limit;
- permission/availability routing;
- ambiguity handling.

### Model contract tests

Maintain a Vietnamese command test corpus such as:

```text
"bật đèn pin"
"bật flash lên"
"mở Spotify"
"âm lượng xuống 30%"
"hẹn giờ 10 phút"
"search xem thời tiết ngày mai"
"nhắn Nam là 10 phút nữa tới"
```

For commands intended to call a tool, assert the expected tool and critical arguments.

### Instrumented Android tests

- flashlight;
- app launch;
- volume/media;
- alarms/timers;
- permission transitions;
- notification access;
- foreground/background lifecycle;
- lock-screen behavior for allowed tools.

### Physical-device acceptance tests

POCO X6 Pro is the primary performance source of truth.

Measure:

- cold model load;
- warm request latency;
- memory use;
- repeated-command stability;
- thermal behavior after repeated requests;
- idle battery impact once wake-word mode exists.

## 20. Development Roadmap

### Phase 0 — Toolchain

Goal: prove development without Android Studio.

Deliverables:

- JDK installed;
- Android SDK CLI installed;
- NDK/CMake installed;
- ADB sees POCO;
- `./gradlew assembleDebug` succeeds;
- debug APK installs on physical device;
- `adb logcat` workflow works.

Exit criterion: Hello World APK builds, installs, launches, and logs on POCO.

### Phase 1 — Local AI → Flashlight

Deliverables:

- MNN integrated;
- Qwen3.5-2B INT4 loads locally;
- text command input;
- structured tool-call parser;
- `flashlight(enabled)` tool;
- safe validation;
- on/off commands work in Vietnamese.

Exit criterion:

> typing “bật đèn pin” reliably turns the torch on through a model-selected tool call with no network dependency.

### Phase 2 — Core Android Assistant

Add:

- `open_app`;
- volume;
- media controls;
- timer/alarm;
- agent loop up to five steps;
- settings/capability status.

Exit criterion: common device commands work reliably without Accessibility.

### Phase 3 — Information Agent

Add:

- notification listener;
- contacts lookup;
- web search;
- short result summarization;
- network/error handling.

Exit criterion: assistant can answer fresh web queries and summarize permitted notifications.

### Phase 4 — Communication

Add:

- compose message;
- send message integrations where technically supported;
- call initiation;
- confirmation layer;
- contact ambiguity handling.

Exit criterion: outbound actions cannot happen without policy-required confirmation.

### Phase 5 — Voice

Add:

- push-to-talk STT;
- TTS;
- voice confirmation.

Exit criterion: user can complete the normal assistant flow without typing.

### Phase 6 — Accessibility Automation

Add only for workflows that cannot use cleaner APIs.

Deliverables:

- bounded screen representation;
- tap/scroll/type/back tools;
- target app allowlist;
- sequence verification;
- robust abort behavior.

Exit criterion: at least one selected third-party-app workflow works reliably without coordinate-based blind automation.

### Phase 7 — Wake Word and Screen-Off Operation

Add:

- wake-word engine;
- assistant service/lifecycle integration;
- low-power idle design;
- screen-off web search and local tools;
- battery testing.

Exit criterion:

> screen off → “A.B” → spoken command → tool/web action → spoken response → return to idle.

### Phase 8 — Personalization and Routines

Optional after the core assistant is stable:

- aliases;
- preferred apps/channels;
- home/work locations;
- user-defined routines;
- small structured memory;
- routine scheduling where Android permits.

### Phase 9 — Runtime Optimization

Only after functionality is validated:

- benchmark OpenCL vs CPU per workload;
- model residency policy;
- prompt caching if runtime supports it usefully;
- investigate MediaTek NeuroPilot/NPU only if access and measured benefit justify the complexity;
- consider smaller/faster replacement model if tool-call accuracy remains acceptable.

## 21. MVP vs Final Product Boundary

### MVP

The MVP is intentionally small:

```text
Text
→ Qwen3.5-2B local
→ one structured tool call
→ validated Kotlin executor
→ flashlight/open-app/volume
→ short result
```

### First useful product

```text
Voice push-to-talk
+ core Android tools
+ notifications
+ contacts
+ web search
+ confirmations
+ short multi-step workflows
```

### Target A.B experience

```text
Wake word while screen off
+ voice
+ local model
+ bounded multi-step agent
+ Android tools
+ web/API tools
+ safe communication
+ selected Accessibility workflows
+ TTS feedback
+ structured personalization
```

## 22. Explicit Non-Goals

The project should not expand into these areas before the main assistant is stable:

- unrestricted autonomous operation;
- always-running full LLM;
- large multimodal model;
- screenshot vision for every action;
- general computer-use agent attempting arbitrary apps with no integration work;
- payment automation;
- bypassing device lock/security;
- rooting the phone as a requirement;
- cloud LLM dependency for normal commands;
- long-term vector-memory/RAG system without a proven user need;
- supporting many Android devices before the POCO target is stable.

## 23. Definition of Done for the Project

The project can be considered successful when the following end-to-end scenario is reliable on the POCO X6 Pro:

1. Phone can be screen-off and idle without significant abnormal battery drain.
2. User says the configured wake word.
3. Assistant captures a Vietnamese command.
4. Local model understands the command and selects only registered tools.
5. Short multi-step workflows complete within bounded steps.
6. Direct Android APIs are used whenever possible.
7. Web queries work without opening a browser unless requested.
8. Personal/outbound actions are blocked by deterministic confirmation policy.
9. Missing permissions and ambiguous targets produce clear requests instead of guesses.
10. The assistant speaks or displays a concise, truthful result based on actual tool execution.
11. It returns to a low-power idle state after the interaction.
12. Normal phone usage remains responsive when the assistant is idle.

## 24. Architectural Decisions Locked Before Implementation

Unless testing proves one of them unsuitable, implementation starts from these decisions:

- Kotlin native Android APK;
- command-line Android build workflow;
- physical POCO X6 Pro as primary test device;
- Qwen3.5-2B INT4;
- MNN runtime;
- OpenCL preferred, CPU fallback;
- no thinking mode;
- 2K context initially;
- maximum five tool steps;
- typed Tool Registry;
- Kotlin-enforced confirmation and authorization;
- Android APIs/Intents before Accessibility;
- voice added after text agent is reliable;
- wake word added after push-to-talk is reliable;
- no root and no NPU integration in early phases.

## 25. Immediate Next Document

After this full-project design is approved, the next artifact should be an implementation plan broken into executable milestones. The first plan should cover only Phase 0 and Phase 1:

```text
toolchain
→ APK shell
→ MNN integration
→ Qwen model load
→ tool-call protocol
→ flashlight executor
→ real-device acceptance tests
```

No Phase 2+ implementation should start until that vertical slice works end-to-end on the actual POCO X6 Pro.
