# FlowForge — Test Results

Version 0.2.0. Environment: Linux x86-64 cloud container (4 CPU threads), OpenJDK 21.0.12,
Gradle 8.14.3, Kotlin 2.2.21, bootstrap Android toolchain (see BUILD_INSTRUCTIONS.md). **No Android
device or emulator was available** (no KVM; emulator images are hosted on a blocked domain). Every
result below comes from the JVM, from Linux runs of the same native speech library, or from static
inspection of the built APK. **Nothing has been executed on Android by this build process.**

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
| core · InsertersTest | 13 | ✅ pass | Insertion + undo against a simulated EditText behind accessibility |
| core · EngineSelectionTest | 9 | ✅ pass | Engine choice incl. offline model default and its fallbacks, network loss, consent/config requirements, never-fallback-to-cloud, Android error-code mapping |
| core · SpeechChunkerTest | 7 | ✅ pass | Splitting a live recording at pauses: short dictation stays whole, cut lands inside the pause, short pauses don't split, max-length cut, silence flagged, steady hum not treated as speech |
| core · ModelInstallerTest | 11 + 1 opt-in | ✅ pass | Model download/verify/install from GNU and PAX `tar.bz2` fixtures (incl. a 153-character path), wrong checksum installs nothing, failed update keeps the old model, truncated download, missing files, cancel, delete, tampered file detected, HTTPS-only redirects, catalog consistency |
| core · PrivacyTest | 5 | ✅ pass | Consent bound to host/purpose/notice version, HTTPS enforcement, retention |
| core · PersonalDictionaryTest | 7 | ✅ pass | Whole-word matching, longest match, possessives, punctuation boundaries, common-word warning |
| core · CloudClientTest | 13 | ✅ pass | OpenAI-compatible protocol vs a local HTTP server, error mapping, key redaction, AI-cleanup validation, WAV/RMS helpers |
| core · GeometryTest | 7 | ✅ pass | Bubble position across rotation and fold/unfold area changes, edge snapping, two-pane breakpoint |
| app · FieldInspectorTest | 7 | ✅ pass | Password/sensitive/hint/unreliable-text detection using the **real Android 16 `AccessibilityNodeInfo`** class |
| app · SpeechConstantsTest | 2 | ✅ pass | Core's copy of `SpeechRecognizer` error codes equals the Android 16 framework values; language-pack status wording |
| app · LocalTranscriberIntegrationTest | 2 opt-in | ✅ pass when run (see §1a) | FlowForge's offline path on real audio with real models |
| **Total** | **162** | **✅ 159 passed, 0 failed, 3 skipped by default** (the 3 opt-in tests need ~0.5–1 GB models; they passed when run, §1a) | |
| Public-API check (`tools/api_check.py`) | 588 refs | ✅ PASS | Every `android.*` reference in app+core bytecode resolved against AOSP Android 16 public API; 0 non-public; API>33 calls guarded. The bundled sherpa-onnx classes were checked separately: 1 reference, public. |
| APK build + `apksigner verify` | — | ✅ pass | v2+v3 signatures; package metadata below |

### 1a. Offline speech engine — measured on this Linux machine, not on a phone

The offline engine is sherpa-onnx v1.13.8. The tests below ran FlowForge's own code
(`SpeechChunker` + `LocalTranscriber`) with the Kotlin API classes from the same Android AAR that the
APK contains, against sherpa-onnx's **Linux x86-64** build of the same native library version. They
show that the models and FlowForge's chunking work; they do **not** measure phone speed or memory.

| Check | Result |
|---|---|
| Real Parakeet archive (482 MB) through `ModelInstaller` | ✅ SHA-256 matches the pin, all 4 files installed with the catalog sizes (51 s on this machine) |
| Real Moonshine archive (251 MB) through `ModelInstaller` | ✅ same (23 s) |
| Parakeet TDT 0.6B v2 int8, synthetic test sentences (espeak voice) | "Hey Jason Um I checked the bathrooms, and actually we should do it Friday not Thursday." / "Let's meet Thursday. Actually, make that Friday." / "Please send the three invoices to the Boston office by 5.30 tomorrow." / "No, don't send that. I mean, wait until Monday." — every word correct. Load 5.4 s; decode 0.07–0.09× real time on 4 threads |
| Parakeet, LibriSpeech sample (16.7 s, human speech) | Correct apart from spelling variants ("for ever", "blest"); 0.07× real time |
| Moonshine Base int8 | LibriSpeech samples correct; on the synthetic voice it misheard "three invoices to the Boston office" as "Freedom Voices to the Master Office". 0.05× real time; load 1.9 s |
| Pause-based chunking vs. whole-clip decoding (clips joined with 1 s pauses, fed in 100 ms blocks exactly like the app) | ✅ Parakeet: same words for the four synthetic clips (4 chunks); 4-word difference over 93 words when the LibriSpeech clip is added (spelling variants). Moonshine: 2-word difference over 78 words ("5:30" vs "five thirty"). No words lost at chunk boundaries |
| No network | ✅ Parakeet transcription run inside a Linux network namespace with no interfaces (`unshare -rn`; `curl github.com` failed there) produced the same text. The app's offline engine reads only local files |

Reproduce (see the test's KDoc): `FLOWFORGE_SHERPA_LIB=<linux jni lib dir> FLOWFORGE_MODEL_DIR=<model dir>
FLOWFORGE_MODEL_ID=parakeet-tdt-0.6b-v2-int8 FLOWFORGE_TEST_WAVS=a.wav,b.wav ./gradlew :app:test --tests '*LocalTranscriber*'`
and `FLOWFORGE_REAL_ARCHIVE=<archive> ./gradlew :core:test --tests '*ModelInstallerTest*'`.

APK-level checks for the engine: `classes.dex` contains all 122 `com.k2fsa.sherpa.onnx` classes;
`lib/arm64-v8a/libsherpa-onnx-jni.so` exports the `Java_com_k2fsa_sherpa_onnx_OfflineRecognizer_*`
entry points those classes call; both native libraries use 16 KB-aligned ELF segments.

Code review: an independent review pass over the Android services/lifecycle code (no device
available) found 7 issues plus 4 minor ones (overlay window leak race, Preview-mode Insert, test
recording continuing in background, taps lost during live transcript updates, undo on rich fields,
a race-only foreground-service crash, no cancel during processing, …). All were fixed in commit
`633cf72`; they are logic fixes that still need device confirmation.

Notes on test doubles (no simulated results stand in for FlowForge's own behavior):
- `InsertersTest` simulates *other apps' fields* (an EditText behind accessibility) following
  Android's documented semantics; FlowForge's insertion code runs unmodified.
- `ModelInstallerTest` uses small archives built for the test with GNU tar; two real model archives
  were also run through it (§1a).
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
package: name='com.flowforge.dictation' versionCode='2' versionName='0.2.0'
         platformBuildVersionName='16' platformBuildVersionCode='36' compileSdkVersion='36'
sdkVersion:'33'   targetSdkVersion:'36'   application-debuggable   native-code: 'arm64-v8a'
uses-permission: RECORD_AUDIO, SYSTEM_ALERT_WINDOW, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MICROPHONE,
                 FOREGROUND_SERVICE_DATA_SYNC, WAKE_LOCK, POST_NOTIFICATIONS, VIBRATE, INTERNET,
                 ACCESS_NETWORK_STATE
size: 13.5 MB (classes.dex 3.6 MB; libonnxruntime.so 22 MB → 8 MB compressed; libsherpa-onnx-jni.so 4.8 → 2 MB)
android:extractNativeLibs="true"
Signatures: v2 + v3 (APK Signature Scheme), verified by apksigner
```
Components: `ui.MainActivity` (launcher), `ui.BubbleLauncherActivity` (not exported),
`overlay.DictationService` (foregroundServiceType=microphone, not exported),
`models.ModelDownloadService` (foregroundServiceType=dataSync, not exported),
`accessibility.FlowForgeAccessibilityService`, `tile.BubbleTileService` (each protected by its system
`BIND_*` permission). The FlowForge keyboard (IME) was removed in 0.2.0.

## 4. Requires device verification (not tested — no device available)
Spec test areas mapped to evidence:

| Spec area | Logic tested here | Still needs a device |
|---|---|---|
| Offline model engine | ✅ chunking, transcription, install (Linux) | Native library loading on the phone, model load time and memory (Parakeet loads ~0.6 GB of weights), decode speed on the Fold7's CPU, Java bzip2 install time |
| Hold-to-talk / tap-to-talk bubble | — (touch logic is Android-only) | Hold ≥ 0.28 s starts, release finishes, slide away cancels, quick tap toggles, drag still moves, TalkBack double-tap |
| Speech recognition lifecycle | ✅ state machine | Real `SpeechRecognizer` callbacks on Samsung/Google services (fallback engines) |
| Microphone permission changes | ✅ denied / revoked events | Revoking in Settings mid-dictation |
| Foreground-service behavior | — | Bubble from app & QS tile; model download notification with Cancel; Android 15+ dataSync limits |
| Overlay creation/destruction | ✅ geometry | Bubble window add/remove, drag, dismiss target |
| Accessibility node handling | ✅ real `AccessibilityNodeInfo` | Live nodes in Messages, Gmail, Chrome, Samsung Notes |
| Insertion & cursor preservation / selection replacement / undo | ✅ contract simulations | Real apps (EditText, WebView, Compose text fields) |
| Cleanup accuracy, self-corrections, names/dates/numbers/negations | ✅ | Real recognizer output on the phone's microphone |
| App switching | ✅ confirmation rule | Switching apps during a bubble dictation |
| Screen rotation, fold/unfold | ✅ geometry + breakpoint | Fold7 folded↔unfolded with bubble idle and recording; two-pane UI |
| Network loss | ✅ selection + client errors | Airplane mode with each engine |
| Offline recognition | ✅ offline model on Linux with no network | Airplane mode on the phone with the model installed |
| Cancellation, repeated sessions, background interruption | ✅ state machine | Phone call / other app taking the mic; process death |

### Device test checklist (Galaxy Z Fold7, Android 16)
1. Install the APK over 0.1.0 (same debug key). The FlowForge keyboard disappears; switch back to
   Samsung Keyboard if it was selected.
2. Setup: mic, notifications, display over apps, text insertion — follow the in-app "Restricted
   setting" steps (A → B → C) and note whether they match the phone's screens.
3. Speech engine → download Parakeet on Wi-Fi: progress in app and notification, Cancel works, time
   to install, storage used (Settings → Apps → FlowForge → Storage).
4. Home → Try it here: hold the mic and talk, release; tap–talk–tap; note time from release to text
   (Help → Diagnostics shows load and stop→result times). Repeat in airplane mode.
5. Bubble in Messages, Gmail, Chrome (normal + incognito), Samsung Notes: hold-to-talk, tap-to-talk,
   slide-away cancel, very short press (expect a hint), drag/snap, drag to ✕, insertion at cursor,
   selection replacement, Undo, "Preview first" setting, password field lockout.
6. A 2–3 minute tap-to-talk dictation with natural pauses: check nothing is dropped and the text
   appears in the bubble panel while still talking.
7. Fold/unfold and rotate with the bubble idle and while holding/recording.
8. Errors: deny mic; incoming call during dictation; another recorder app; delete the model while the
   bubble is showing; switch model.
9. Battery and memory with the bubble shown for an hour (the model stays loaded while the bubble is
   shown and is freed a minute after it is hidden).
