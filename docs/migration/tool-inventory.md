# A.B legacy tool inventory

Inventory from the pre-v2 source tree at commit `2f65ddc`.

| Tool | Current class/function | Input shape | Permissions/capabilities | Deterministic | Calls Qwen | Accessibility | Risk | Known failure mode |
|---|---|---|---|---|---|---|---|---|
| flashlight | `ToolRegistry.execute` -> `FlashlightController.execute` | `FlashlightOn/Off` | Camera permission; camera flash feature | Yes | Only when input is not parsed directly | No | Device state | Missing flash or denied Camera permission |
| open_app | `ToolRegistry.openApp` | `OpenApp(appName)` | Launcher activity | Yes | Only when input is not parsed directly | No | Low | No match or ambiguous app label |
| volume | `ToolRegistry.setVolume` | `SetVolume(stream, 0..100)` | Audio service | Yes | Only when input is not parsed directly | No | Low | Missing audio service or security error |
| volume adjustment | `ToolRegistry.adjustVolume` | `AdjustVolume(stream, UP/DOWN)` | Audio service | Yes | Direct Tier-0 route for explicit up/down | No | Low | Missing audio service or security error |
| media | `ToolRegistry.media` | `Media(PLAY/PAUSE/NEXT/PREVIOUS)` | Audio service/media key dispatch | Yes | Only when input is not parsed directly | No | Low | Missing audio service or blocked media dispatch |
| timer | `ToolRegistry.setTimer` | `SetTimer(1..1440)` | Alarm intent handler | Yes | Only when input is not parsed directly | No | Low | No clock app handles the intent |
| alarm | `ToolRegistry.setAlarm` | `SetAlarm(hour, minute, label)` | Alarm intent handler | Yes | Only when input is not parsed directly | No | Low | No clock app handles the intent |
| notifications | `ToolRegistry.readNotifications` | `ReadNotifications(filter?)` | Notification listener access | Yes | Only when input is not parsed directly | No | Information | Access disabled or empty in-memory cache |
| contacts | `ToolRegistry.findContact` -> `ContactLookup.find` | `FindContact(name)` | `READ_CONTACTS` | Yes | Only when input is not parsed directly | No | Information | Permission denied or no match |
| web search | `ToolRegistry.webSearch` -> `BingRssSearchClient.search` | `WebSearch(query)` | Network available; `INTERNET` | Yes | Only when input is not parsed directly | No | External data | Timeout, network error, empty results; response is untrusted text |
| send SMS | `ToolRegistry.sendSms` | `SendSms(recipient, message)` | `READ_CONTACTS` for name, `SEND_SMS`, SMS service | Yes | Direct parser can bypass Qwen; model route still parses JSON | No | High/outbound | Ambiguous recipient, missing permission, unsupported device; confirmation is required |
| dial contact | `ToolRegistry.dialContact` | `DialContact(recipient)` | `READ_CONTACTS` for name, dialer handler | Yes | Only when input is not parsed directly | No | Medium | Ambiguous/missing contact or no dialer; opens dialer, does not place call directly |
| device state | `ToolRegistry.readDeviceState` | `ReadDeviceState` | Android battery service | Yes | Direct Tier-0 route for explicit status requests | No | Information | Battery service or percentage unavailable |

Cross-cutting facts:

- `UserCommandParser` handles conservative explicit Vietnamese/English phrases before model invocation.
- `AgentCore` is the current permission and confirmation gate; `ToolRegistry` remains the policy source.
- Accessibility now has a registered semantic service and snapshot/postcondition layer; PTT voice is wired through `VoiceSessionCoordinator`; screen-off wake-word lifecycle is bounded by `AbVoiceInteractionService` but its real system/DSP detector remains a device-gated dependency.
- Tool-call schemas are allowlisted by `ToolCommandParser`; unknown tools, extra keys, prose, Markdown, escaped values, and out-of-range values are rejected.
