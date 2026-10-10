# FlowForge

**Speak naturally. Clean text lands where you're typing.**

FlowForge is a privacy-first Android voice dictation app (Android 13+, optimized for the Galaxy Z
Fold7). Keep your normal keyboard: a movable **floating mic bubble** sits over any app. **Hold it
and talk, let go**, and FlowForge types clean, correctly punctuated text at your cursor — or tap
once to start and again to finish. It removes "um"s, stutters and self-corrections while never
silently changing numbers, dates, names or negations.

> Status: **0.2.0 debug build — not yet tested on a physical device.** See
> [RELEASE_READINESS.md](RELEASE_READINESS.md) for exactly what is verified and what is not.

## Highlights
- **Hold-to-talk or tap-to-talk bubble** over any app, with accessibility-based text insertion
  (or copy/paste when insertion is off). Slide away while holding to cancel.
- **Offline speech model (recommended):** a Whisper-class model (NVIDIA Parakeet TDT 0.6B, or the
  smaller Moonshine Base) runs inside FlowForge via sherpa-onnx. One-time download, checked against a
  pinned SHA-256; afterwards audio never leaves the phone and no internet is needed. The microphone
  records continuously and finished sentences are transcribed while you keep talking. English only.
- **Three cleanup modes:** Verbatim, Light cleanup, Professional — deterministic, on-device, with
  a safety guard that skips any rule that would alter numbers, dates, names or negations.
- **Voice self-corrections:** "scratch that", "make that Friday", "Thursday, I mean Friday",
  "two, no, three"; spoken punctuation; "new paragraph"; spoken lists → numbered lists.
- **Cursor-safe insertion:** inserts at the cursor or replaces the selection, never rewrites the rest
  of the field, refuses password and payment fields, asks before typing into an app you switched to,
  and offers undo + "use original transcript".
- **Personal dictionary** for names, places, acronyms and jargon (whole-word matching only).
- **Other engines:** Android on-device recognition (offline once the language pack is installed), the
  phone's speech service, or your own OpenAI-compatible cloud endpoint (bring your own key,
  explicit per-host consent). No subscription required.
- **Privacy:** no analytics/ads/tracking, no FlowForge server, audio kept in memory only,
  encrypted local history with retention controls, one-tap "delete all data".
- **Foldable-aware UI:** two-pane layout when unfolded, single pane on the cover screen; the bubble
  keeps its relative position through fold/unfold and rotation.

## Repository layout
| Path | Contents |
|---|---|
| `core/` | Pure Kotlin logic + JVM tests: cleanup engine, dictionary, insertion & undo, session state machine, engine selection, privacy/consent, cloud client, model download/verify/install, pause-based audio chunking |
| `app/` | Android app: services (bubble, accessibility, model download, QS tile), speech engines (offline model, Android, cloud), encrypted storage, UI; custom Gradle APK pipeline |
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
code, assets or branding from any other product. Third-party components: Kotlin standard library
(Apache-2.0), sherpa-onnx (Apache-2.0) with ONNX Runtime (MIT), jbzip2 (MIT). Speech models are
downloaded at the user's request from the sherpa-onnx releases: Parakeet TDT 0.6B v2 (CC-BY-4.0,
NVIDIA) and Moonshine Base (MIT, Useful Sensors).
