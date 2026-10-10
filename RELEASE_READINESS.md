# FlowForge — Release Readiness

Version 0.2.0 (versionCode 2) · package `com.flowforge.dictation` · minSdk 33 (Android 13) · targetSdk 36 (Android 16)

**Bottom line:** FlowForge is a complete, buildable debug app with a well-tested core, but **no part
of it has run on a phone or emulator yet** (none was available in the build environment — see
"Blocked"). It is ready for hands-on device testing, not for public release.

---

## 1. Feature status

### Implemented and verified (automated tests in this environment)
| Area | Evidence |
|---|---|
| Cleanup engine — Verbatim / Light / Professional | Core unit tests incl. the spec's reference sentence and all spec test phrases |
| Self-corrections ("scratch that", "make that X", "I mean X", "A, no, B", "actually X") | Unit tests, including false-positive guards ("scratch that itch", "No, don't send that.") |
| Protection of numbers, dates, names, negations | `SafetyGuard` runs after every rule; corpus test asserts no meaningful word is dropped |
| Spoken punctuation, paragraphs, spoken lists, technical-term casing | Unit tests |
| Personal dictionary (whole-word, longest match, possessives) | Unit tests |
| Cursor-preserving insertion, selection replacement, smart spacing | Unit tests against a simulated EditText behind accessibility |
| Offline speech model: download, SHA-256 verification, safe unpacking, install/delete/switch | Unit tests + both real model archives installed through the same code (TEST_RESULTS §1a) |
| Offline transcription and pause-based chunking (no words lost between chunks) | Opt-in integration tests with Parakeet and Moonshine on Linux, using the AAR's Kotlin classes and sherpa-onnx's Linux build of the same native library; also run with no network |
| Undo and original-transcript recovery (refuses if the user edited since) | Unit tests |
| Password / sensitive-field refusal, blocked apps, app-switch confirmation | Unit tests; field inspection tested on the real Android 16 `AccessibilityNodeInfo` class |
| Dictation lifecycle: start/stop/cancel, repeated sessions, stale callbacks, permission revoked, interruptions, network errors, silence, max duration | State-machine unit tests |
| Engine selection: offline model by default, falls back to engines that keep audio on the phone first, never silently to cloud | Unit tests |
| Consent bound to provider host + notice version; HTTPS enforcement; retention | Unit tests |
| Cloud client (OpenAI-compatible multipart upload, auth, errors, no key leakage) | Tests against a local HTTP server implementing the documented API shape (protocol only) |
| AI-cleanup output validation (rejects changed numbers/dates/names/negations, commentary) | Unit tests |
| APK builds, signs (v2+v3), verifies; manifest components, permissions, FGS type | Build output + `aapt2 dump`, `apksigner verify` |
| No hidden/non-public Android API use; API>33 calls version-guarded | `tools/api_check.py` against AOSP Android 16 & 13 public API (588 app/core references + the sherpa-onnx classes, 0 errors) |
| Android lifecycle code review | Independent reviews of services/overlay/engines (0.1.0: 11 findings; 0.2.0: offline engine, bubble gestures, download service) — all fixes still to be confirmed on a device |

### Implemented but NOT device-tested
Everything that needs a running Android system:
- Offline model on the phone: loading the native libraries, model load time and memory (Parakeet ≈ 0.6 GB of weights in RAM while the bubble is shown; Moonshine ≈ 0.3 GB), decode speed on the Fold7 CPU, install time (Java bzip2), download service with notification/Cancel, Android 15+ dataSync time limits.
- Hold-to-talk / tap-to-talk / slide-to-cancel gestures, very short presses, TalkBack.
- Fallback engines: Android `SpeechRecognizer` (on-device and system service), continuous dictation by segment restart, level metering, language-pack check/download.
- Microphone capture for the cloud engine (`AudioRecord`, in-memory only), cloud transcription against a real provider.
- Floating bubble: overlay window, hold/tap/drag, edge snapping, drag-to-dismiss, repositioning on rotation and fold/unfold, result/preview panel, haptics.
- Microphone foreground service start rules, ongoing notification with Stop/Cancel/Hide, Quick Settings tile and its trampoline activity.
- Accessibility text insertion in real apps (Messages, Gmail, Chrome, Samsung Notes…), paste fallback, clipboard clearing, undo.
- Encrypted storage (Android Keystore AES-GCM) for history, dictionary and API key; retention purge; "delete all data".
- All screens; two-pane layout on wide windows (Fold7 inner display) vs single pane (cover display); dark/light; Material You colors; edge-to-edge insets; predictive back.
- Performance: mic start latency and stop→result latency are *measured* on-device and shown in Help → Diagnostics, but no numbers exist yet. Battery impact unmeasured.

### Incomplete
| Item | Why / plan |
|---|---|
| Offline model for other languages | The bundled catalog is English-only (Parakeet v2, Moonshine Base). Multilingual models (e.g. Whisper, SenseVoice, Parakeet v3) can be added to `ModelCatalog` after evaluation. |
| Hotwords / personal dictionary as a recognition hint for the offline model | The dictionary is applied in cleanup; transducer hotword biasing needs a BPE vocabulary file and was not evaluated. |
| 32-bit ARM and x86 phones | Only arm64-v8a native libraries are packaged (keeps the APK at 13.5 MB). Other ABIs use the Android engines. |
| Jetpack Compose UI | Replaced by framework Views because Google Maven (Compose/AndroidX) was unreachable. All logic is in `:core`, so migrating the UI layer is self-contained. |
| Robolectric / instrumentation tests | Robolectric needs `androidx.test:monitor` (Google Maven only); no emulator. |
| Release pipeline: AAB, R8 shrinking, Play App Signing | Needs AGP or bundletool (Google Maven). See §5. |
| Localization | UI text is in Kotlin, English only; cleanup rules are English-specific (Verbatim works for any language the recognizer supports). |
| Auto-paragraphing in Professional mode | Only spoken "new paragraph" commands create paragraphs. |
| Screenshots / visual QA | No renderer or device available. |

### Blocked (external)
- **No device or emulator:** no KVM in the container and no physical device attached. Nothing could be executed on Android.
- **`dl.google.com` denied by the environment's network policy:** blocks the official Android SDK, Google Maven (AGP, AndroidX, Compose, D8/R8), emulator system images. To lift: allow `dl.google.com` in the cloud environment's network settings.
- **`huggingface.co` and `alphacephei.com` denied:** models come from the sherpa-onnx GitHub releases instead (reachable), which is also where the app downloads them from.

---

## 2. Known limitations & Android compatibility notes
- **Sideloading on Android 13+:** the accessibility service ("text insertion") is a *restricted setting* for apps installed from files. The user must first try to turn it on (Android shows "Restricted setting"), then App info → ⋮ → Allow restricted settings, then turn it on. Setup shows these steps in order with buttons for each screen.
- **Hold-to-talk timing:** a press shorter than 0.28 s is a tap; a hold released within 0.45 s is discarded with a hint (too short to contain speech).
- **Offline model latency:** text appears after the model has transcribed the last stretch of speech; earlier sentences are transcribed while you talk. The first dictation after showing the bubble may wait for the model to load (measured 2–5 s on desktop; phone unmeasured).
- **Overlay vs keyboard:** Android draws `TYPE_APPLICATION_OVERLAY` windows below the on-screen keyboard; a bubble placed low on the screen can be hidden by the keyboard. Drag it higher.
- **Bubble start:** a microphone foreground service may only start while FlowForge is visible, so the bubble starts from the app or the Quick Settings tile (which briefly opens an invisible activity), never by itself in the background. If Android or Samsung's battery management kills the process, the bubble does not reappear on its own; Home offers "Restore bubble".
- **On-device recognition availability** depends on the phone's speech services (Google's on-device recognizer / language packs). FlowForge reports what Android tells it and labels the system service honestly as possibly cloud-based.
- **Android recognizer (fallback engine)** restarts at each pause in continuous mode; a few hundred milliseconds of audio may be missed at a restart, and some recognizers play a sound at start. This is the "cutting in and out" behavior 0.1.0 had; the offline model avoids it by recording continuously.
- **Rich-text editors (WebView, Docs):** insertion uses paste, which overwrites the clipboard briefly (marked sensitive so Android's clipboard preview hides it, and cleared afterwards, best effort). Paste insertions can't be undone by FlowForge, because undo would rewrite the rich field as plain text; use the app's own undo. Some apps block accessibility actions entirely; the result is then shown for manual copy.
- **App rewriting text** (input filters, emoji): insertion is reported unverified and undo is disabled for that insertion.
- **Cleanup language:** English rules only.
- **Build toolchain:** the delivered APK was built with the bootstrap toolchain (dx, distro aapt2, AOSP framework jars from Maven Central), not the official SDK. Public-API compliance is enforced by `tools/api_check.py`.

---

## 3. Privacy & security assessment

### Data inventory
| Data | Where | Protection | Leaves device? |
|---|---|---|---|
| Live audio | RAM only (offline model and cloud engine) or inside Android's recognizer | Zeroed after use; never written to disk | Never with the offline model. Only with the cloud engine, to the user's chosen host, after consent; or by the *system* speech service if the user selects it (disclosed) |
| Speech models | App's no-backup storage (`noBackupFilesDir/models`) | Downloaded only on request over HTTPS (redirects must stay HTTPS); SHA-256 pinned in the app; only allow-listed file names are written, so archive paths can't escape the folder | No (download only) |
| Transcripts (history) | SQLite | AES-256-GCM, Keystore key (non-exportable); `secure_delete`; retention 1–90 days (default 7); can be disabled; incognito fields skipped | No |
| Personal dictionary | SQLite | Same encryption | No (terms may be sent as a vocabulary hint to the user's cloud provider when cloud recognition is used) |
| Cloud API key | SharedPreferences | Encrypted with Keystore key; decrypted only per request; never logged (`CloudConfig.toString()` redacts) | Sent only to the configured host |
| Settings | SharedPreferences | Not sensitive | No |
| Other apps' content | Not stored | Accessibility reads only the focused field, only at insertion time | No |

- Backups: `allowBackup=false`; data-extraction rules exclude everything (cloud backup and device transfer).
- Network: no traffic unless the user downloads a speech model (GitHub release asset, verified) or configures a cloud provider. HTTPS enforced by network security config and in code (plain HTTP only for `localhost`/`127.0.0.1`). Redirects are not followed, so the API key cannot be forwarded to another host.
- Consent is recorded per purpose (recognition / AI cleanup), bound to the provider host and to the notice version; changing either requires fresh consent. Withdrawal stops uploads immediately.
- AI cleanup output is validated on-device and discarded if it changes protected content.
- No analytics, advertising, crash-reporting or tracking SDKs. Bundled libraries: Kotlin standard library, sherpa-onnx + ONNX Runtime (speech recognition, native), jbzip2 (decompression). The sherpa-onnx AAR and every Maven artifact are pinned by SHA-256 in `gradle/verification-metadata.xml`.
- Recording indicators: Android's privacy indicator, an ongoing notification ("FlowForge is recording your voice right now") with Stop/Cancel, and the red bubble. Recording requires an explicit hold or tap.

### Permissions
| Permission | Purpose |
|---|---|
| RECORD_AUDIO | Dictation, only between start and stop |
| FOREGROUND_SERVICE, FOREGROUND_SERVICE_MICROPHONE | Bubble mode microphone access while other apps are in front |
| FOREGROUND_SERVICE_DATA_SYNC, WAKE_LOCK | The one-time model download/verification, with a progress notification, if the user leaves the app or turns off the screen |
| SYSTEM_ALERT_WINDOW | Draw the bubble over other apps (user-granted) |
| POST_NOTIFICATIONS | Recording/bubble notification with controls |
| INTERNET, ACCESS_NETWORK_STATE | Model download on request; optional user-configured cloud provider; offline/metered detection |
| VIBRATE | Haptic feedback |
| BIND_ACCESSIBILITY_SERVICE / BIND_QUICK_SETTINGS_TILE | Held by the system to bind FlowForge's services (not requested from the user) |

### Exposed surface
- Exported components: launcher activity, and the accessibility / tile services, each protected by a system-only `BIND_*` permission. `DictationService`, `ModelDownloadService` and the trampoline activity are not exported. All PendingIntents are immutable.
- SQL uses parameterized queries. No WebView. No dynamic code loading (the downloaded models are data files read by onnxruntime, not code).

### Residual risks / to do before release
- Debug APK is `debuggable` and signed with a public debug key — for testing only.
- Paste fallback briefly places dictation on the clipboard (marked sensitive; cleared afterwards, best effort).
- Accessibility service is powerful by nature; the code subscribes only to focus events and ignores them, but Google Play reviews such apps strictly (§4).
- Independent security review and a written privacy policy are still needed.

---

## 4. Google Play policy review (pre-submission)
| Policy | Status |
|---|---|
| **Accessibility API** | FlowForge is not an accessibility tool (`isAccessibilityTool=false`). Play requires a prominent in-app disclosure + affirmative consent before enabling (implemented: Setup → Text insertion dialog), and an Accessibility API declaration in Play Console. Using accessibility to type into other apps may be rejected. Since 0.2.0 the bubble is the only way to type into other apps (the keyboard was removed at the user's request), so a Play release depends on this declaration being accepted; otherwise distribute outside Play, or offer copy/paste-only on Play. |
| **Foreground service types (Android 14+)** | `microphone` (user-initiated dictation while another app is in front) and `dataSync` (user-initiated model download) declared. Play Console requires FGS declarations with a short video. |
| **Model licenses** | Parakeet TDT 0.6B v2 is CC-BY-4.0 (attribution shown in Help → About and in Speech settings); Moonshine is MIT. Models are downloaded from the sherpa-onnx project, not redistributed in the APK. |
| **Permissions / sensitive data** | Microphone and accessibility use require a privacy policy URL and a Data safety form (audio processed on device; optional user-configured third-party processing). Not yet written. |
| **Target API level** | targetSdk 36 satisfies current requirements. |
| **Families / ads / billing** | No ads, no billing, not targeting children. |
| **Impersonation / IP** | Original name, icon, colors and code; no Wispr Flow assets. **Trademark check needed:** "FlowForge" was previously used by another software company (FlowForge Inc., now FlowFuse). |

---

## 5. Release configuration path
1. Get access to the official SDK and Google Maven (allow `dl.google.com`).
2. Either keep the Gradle pipeline (switch dexer to `d8`/`r8` and add `bundletool` to produce an AAB) or migrate `:app` to the Android Gradle Plugin — the source layout (`src/main/kotlin`, `res`, `AndroidManifest.xml`) is AGP-compatible.
3. Enable R8 shrinking (expected to cut the 3.6 MB dex substantially; the native libraries are 10 MB of the 13.5 MB APK). With AGP, store native libraries uncompressed and 16 KB-aligned instead of extracting them.
4. Create a release key, enroll in Play App Signing, keep `keystore.properties` out of git.
5. Write the privacy policy, Data safety form, Accessibility and FGS declarations.
6. Run the device test plan in TEST_RESULTS.md on the Fold7 (folded/unfolded) plus at least one Pixel and one Android 13 device.

---

## 6. Commercial model (designed, not activated)
`core/features/Entitlements.kt` is the only monetization seam; this build unlocks everything and contains no payment, subscription or advertising code.

| Tier | Contents | Marginal cost to you |
|---|---|---|
| Free | Offline-model and on-device dictation, all three deterministic cleanup modes, personal dictionary, history | ~0 (runs on the phone) |
| Pro (candidate) | Bring-your-own-key cloud recognition and AI cleanup; future: hosted cloud, custom styles per app, more formatting | BYO key: ~0 (user pays provider). Hosted: per-minute provider cost |

Hosted cloud recognition must be **metered, not unlimited**: provider list prices are on the order of $0.003–$0.006 per audio minute (verify current pricing before committing). A heavy user dictating 60 min/day ≈ 1,800 min/month ≈ $5–11/month in provider cost alone — more than a typical subscription price. Use a monthly minute allowance with on-device as the default.

---

## 7. Recommended next steps
1. **Device test pass on the Galaxy Z Fold7** using the checklist in TEST_RESULTS.md; fix findings.
2. Allow `dl.google.com` in the environment, add the official SDK toolchain, and run the same pipeline with `d8`.
3. Add instrumentation tests (Espresso/UiAutomator) for the bubble gestures, offline engine and accessibility insertion in sample apps.
4. Decide Play strategy for the accessibility bubble (§4); draft privacy policy and Data safety answers.
5. Measure the offline model on the Fold7 (load time, memory, release-to-text latency, battery); if Parakeet is too heavy, make Moonshine the default. Evaluate multilingual models.
6. Localize UI strings; add non-English cleanup rules where demand exists.
7. Trademark search for the product name.
