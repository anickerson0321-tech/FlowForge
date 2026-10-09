# FlowForge — Development Status

_Living document so any future session can resume without losing context._
Last updated: 2026-10-09 (session 1).

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
| Two insertion paths: voice keyboard (IME) primary, accessibility bubble optional | IME is most reliable and Play-friendly; bubble keeps the user's own keyboard. |
| Recognition: Android on-device / system `SpeechRecognizer`, optional BYO-key OpenAI-compatible cloud | Free, functional path without subscriptions; honest exposure labels; consent bound to host. |
| Debug keystore committed (`app/debug.keystore`) | Builds from any session/machine install over each other. Not a secret; never for release. |
| History/dictionary/API key encrypted with an Android Keystore AES-GCM key; no backups | Privacy-first. |

## Progress
- [x] Phase 1 — reproducible build (wrapper, toolchains, verification metadata), Android 16 target,
      microphone capture, floating bubble, text insertion, debug APK.
- [x] Phase 2 — recognition engine interface, three cleanup modes, personal dictionary, preview,
      undo, clipboard fallback.
- [~] Phase 3 — on-device recognition via Android's recognizer (+ language-pack check/download).
      **Local Whisper engine not implemented** (needs NDK/models; blocked and untestable here).
- [x] Phase 4 — foldable layouts, settings, history, onboarding, accessibility labels, crash-safe
      fallbacks, privacy controls.
- [~] Phase 5 — automated regression tests (145 JVM tests + API check), independent code review
      (all findings fixed), policy review, release config path and docs done; **device testing,
      AAB/R8 and Play paperwork not done.**

## Where things are
| Path | What |
|---|---|
| `core/src/main/kotlin/com/flowforge/core/` | `cleanup/` (pipeline + steps + SafetyGuard), `dictionary/`, `insertion/` (planner, policy, inserters, undo), `session/` (state machine), `recognition/` (engine selection, error mapping), `privacy/`, `cloud/` (client, AI validation), `layout/`, `settings/`, `features/` (entitlements seam), `audio/`, `text/` (tokenizer, lexicon) |
| `app/src/main/kotlin/com/flowforge/dictation/` | `overlay/` (DictationService, BubbleView, OverlayPanel), `accessibility/`, `ime/`, `speech/`, `dictation/DictationController.kt`, `data/` (SecureBox, settings, DB), `ui/` (MainActivity, screens, kit), `tile/`, `util/` |
| `tools/` | `bootstrap-toolchain.sh`, `api_check.py` (+ allowlists), `package-deliverables.sh` |
| `dist/` | `FlowForge-debug.apk` |

## How to resume
1. `./gradlew check assembleDebug` (see BUILD_INSTRUCTIONS.md if toolchain is missing).
2. Read RELEASE_READINESS.md §1 for verified vs unverified features and TEST_RESULTS.md §4 for the
   device checklist.
3. If `dl.google.com` is now allowed: install `platforms;android-36` + `build-tools;36.0.0`, set
   `sdk.dir`, and rebuild (the pipeline switches to the official SDK and d8 automatically).

## Next steps (priority)
1. Run the Fold7 device checklist; fix issues found.
2. Official SDK toolchain + d8/R8; AAB packaging; then Play paperwork (privacy policy, Data safety,
   Accessibility & FGS declarations).
3. Instrumentation tests; local Whisper engine evaluation; localization.
