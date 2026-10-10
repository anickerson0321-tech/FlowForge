# FlowForge — Development Status

_Living document so any future session can resume without losing context._
Last updated: 2026-10-10 (session 2, version 0.2.0).

## Starting point (audit)
- The repository contained only `README.md`. The task package said the original source was
  preserved, but no Android source was uploaded or present on any branch, and no other repository
  on the account contains FlowForge code. **The project was built from scratch.**
- Environment: no Android SDK, no KVM (so no emulator), no physical device. The network policy
  denies `dl.google.com` (Android SDK + Google Maven: AGP, AndroidX, Compose, D8/R8),
  `huggingface.co` and `alphacephei.com`. Maven Central (via Google's Cloud Storage mirror),
  the Ubuntu archive and raw.githubusercontent.com are reachable.

## Key decisions
| Decision | Reason |
|---|---|
| Custom Gradle APK pipeline instead of AGP (`app/build.gradle.kts`) | AGP is only on Google Maven (blocked). Pipeline supports the official SDK automatically when present. |
| Bootstrap toolchain: Ubuntu aapt2/dx/zipalign/apksigner + AOSP framework jars (`org.robolectric:android-all`) from Maven Central | Official SDK host blocked. API 36 jar for compiling; API 34 jar for resource linking (distro aapt2 can't parse newer resource tables). |
| `tools/api_check.py` public-API gate in `./gradlew check` | The framework jar contains hidden APIs; the check resolves all `android.*` bytecode references against AOSP Android 16/13 `current.txt`. |
| Framework Views UI (no Compose/AndroidX) | Google Maven blocked. All logic in pure-Kotlin `:core`, so a Compose UI can replace `app/ui` later. |
| Kotlin compiled to Java 8 bytecode with `-Xlambdas=class`, `-Xjdk-release=1.8` | `dx` cannot desugar `invokedynamic`; JDK API limited to what Android provides. |
| Deterministic, rule-based cleanup + `SafetyGuard` | Works offline at zero cost; guarantees numbers/dates/names/negations are not silently changed. AI cleanup is optional and validated. |
| ~~Two insertion paths: voice keyboard (IME) primary~~ → **bubble only** (0.2.0) | The user tried 0.1.0 and didn't want the keyboard: "use your normal keyboard and a pop-up bubble". IME and `CursorInserter` removed. |
| Bubble gestures: hold-to-talk (≥ 280 ms) + tap-to-toggle; slide ≥ 96 dp to cancel; long-press menu removed | User request. Drag starts only if the finger moves before the hold begins, so drags never record. |
| Default engine: **FlowForge offline model** (sherpa-onnx 1.13.8 + Parakeet TDT 0.6B v2 int8; Moonshine Base as the smaller option) | User reported Android's recognizer "pops in and out" (it restarts at every pause). The offline engine records continuously with `AudioRecord`, splits at pauses (`SpeechChunker`) and transcribes finished chunks while the user talks. Evaluated in session 2 on Linux: Parakeet most accurate (incl. punctuation/casing), Moonshine faster/smaller; Parakeet 110M and Whisper base.en were worse on test speech. |
| sherpa-onnx AAR from GitHub releases via an exclusive ivy repo, SHA-256 pinned; native libs arm64 only, compressed + `extractNativeLibs` | Not on Maven; no AGP to consume AARs; distro zipalign can't 16 KB-align. Both .so files have 16 KB-aligned ELF segments. |
| Models downloaded on request (dataSync FGS), streamed network → SHA-256 → bzip2 (jbzip2) → tar (own `TarReader`) → allow-listed files only → atomic rename | Keeps APK at 13.5 MB; tampered/partial downloads never used; no archive path is trusted. Commons Compress rejected (uses invokedynamic, dx can't dex it). |
| Recognition fallbacks: Android on-device / system `SpeechRecognizer`, optional BYO-key OpenAI-compatible cloud | Used when the model isn't downloaded or for non-English; never silently falls back to cloud. |
| Debug keystore committed (`app/debug.keystore`) | Builds from any session/machine install over each other. Not a secret; never for release. |
| History/dictionary/API key encrypted with an Android Keystore AES-GCM key; no backups | Privacy-first. |

## Progress
- [x] Phase 1 — reproducible build (wrapper, toolchains, verification metadata), Android 16 target,
      microphone capture, floating bubble, text insertion, debug APK.
- [x] Phase 2 — recognition engine interface, three cleanup modes, personal dictionary, preview,
      undo, clipboard fallback.
- [x] Phase 3 — offline Whisper-class engine (sherpa-onnx; Parakeet/Moonshine download, verify,
      install, switch, delete), plus Android's on-device recognizer as fallback. Tested on Linux
      with the same native library version and real models, including with no network; **not yet
      run on a phone**.
- [x] Phase 4 — foldable layouts, settings, history, onboarding, accessibility labels, crash-safe
      fallbacks, privacy controls.
- [~] Phase 5 — automated regression tests (163 JVM tests incl. 3 opt-in model tests + API check), independent code review
      (all findings fixed), policy review, release config path and docs done; **device testing,
      AAB/R8 and Play paperwork not done.**

## Where things are
| Path | What |
|---|---|
| `core/src/main/kotlin/com/flowforge/core/` | `cleanup/` (pipeline + steps + SafetyGuard), `dictionary/`, `insertion/` (planner, policy, inserters, undo), `session/` (state machine), `recognition/` (engine selection, error mapping), `privacy/`, `cloud/` (client, AI validation), `layout/`, `settings/`, `features/` (entitlements seam), `audio/`, `text/` (tokenizer, lexicon) |
| `core/src/main/kotlin/com/flowforge/core/models/` | `ModelCatalog` (pinned URLs/SHA-256/sizes), `ModelInstaller`, `TarReader` |
| `core/src/main/kotlin/com/flowforge/core/audio/SpeechChunker.kt` | Pause-based chunking of live audio |
| `app/src/main/kotlin/com/flowforge/dictation/` | `overlay/` (DictationService, BubbleView, OverlayPanel), `accessibility/`, `speech/` (LocalModelEngine, LocalAsr, LocalTranscriber, AndroidSpeechEngine, CloudSpeechEngine, PcmRecorder), `models/ModelDownloadService.kt`, `dictation/DictationController.kt`, `data/` (SecureBox, settings, DB), `ui/` (MainActivity, screens, kit), `tile/`, `util/` |
| `tools/` | `bootstrap-toolchain.sh`, `api_check.py` (+ allowlists), `package-deliverables.sh` |
| `dist/` | `FlowForge-debug.apk` |

## How to resume
1. `./gradlew check assembleDebug` (see BUILD_INSTRUCTIONS.md if toolchain is missing).
2. Read RELEASE_READINESS.md §1 for verified vs unverified features and TEST_RESULTS.md §4 for the
   device checklist.
3. If `dl.google.com` is now allowed: install `platforms;android-36` + `build-tools;36.0.0`, set
   `sdk.dir`, and rebuild (the pipeline switches to the official SDK and d8 automatically).

## Session log
- **Session 1:** built everything from scratch; 0.1.0 debug APK.
- **Session 2:** user feedback on 0.1.0 (from their phone): recognition "pops in and out"; the
  accessibility switch was blocked ("Restricted setting") and the instructions didn't get them past
  it; the keyboard isn't wanted. Changes: offline sherpa-onnx engine as default, hold-to-talk /
  tap-to-talk bubble, keyboard removed, ordered restricted-settings steps in Setup, version 0.2.0.

## Next steps (priority)
1. Run the Fold7 device checklist (TEST_RESULTS.md §4); measure model load time, memory and
   release-to-text latency; fix issues found.
2. Official SDK toolchain + d8/R8; AAB packaging; then Play paperwork (privacy policy, Data safety,
   Accessibility & FGS declarations).
3. Instrumentation tests; local Whisper engine evaluation; localization.
