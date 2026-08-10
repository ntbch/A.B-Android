# A.B device acceptance checklist

This checklist is for the physical POCO X6 Pro (`2311DRK48`, Android 16 / SDK 36). It keeps device-only gates separate from host build evidence and never treats an unverified outbound action as a pass.

## Automated evidence

Run from the repository root:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-upgrade-v2.ps1 `
  -DeviceSerial Y9ZXD6OZ75KBLBTO -SkipBuild -SkipInstall -SkipPrompts

powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\benchmark-poco.ps1 `
  -DeviceSerial Y9ZXD6OZ75KBLBTO -WarmModel -WarmRepeatCount 10 `
  -ModelWaitSeconds 25 -SkipInstall -SkipRestore `
  -AdbTimeoutSeconds 10 -UiDumpTimeoutSeconds 4
```

Record the verifier's `GATE EVIDENCE` table and the benchmark's `SNAPSHOT BEFORE/AFTER` JSON. A model-route run passes only when the UI contains an SMS/call confirmation and the harness cancels it; no SMS or call commit is part of this checklist.

Latest bounded POCO evidence (2026-08-11): CPU-first warm-repeat was valid on 10/10 runs, with `generationMs=9,488 ms` cold and `9,247–9,481 ms` warm. The snapshot moved from PSS/swap `1,482,226/166 KB` and CPU/skin/battery temperatures `47.9/43.7/39.8 °C` to `1,450,177/154,332 KB` and `53.3/45.7/40.0 °C`; battery stayed at `30%` and thermal status stayed `0`. This is a bounded sample only; it does not replace unplugged long-duration qualification.

The full corpus also passed flashlight, volume, truthful timer limitation, and bounded web search checks. Both direct and model SMS proposals produced confirmations and were canceled by the harness. The open-app case stopped at the POCO microG battery-optimization prompt and remains an environment-specific manual follow-up.

The deployed APK also exposed `HỦY` during an in-flight model request on the POCO; after the tap the button disappeared and the late model callback did not restore the task UI. This verifies the cancellation boundary, not native compute interruption.

Final-APK targeted recheck: `-Backend CPU -WarmModel -WarmRepeatCount 2` produced valid SMS confirmation and cancellation on `2/2` runs. Cold run generation was `10,832 ms`; warm run generation was `8,675 ms`; both reported actual CPU backend and thermal status `0`. A separate targeted cold model route measured `10,568 ms` generation and was also canceled. A full-corpus rerun timed out at `web-route` before the model cases and is intentionally not counted as a pass.

With the bounded ADB/UI watchdogs, the complete corpus later finished in `201.8 s`: flashlight, volume, web, and both canceled SMS confirmations were observed; timer remained a truthful limitation; and flashlight was restored OFF. `open-app` reached the microG GmsCore battery-optimization prompt without a verified YouTube postcondition, so that row remains pending. A targeted rerun reproduced the prompt in `10,270 ms`; the final snapshot reported thermal status `0`, battery `32%`, and CPU/skin/battery temperatures `50.1/45.4/39.9 °C`.

After per-tool timeout/cancellation enforcement, a fresh targeted model smoke still produced a valid SMS confirmation and was canceled without commit. CPU generation was `12,568 ms`; thermal status remained `0`. UI-dump watchdog warnings during busy confirmation were bounded and did not alter the result.

## Canonical acceptance matrix

The v2 design asks for at least 20 parameterized tasks over time. This is the current matrix; a host-test result or a truthful device limitation is not counted as a physical release pass.

| # | Category | Canonical request | Current evidence |
|---:|---|---|---|
| 1 | Device | `bat den pin` | POCO PASS; restore required |
| 2 | Device | `tat den pin` | POCO PASS as restore action |
| 3 | Device | `mo YouTube` | POCO pending: microG battery-optimization prompt |
| 4 | Device | `am luong 30 phan tram` | POCO PASS |
| 5 | Device | `tang am luong` | Parser/host covered; POCO repetition pending |
| 6 | Device/media | `phat nhac` | Parser/host covered; active-player postcondition pending |
| 7 | Device/timer | `dat hen gio 5 phut` | POCO truthful limitation: no clock handler |
| 8 | Device state | `pin con bao nhieu` | Parser/host covered; POCO repetition pending |
| 9 | Information | `xem thong bao cua Nam` | Skill/host covered; Notification access pending |
| 10 | Information | `tim lien he Nam` | Parser/host covered; Contacts permission/data pending |
| 11 | Information/web | `search thoi tiet Ha Noi hom nay` | POCO PASS with bounded source IDs/URLs |
| 12 | Communication | `nhan Nam la 10 phut nua toi` | POCO confirmation shown and canceled |
| 13 | Communication/model | `nhan cho Nam ve viec den muon` | POCO model confirmation shown and canceled |
| 14 | Communication/ambiguity | `nhan cho Nam va Minh ve viec den muon` | Ambiguous-recipient device evidence pending |
| 15 | Communication/call | `goi cho Nam` | Confirmation/call-flow device evidence pending |
| 16 | Skill | `soan tin nhan cho Nam: toi den tre` | Skill/host covered; outbound device flow pending |
| 17 | Skill/branch | conditional tool workflow with success/failure branch | Skill/host tests PASS; POCO replay pending |
| 18 | Recovery | malformed/repeated model tool output | Stuck-detector host tests PASS; device corpus pending |
| 19 | Cancellation | cancel an in-flight typed model task | Deployed POCO PASS; late callback ignored |
| 20 | Voice | screen-on PTT deterministic command | Manual POCO flow pending |
| 21 | Screen-off | assistant entry/wake -> STT -> result -> re-arm | A.B assistant/provider selection pending |

## Accessibility semantic layer

1. Open A.B's device capability screen and tap `BẬT SEMANTIC ACCESSIBILITY`.
2. Enable `A.B` in Android Accessibility settings.
3. Confirm the secure setting contains:

   ```text
   com.ab.assistant/com.ab.assistant.accessibility.AbAccessibilityService
   ```

4. Return to A.B and confirm `Accessibility: sẵn sàng`.
5. Exercise three representative screens without coordinate macros:
   - Android Settings: resolve a stable resource/role and verify package identity.
   - Messaging: resolve recipient/message controls and verify the confirmation text changes.
   - Media/player: resolve a media control and verify the postcondition after the action.
6. Capture the snapshot ID, selected semantic reference, and postcondition result for each screen. A stale snapshot reference must be rejected.

## System assistant and voice

1. Select A.B as the Android system voice assistant.
2. Confirm:

   ```text
   voice_interaction_service=com.ab.assistant/com.ab.assistant.voice.AbVoiceInteractionService
   ```

The deployed APK must also resolve the `android.service.voice.VoiceInteractionSessionService` action to `AbVoiceInteractionSessionService`; the POCO package dump now confirms this registration.

3. Grant microphone permission when A.B requests it.
4. Tap `BẮT ĐẦU VOICE PTT` and run a deterministic command, an information request, and a confirmation-required SMS request. Deny the SMS request and confirm no message is sent.
5. Hide/close the voice session during recognition and during speech output. Confirm the state returns to `IDLE` and no audio continues.

## Wake-word gate

The current APK intentionally reports the default wake detector as `DEGRADED`. Android SDK 36 exposes no public `AlwaysOnHotwordDetector` API in the available SDK, so this gate cannot pass until a real system/DSP provider is registered through `WakeWordDetectorProvider`. Do not substitute SpeechRecognizer as an always-on detector.

After a real provider is registered, verify:

```text
screen off
-> wake phrase
-> STT
-> router/tool or model
-> truthful spoken result
-> detector re-armed
```

Record wake latency, screen-off behavior, background-kill behavior, and idle battery drain.

## Thermal, battery, and memory qualification

Use the 10-run benchmark output as a bounded sample, not a release claim. For daily-use qualification, repeat with the phone unplugged and screen-off/idle intervals between runs. Record:

- every `generationMs`, backend, and confirmation result;
- `totalPssKb` and `totalSwapPssKb` before/after;
- CPU, skin, battery temperatures and `thermalStatus`;
- battery level before/after and whether charging was active;
- whether normal phone use remains responsive.

The CPU-first POCO profile is currently the selected warm profile, but the preferred 5-second target and long-duration thermal/battery/memory qualification remain open.
