# PrivateVoice

PrivateVoice is a native Android app that reads your incoming text messages aloud with natural-sounding neural voices. Everything runs on the phone, and the app has no internet permission. You can give different people different voices, read messages on demand, or turn on automatic reading under conditions you choose.

- **Target:** Samsung Galaxy Z Fold7, Android 16 (API 36), arm64-v8a. Minimum Android 12 (API 31).
- **Voice engine:** Kokoro-82M v1.0 (8-bit ONNX) running through ONNX Runtime on the CPU, with 28 English voices. A Kotlin port of misaki's grapheme-to-phoneme rules converts text to phonemes, using its 183k-entry lexicon.
- **Fallback engine:** Android's installed text-to-speech, restricted to offline voices.
- **Privacy:** message text stays in memory only and expires after a few minutes (10 by default). Nothing is logged, backed up or sent anywhere. See [PRIVACY.md](PRIVACY.md).

---

## Install on a Galaxy Z Fold7

1. Copy `PrivateVoice-debug.apk` to the phone, for example with a USB cable, Quick Share, or by downloading it.
2. Open it with **My Files**, tap the APK, and allow **Install unknown apps** for My Files when asked.
3. Open **PrivateVoice** and tap **Allow notification access**, then turn PrivateVoice on.
   - If the switch is greyed out ("Restricted setting"), go to **Settings → Apps → PrivateVoice → ⋮ (top-right) → Allow restricted settings**. Android requires this for sideloaded apps that ask for notification access. Then try again.
4. Tap **Allow notifications** so PrivateVoice can show its Read aloud / Stop controls. These never show message text.
5. Optional:
   - Open **Voice Studio** to pick a default voice and preview voices.
   - Open **People & Voices** to give individual senders their own voices.
   - Open **Automatic Reading** to set up hands-free reading.
   - For reliable automatic reading with music ducking, tap **Allow unrestricted battery use** under Reliability (see [Known limitations](#known-limitations)).

With ADB instead: `adb install -r PrivateVoice-debug.apk`

The APK is debug-signed with the standard Android debug key, so installing a later build signed with a different debug key requires uninstalling first.

## Using it

| Where | What |
|---|---|
| Dashboard | **Read latest message**, Stop, Pause/Resume, Repeat, status for access / engine / offline / auto-reading / playback / audio output, and the list of messages waiting (sender and app only, never text) |
| Notification | Read aloud · Repeat · Dismiss, and while reading Pause · Stop. The lock screen shows only "New message ready to read" |
| Quick Settings tile | **Read message**: tap to read the latest, tap again to stop. When the phone is locked you must unlock first |
| Floating button | Optional. Tap to read the latest; long-press for Stop / Pause / Repeat; drag to move. Transparency is adjustable, and it can hide when nothing is waiting and on the lock screen |
| Voice Studio | Load or unload the engine, speaking speed, volume, emoji and texting-shorthand handling, default / announcer / masculine-category / feminine-category voices, previews for every voice |
| People & Voices | Add a sender (picked from a waiting message for exact ID matching, or typed by name), choose a voice or a category, preview, edit, remove, delete all |
| Automatic Reading | Off by default. Settings for: allowed outputs (headphones only / Bluetooth only / any), approved Bluetooth headphones, only when unlocked, quiet hours, pause during calls, skip groups, sender-only mode, announcements, how long unread messages are kept, temporary pause (15 min / 1 h / 8 h) |
| Privacy Center | Offline status, permission overview, clear temporary content, manage or delete voice assignments, honest limitations |

**Voice priority for each message:** the sender's own voice → the voice for the category you put them in → the default voice. The optional announcer says "Message from Sarah." in the announcer voice, then the message is read in Sarah's voice.

---

## Build

### Why the build doesn't use the Android Gradle Plugin

This project was built in an environment where Google's Maven repository and `dl.google.com` (the SDK manager) were blocked. That rules out the Android Gradle Plugin, AndroidX and Jetpack Compose. So:

- **The UI uses framework Views**, styled to Material 3 dark design (`app/src/main/kotlin/.../ui/Ui.kt` is a small view DSL), not Compose. Everything the spec asks for is implemented, but it is not built with Compose.
- **The APK is assembled by an explicit Gradle pipeline** in `app/build.gradle.kts`:

```
aapt2 compile/link  → resources + R.java        (links against the framework-res.apk resource table)
kotlinc (JVM 1.8)   → classes                    (compiled against the API 36 framework jar from Robolectric android-all 16)
LambdaDesugarer     → rewrites invokedynamic lambdas (buildSrc/, ASM; same strategy as D8)
dx                  → classes.dex
zip + zipalign      → APK (Kokoro model stored uncompressed so it can be memory-mapped)
apksigner           → v2+v3-signed debug APK
```

`./gradlew assembleDebug` works with the standard wrapper (Gradle 8.14.3).

### Requirements (Linux; WSL or Docker work on Windows/macOS)

- JDK 21
- Debian/Ubuntu packages: `sudo apt install aapt apksigner zipalign dalvik-exchange android-framework-res`
- Network access at build time to Maven Central, registry.npmjs.org and files.pythonhosted.org. The voice model, voices and lexicon are downloaded once and checked against pinned SHA-256 hashes (`app/voice-assets.gradle.kts`). The app never downloads anything.

Tool paths can be overridden in `gradle.properties`: `pv.aapt2`, `pv.dxJar`, `pv.zipalign`, `pv.apksigner`, `pv.frameworkRes`.

### Commands

```bash
./gradlew assembleDebug          # → app/build/outputs/apk/debug/PrivateVoice-debug.apk (+ .sha256)
./gradlew test                   # JVM + Robolectric tests, including real Kokoro inference
./gradlew testDesugared          # the same tests against the exact (desugared) bytecode that is dexed
./gradlew privacyAudit           # APK + source privacy checks → app/build/reports/privacy-audit.txt
```

Test audio from the inference test is written to `app/build/test-audio/*.wav`. This happens only in tests; the app never writes audio.

---

## Architecture

```
notifications/  NotificationListenerService → AndroidNotificationExtractor (plain snapshot)
                → NotificationParser (MessagingStyle, groups, hidden content, multi-message)
                ControlsNotifier + ReaderActionReceiver (Read aloud / Stop / Repeat)
messaging/      TemporaryMessageRepository (in-memory, bounded, expiring, wipes on removal)
                NewMessageTracker (dedup by notification key + message timestamp; no text or hashes)
reader/         ReaderController (manual/auto flow) · AutoReadPolicy · RoutePolicy · QuietHours
                ScriptBuilder (announcer + sender segments) · ReadingService (short-lived FGS)
voices/         VoiceCatalog · VoiceSelectionResolver · VoiceAssignmentRepository
                (AES-GCM, Android Keystore key, no-backup dir)
speech/         SpeechEngine: KokoroEngine (ONNX Runtime) · SystemTtsEngine (offline-only fallback)
speech/kokoro/  TextNormalizer · EnglishG2P (misaki port) · Lexicon · LetterToSound · KokoroSynthesizer
audio/          SpeechPlayer (AudioTrack, audio focus, pause/resume, bounded queue, Repeat cache)
                AudioRouteMonitor (outputs, calls, becoming-noisy)
overlay/        FloatingButtonController (TYPE_APPLICATION_OVERLAY, one instance, fold-aware)
tile/           ReadLatestTileService
privacy/        PrivacyController
ui/             MainActivity (single-pane on the cover screen, nav rail + content on the inner screen),
                Screens, Dialogs
```

Requested interfaces:

- `NotificationParser`, `TemporaryMessageRepository`, `VoiceAssignmentRepository`, `VoiceSelectionResolver`, `AudioPlaybackController` and `PrivacyController` exist under those names.
- `SpeechEngine` is the common engine interface.
- The Kokoro pipeline itself (`KokoroSynthesizer`) is pure JVM code with no Android dependencies, so the exact same code runs real inference in unit tests.

### Kokoro on Android: what was verified

| Item | Value |
|---|---|
| Model | onnx-community `Kokoro-82M-v1.0-ONNX/model_quantized.onnx`, 92,361,116 bytes, SHA-256 `fbae9257…a1478` (matches the published value) |
| Inputs / output | `input_ids` int64 [1,N] (padded with 0 at both ends, ≤510 phonemes), `style` float32 [1,256] (voice row N−1), `speed` float32 [1] → `waveform` float32 at 24 kHz |
| Runtime | ONNX Runtime 1.20.0 Android AAR (Maven Central). The model uses Microsoft contrib ops, so the full ORT package is required |
| G2P | Kotlin port of misaki: gold + silver lexicon, stress rules, function words, -s/-ed/-ing, numbers, currency, times, acronyms, plus a rule-based letter-to-sound fallback in place of espeak-ng. 1.8% phoneme error rate against official misaki on 20 reference sentences |
| Inference | Real inference in unit tests: x86 container, 4 threads, ~0.9–1.05× real time, model load ~2 s |
| Intelligibility | Generated WAVs transcribed by two independent offline recognizers (Whisper-tiny.en and Moonshine-tiny via sherpa-onnx) essentially word for word ([TEST_RESULTS.md](TEST_RESULTS.md)) |
| On-device | **Not verified.** No Android device or emulator was available. See below |

### Voices (28)

| Group | Voices |
|---|---|
| American feminine | Heart (recommended), Bella, Nicole, Sarah, Sky, Nova, Alloy, Aoede, Jessica, Kore, River |
| American masculine | Michael (recommended), Adam, Onyx, Fenrir, Eric, Liam, Echo, Puck, Santa (novelty) |
| British feminine | Emma, Isabella, Alice, Lily |
| British masculine | George, Fable, Lewis, Daniel |

Presentation labels come from the model authors' naming. PrivateVoice never infers anything about a sender; you choose voices and categories yourself.

---

## Known limitations

- **No on-device testing.** The APK builds, verifies, and passes JVM and Robolectric (Android 16 framework) tests, but it has not run on a physical device or emulator. See [TEST_RESULTS.md](TEST_RESULTS.md) for the exact split.
- **Automatic reading in the background on Android 15+.**
  - Audio focus (ducking music while speaking) is only granted to the top app or an app running a foreground service.
  - PrivateVoice starts a short `mediaPlayback` foreground service while reading. Android allows that from notification actions, the tile, the floating button, or when the app is exempt from battery optimization.
  - From a bare notification-listener callback it may be refused. Reading still happens, but other audio isn't ducked, and the app tells you so. Allowing unrestricted battery use fixes this.
- **16 KB memory pages.** ONNX Runtime 1.20's JNI library is 4 KB-aligned (`libonnxruntime.so` itself is 16 KB-aligned). On a device with a 16 KB-page kernel, Android 16's compatibility mode or the built-in fallback to the system voice applies. Newer ORT releases ship Java 17 bytecode, which the available dexer (dx) cannot process.
- **Performance on the Fold7 is unmeasured.** Expect faster than the ~1× real time measured on the build container's x86 CPU. Sentence-by-sentence streaming starts playback after the first sentence is synthesized.
- **Pronunciation.**
  - Dictionary words follow misaki closely.
  - Unknown words (rare names, slang) use rule-based letter-to-sound, so they can be imperfect: "Patel" is stressed on the first syllable.
  - Heteronyms that depend on grammar (e.g. past-tense "read") use simple context rules instead of a neural tagger.
- **British voices** use the US lexicon, so they have a British voice timbre with mostly American pronunciation.
- **Message availability.**
  - PrivateVoice can only read what the messaging app puts in its notification.
  - Hidden or redacted content (e.g. "Sensitive notification content hidden", Android's OTP redaction) is reported as hidden and never guessed.
  - Apps that don't post MessagingStyle notifications give no stable sender ID, so voice assignments for them match by exact name.
- **UI** uses framework Views instead of Jetpack Compose (see [Build](#build)).
- **Debug signing only.** A release build would need your own keystore.

## Features awaiting physical-device verification

- Notification access onboarding, including the "restricted settings" flow on One UI 8
- Real Google Messages / Samsung Messages notifications: sender IDs, group messages, updates
- Kokoro load time, memory use and real-time factor on the Snapdragon 8 Elite; audio quality through AudioTrack
- Audio focus and ducking, phone-call interruption, wired / Bluetooth / car routing, disconnect handling
- The foreground service starting from each trigger (notification action, tile, overlay, auto-read with and without the battery exemption)
- Floating button drag and position across cover/inner screens, the lock-screen hide behavior, and the Quick Settings tile
- Keystore-backed encryption of voice assignments (Robolectric has no AndroidKeyStore)
- Battery impact over a day of use

## Licenses

See [LICENSES.md](LICENSES.md). The app code is MIT. Kokoro-82M, the voices and misaki are Apache-2.0, and ONNX Runtime is MIT.
