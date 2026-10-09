# FlowForge — Test Results

Environment: Linux x86-64 cloud container, OpenJDK 21.0.12, Gradle 8.14.3, Kotlin 2.2.21,
bootstrap Android toolchain (see BUILD_INSTRUCTIONS.md). **No Android device or emulator was
available** (no KVM; emulator images are hosted on a blocked domain). Every result below comes from
the JVM or from static inspection of the built APK. Nothing has been executed on Android yet.

Reproduce everything with:
```bash
./gradlew check assembleDebug
```

## 1. Summary

| Suite | Tests | Result | What it exercises |
|---|---:|---|---|
| core · TextCleanerTest | 42 | ✅ pass | Cleanup modes, the spec's reference sentence, self-corrections, spoken punctuation, lists, dictionary in cleanup, protected content |
| core · WordPreservationTest | 3 | ✅ pass | 40-sentence corpus of "trap words": Light/Professional keep every word, Verbatim returns input unchanged |
| core · DictationStateMachineTest | 15 | ✅ pass | Dictation lifecycle, cancel, repeated sessions, stale callbacks, permission revoked, interruption, network error, silence, max duration, double-tap protection |
| core · TextInsertionTest | 18 | ✅ pass | Cursor/selection insertion planning, smart spacing, undo records, insertion policy (password/sensitive/blocked/own app), app-switch confirmation |
| core · InsertersTest | 18 | ✅ pass | Insertion + undo against simulated EditText-accessibility and InputConnection contracts |
| core · EngineSelectionTest | 8 | ✅ pass | Engine choice, network loss, consent/config requirements, never-fallback-to-cloud, Android error-code mapping |
| core · PrivacyTest | 5 | ✅ pass | Consent bound to host/purpose/notice version, HTTPS enforcement, retention |
| core · PersonalDictionaryTest | 7 | ✅ pass | Whole-word matching, longest match, possessives, punctuation boundaries, common-word warning |
| core · CloudClientTest | 13 | ✅ pass | OpenAI-compatible protocol vs a local HTTP server, error mapping, key redaction, AI-cleanup validation, WAV/RMS helpers |
| core · GeometryTest | 7 | ✅ pass | Bubble position across rotation and fold/unfold area changes, edge snapping, two-pane breakpoint |
| app · FieldInspectorTest | 7 | ✅ pass | Password/sensitive/hint/unreliable-text detection using the **real Android 16 `AccessibilityNodeInfo`** class |
| **Total** | **143** | **✅ 143 passed, 0 failed, 0 skipped** | |
| Public-API check (`tools/api_check.py`) | 624 refs | ✅ PASS | Every `android.*` reference in app+core bytecode resolved against AOSP Android 16 public API; 0 non-public; API>33 calls guarded |
| APK build + `apksigner verify` | — | ✅ pass | v2+v3 signatures; package metadata below |

Notes on test doubles (no simulated results stand in for FlowForge's own behavior):
- `InsertersTest` simulates *other apps' fields* (an EditText behind accessibility, an
  InputConnection) following Android's documented semantics; FlowForge's insertion code runs unmodified.
- `CloudClientTest` uses a local HTTP server implementing the documented OpenAI-compatible request and
  response shapes. It proves protocol handling, **not** that any real provider transcribes speech.
- `FieldInspectorTest` uses the real framework class; only `android.util.Log` (native, logging only)
  is replaced on the test classpath.

## 2. Spec test phrases (all pass, Light cleanup unless noted)
| Spoken | Output |
|---|---|
| Hey Jason um I checked the bathrooms and actually we should do it Friday not Thursday. | Hey Jason, I checked the bathrooms, and we should do it Friday, not Thursday. |
| Let's meet Thursday. Actually, make that Friday. | Let's meet Friday. |
| Let's do it Wednesday I mean Thursday | Let's do it Thursday. |
| I'll be there at 5 scratch that I'll be there at 6 | I'll be there at 6. |
| Scratch that. *(alone)* | *(empty text + "undo previous dictation" command)* |
| No, don't send that. / no don't send that | No, don't send that. *(negation kept; not treated as a correction)* |
| I I I think we should go | I think we should go. |
| we should we should go to the the store | We should go to the store. |
| I want two, no, three apples | I want three apples. |
| no one came / there are no Mondays off | No one came. / There are no Mondays off. *(unchanged meaning)* |
| it was very very good / he had had enough | kept as spoken |
| agenda for today first review the budget second hire a designer and third ship the app *(Professional)* | Agenda for today:⏎1. Review the budget⏎2. Hire a designer⏎3. Ship the app |

Safety property test: for six sentences mixing negations, numbers and dates, **all three modes**
leave the set of numbers, dates and negations unchanged.

## 3. APK inspection (`app/build/outputs/apk/debug/FlowForge-debug.apk`, copied to `dist/`)
```
package: name='com.flowforge.dictation' versionCode='1' versionName='0.1.0'
         platformBuildVersionName='16' platformBuildVersionCode='36' compileSdkVersion='36'
sdkVersion:'33'   targetSdkVersion:'36'   application-debuggable
uses-permission: RECORD_AUDIO, SYSTEM_ALERT_WINDOW, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MICROPHONE,
                 POST_NOTIFICATIONS, VIBRATE, INTERNET, ACCESS_NETWORK_STATE
provides-component: ime, accessibility
Signatures: v2 + v3 (APK Signature Scheme), verified by apksigner
```
Components: `ui.MainActivity` (launcher), `ui.BubbleLauncherActivity` (not exported),
`overlay.DictationService` (foregroundServiceType=microphone, not exported),
`accessibility.FlowForgeAccessibilityService`, `ime.FlowForgeKeyboardService`,
`tile.BubbleTileService` (each protected by its system `BIND_*` permission).

## 4. Requires device verification (not tested — no device available)
Spec test areas mapped to evidence:

| Spec area | Logic tested here | Still needs a device |
|---|---|---|
| Speech recognition lifecycle | ✅ state machine | Real `SpeechRecognizer` callbacks on Samsung/Google services |
| Microphone permission changes | ✅ denied / revoked events | Revoking in Settings mid-dictation |
| Foreground-service behavior | — | Start from app & QS tile; notification; Android 14+ eligibility |
| Overlay creation/destruction | ✅ geometry | Bubble window add/remove, drag, dismiss target |
| Accessibility node handling | ✅ real `AccessibilityNodeInfo` | Live nodes in Messages, Gmail, Chrome, Samsung Notes |
| Insertion & cursor preservation / selection replacement / undo | ✅ contract simulations | Real apps (EditText, WebView, Compose text fields) |
| Cleanup accuracy, self-corrections, names/dates/numbers/negations | ✅ | Real recognizer output (punctuation/casing vary by engine) |
| App switching | ✅ confirmation rule | Switching apps during a bubble dictation |
| Screen rotation, fold/unfold | ✅ geometry + breakpoint | Fold7 folded↔unfolded with bubble idle and recording; two-pane UI |
| Network loss | ✅ selection + client errors | Airplane mode with system and cloud engines |
| Offline recognition | ✅ selection only | Airplane mode + installed language pack (**not claimed to work offline until tested**) |
| Cancellation, repeated sessions, background interruption | ✅ state machine | Phone call / other app taking the mic; process death |

### Device test checklist (Galaxy Z Fold7, Android 16)
1. Install the APK; complete Setup (mic, notifications, keyboard, overlay, text insertion incl. "Allow restricted settings").
2. Home → Try it here: dictate with the on-device engine; Speech engine → Check language pack; repeat in airplane mode.
3. Voice keyboard in Messages, Gmail, Chrome (normal + incognito), Samsung Notes: insertion at cursor, selection replacement, Undo, Original, password field lockout, Enter/Send key.
4. Bubble: show from Home and Quick Settings tile; tap → speak → tap; drag/snap; drag to ✕; long-press menu; switch apps mid-dictation (expect confirmation).
5. Fold/unfold and rotate with the bubble idle and while recording; check app two-pane ↔ single-pane.
6. Errors: deny mic; revoke mic mid-dictation; incoming call during dictation; another recorder app running.
7. Long dictation (3+ minutes), silence auto-stop (20 s), max duration.
8. Privacy: history retention, delete history, delete all data, cloud consent dialogs (if testing BYO cloud).
9. Record Help → Diagnostics latencies; check battery usage after an hour with the bubble idle.
