# FlowForge — Development Status

_Living document so any future session can resume. Updated at each milestone._

## Starting point (audit, 2026-10-09)
- The repository contained only `README.md` ("# FlowForge"). The task package said original source
  was preserved, but no Android source was uploaded or present on any branch; no other repository on
  the account contains FlowForge code. The project was therefore built from scratch.
- Environment: no Android SDK, no KVM (no emulator possible). The network policy blocks
  `dl.google.com` — which also serves `maven.google.com` — so AGP, AndroidX, Jetpack Compose,
  D8/R8 and the official SDK platforms cannot be downloaded here.

## Build approach (decision)
- Custom Gradle pipeline in `app/build.gradle.kts` (aapt2 → kotlinc → dx/d8 → zipalign → apksigner),
  no Android Gradle Plugin. See BUILD_INSTRUCTIONS.md.
- Framework-only app (Kotlin + platform Views, no AndroidX/Compose) because Google Maven is blocked.
  All logic lives in the pure-Kotlin `:core` module so the UI layer can later move to Compose.

## Progress
- [x] Gradle wrapper (8.14.3, SHA-256 pinned) + bootstrap toolchain + APK pipeline verified.
- [x] Core: cleanup engine (3 modes, safety guard), dictionary, insertion planner + undo,
      session state machine, engine selection, privacy/consent, cloud client, AI validation.
      110 JVM unit tests passing.
- [ ] Android services: dictation FGS + bubble, accessibility insertion, voice keyboard (IME).
- [ ] UI screens, onboarding, foldable layouts.
- [ ] Docs: BUILD_INSTRUCTIONS.md, TEST_RESULTS.md, RELEASE_READINESS.md; source zip.
