# FlowForge

**Speak naturally. Clean text lands where you're typing.**

FlowForge is a privacy-first Android voice dictation app (Android 13+, optimized for the Galaxy Z
Fold7). Dictate into any app through the **FlowForge Voice Keyboard** or a movable **floating
bubble**; FlowForge turns speech into clean, correctly punctuated text — removing "um"s, stutters
and self-corrections while never silently changing numbers, dates, names or negations.

> Status: **0.1.0 debug build — not yet tested on a physical device.** See
> [RELEASE_READINESS.md](RELEASE_READINESS.md) for exactly what is verified and what is not.

## Highlights
- **Two ways to dictate anywhere:** a voice keyboard (InputMethodService, most reliable) or a
  floating bubble with accessibility-based text insertion (keeps your usual keyboard).
- **Three cleanup modes:** Verbatim, Light cleanup, Professional — deterministic, on-device, with
  a safety guard that skips any rule that would alter numbers, dates, names or negations.
- **Voice self-corrections:** "scratch that", "make that Friday", "Thursday, I mean Friday",
  "two, no, three"; spoken punctuation; "new paragraph"; spoken lists → numbered lists.
- **Cursor-safe insertion:** inserts at the cursor or replaces the selection, never rewrites the rest
  of the field, refuses password and payment fields, asks before typing into an app you switched to,
  and offers undo + "use original transcript".
- **Personal dictionary** for names, places, acronyms and jargon (whole-word matching only).
- **Engines:** Android on-device recognition (offline once the language pack is installed), the
  phone's speech service, or your own OpenAI-compatible cloud endpoint (bring your own key,
  explicit per-host consent). No subscription required.
- **Privacy:** no analytics/ads/tracking, no FlowForge server, audio kept in memory only,
  encrypted local history with retention controls, one-tap "delete all data".
- **Foldable-aware UI:** two-pane layout when unfolded, single pane on the cover screen; the bubble
  keeps its relative position through fold/unfold and rotation.

## Repository layout
| Path | Contents |
|---|---|
| `core/` | Pure Kotlin logic + JVM tests: cleanup engine, dictionary, insertion & undo, session state machine, engine selection, privacy/consent, cloud client |
| `app/` | Android app: services (bubble, accessibility, keyboard, QS tile), speech engines, encrypted storage, UI; custom Gradle APK pipeline |
| `tools/` | Toolchain bootstrap script and the public-API bytecode checker |
| `dist/` | Prebuilt `FlowForge-debug.apk` |

## Build
```bash
tools/bootstrap-toolchain.sh   # only if you don't have the official Android SDK
./gradlew check assembleDebug  # tests + API check + signed debug APK
```
Details, toolchains and phone installation: [BUILD_INSTRUCTIONS.md](BUILD_INSTRUCTIONS.md).
Test evidence: [TEST_RESULTS.md](TEST_RESULTS.md). Progress log: [DEVELOPMENT_STATUS.md](DEVELOPMENT_STATUS.md).

FlowForge is an independent project inspired by the category of AI dictation tools; it uses no
code, assets or branding from any other product.
