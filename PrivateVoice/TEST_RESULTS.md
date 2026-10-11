# Test results

Recorded on 2026-10-11 from a clean `./gradlew clean assembleDebug test testDesugared privacyAudit` run. The build environment was a Linux x86-64 container with 4 vCPUs and JDK 21.

**No Android emulator or physical device was available.** All results below come from the JVM, from Robolectric (which runs the real Android 16 framework code on the JVM), or from static checks of the APK.

## 1. Build

| Check | Result |
|---|---|
| `./gradlew assembleDebug` | ✅ BUILD SUCCESSFUL |
| APK | `app/build/outputs/apk/debug/PrivateVoice-debug.apk`, 116,456,763 bytes |
| Signature | ✅ `apksigner verify`: APK Signature Scheme v2 + v3, 1 signer (debug key) |
| Alignment | ✅ `zipalign -c 4` |
| Manifest | package `com.privatevoice.app`, minSdk 31, targetSdk 36, native code `arm64-v8a` only |
| Lambda desugaring | 131 `invokedynamic` sites rewritten across 2,375 classes; **0 remaining** (the build fails otherwise) |
| Model asset | `assets/kokoro/kokoro-v1.0-q8.onnx` stored uncompressed (memory-mapped at runtime), SHA-256 `fbae9257e1e05ffc727e951ef9b9c98418e6d79f1c9b6b13bd59f5c9028a1478` |

## 2. Automated tests: 83 passed, 0 failed

### 2a. JVM unit tests (no Android framework)

| Suite | Tests | Covers |
|---|---|---|
| NotificationParsingTest | 10 | MessagingStyle parsing, sender extraction and keys, group messages, multiple messages, own replies excluded, hidden content never invented, classic notifications, summaries/ongoing ignored, candidate app rules, control/bidi stripping, length bound |
| MessagingAndVoicesTest | 15 | Temporary message expiry and wiping, bounded capacity, clear, no text in UI state; duplicate suppression (re-posts, updated notifications); bounded tracker; voice priority (sender → category → default); announcer independence; key-based matching that doesn't leak to a same-name stranger; exact name matching; determinism; codec round trip; encrypted store persist and delete-all (key destroyed); corrupt store recovery |
| ReaderFlowTest | 15 | Manual reading flow; announcer/sender voice switching; category fallback and unknown sender; group name; repeated-sender announcement; hidden content; auto-read off by default; auto-read only on allowed routes; dedup in the full flow; sender-only mode; clearing on listener disconnect; expiry; auto-read policy (snooze, quiet hours, calls, locked, groups); quiet hours across midnight; Bluetooth not assumed private |
| EnglishG2PTest | 8 | Exact match with misaki reference; context words (the/to); suffixes; numbers, currency, times, ordinals; links, emoji, shorthand; out-of-vocabulary names; output only uses Kokoro vocabulary; long-message chunking ≤ 510 phonemes |
| MisakiAgreementTest | 1 | Kotlin G2P vs official misaki 0.9.4 (spaCy tagger) on 20 sentences: **14/20 identical, 91.0% word agreement, 1.8% phoneme error rate** |
| KokoroInferenceTest | 3 | **Real Kokoro-82M inference through ONNX Runtime** with 7 different voices on 8 sentences; speed control (0.75× → 80,012 samples vs 1.5× → 38,337); cancellation between chunks |

### 2b. Robolectric component tests (real Android 16 / API 36 framework on the JVM)

| Suite | Tests | Covers |
|---|---|---|
| AppLaunchTest | 1 | Application + dependency graph start; dashboard renders |
| ComponentTests | 18 | `NotificationListenerService` with real `Notification.MessagingStyle` / `Person` objects (individual, group, own replies, re-post/update dedup, non-message and own-package ignored, disconnect clears memory); controls notification never contains message text, PRIVATE visibility, sender-free public version, correct actions; notification permission denied → no crash and nothing posted; Dismiss action clears; **neural model failure → reports it and falls back to the system voice**; Quick Settings tile reads latest; settings persistence contains no message text; privacy status (no INTERNET); floating button show/hide/reposition with no duplicate windows, hidden when empty; every screen renders on the cover-screen width (411dp); navigation rail on the inner-screen width (841dp); **fold/unfold** configuration changes keep the current screen and switch layouts; onboarding shown without notification access |

### 2c. Same 83 tests against the APK bytecode

`./gradlew testDesugared` runs the whole suite against the **desugared classes that are dexed into the APK**: app code plus kotlin-stdlib and kotlinx-coroutines after lambda rewriting. **83 passed, 0 failed.** This validates the custom desugaring step.

### 2d. Added in the "older messages" update (included in the totals above)

- Heard messages stay available to hear again until they expire (up to 24 h); "Read latest" replays the newest when nothing is new.
- Opening a conversation in the messaging app no longer forgets the message, unless "Forget a message once I open it" is on.
- Messages already in the notification shade are added to the list but never read automatically, and aren't duplicated on reconnect.
- The test message uses the announcer and sender voices and isn't kept.
- SMS history senders use the name you gave the number, or "a number ending in …"; phone numbers match across formats (`tel:+15551234567` = `(555) 123-4567`).
- The SMS inbox query runs against a fake provider in Robolectric: permission required, newest first, read and unread messages, unread filter, limit.
- The floating button shows when enabled even with nothing waiting.
- The Text Messages screen asks before reading SMS.
- The dashboard shows the test-message button and the "last message seen" diagnostic.

Not verified: the real SMS provider and Android's permission dialog on One UI (sideload "restricted setting" behaviour for SMS access).

## 3. Neural voice verification

Real inference on the build container's CPU (4 threads):

| Voice | Text | Audio | Synthesis time |
|---|---|---|---|
| af_heart | Hello, are we still going out tonight? | 2.04 s | 2.2 s |
| am_michael | Hey man, the game starts at 7:30. Bring snacks! | 3.76 s | 3.8 s |
| af_bella | Reminder: your appointment with Dr. Patel is on March 3rd at 10am. | 4.44 s | 4.4 s |
| bm_george | Can you pick up milk, eggs and bread on the way home? Thanks! | 4.02 s | 3.6 s |
| af_sarah | omg I can't believe it 😂 call me when you get this | 3.46 s | 3.6 s |
| am_adam | The meeting moved to Thursday. Please review the $1,250 budget before then. | 5.67 s | 5.0 s |
| bm_george | Message from Sarah in Family. | 2.53 s | 2.3 s |
| am_onyx | yo r u coming to the game tmrw? bring the cooler lol | 3.07 s | 3.3 s |

Model load was about 2.0–2.3 s. Real-time factor was 0.88–1.09 on this x86 container; the Fold7 has not been measured.

**Intelligibility check.** The generated WAVs were transcribed by two independent offline speech recognizers, Whisper tiny.en and Moonshine tiny (both via sherpa-onnx 1.13.8). Neither is part of the app.

| Sample | Whisper | Moonshine |
|---|---|---|
| 0 | Hello, are we still going out tonight? | Hello, are we still going out tonight? |
| 1 | Hey Man, the game starts at 730. Bring Snacks. | Hey man the game starts at 7:30. Bring snacks. |
| 2 | Reminder. Your appointment with Dr. Paytel is on March 3 at 10am. | Reminder, your appointment with Dr. Paytel is on March 3rd at 10 a.m. |
| 3 | Can you pick up milk, eggs and bread on the way home? Thanks. | Can you pick up milk, eggs and bread on the way home? Thanks. Thank you. |
| 4 | Oh my gosh I can't believe it. Laughing call me when you get this. | Oh my gosh, I can't believe it. Laughing, call me when you |
| 5 | The meeting moved to Thursday. Please review the $1,250 budget before then. | The meeting moved to Thursday. Please review the $1,250 budget before then. |
| 6 | message from Sarah in family. | Message from Sarah in family. |
| 7 | You're you coming to the game tomorrow. Bring the cooler LOL. | Yo, are you coming to the game tomorrow? Bring the cooler L |

What the differences show:

- **"Patel"** is out of vocabulary. The rule-based fallback stresses the first syllable, so both recognizers heard "Paytel".
- **Sample 7:** Whisper misheard the unstressed "yo r" as "You're"; Moonshine transcribed it correctly. The 😂 emoji in sample 4 is read as "laughing" by design.
- **Truncated or extra words** ("call me when you", "Bring the cooler L", "Thank you") are recognizer artefacts on short tiny models; the audio contains the full sentences.

## 4. Privacy audit (`./gradlew privacyAudit`): all 38 checks passed

- **Permissions.** The APK does not request INTERNET, ACCESS_NETWORK_STATE, RECORD_AUDIO, READ_CONTACTS, READ_SMS, RECEIVE_SMS, READ_PHONE_STATE, BIND_ACCESSIBILITY_SERVICE, or READ/WRITE_EXTERNAL_STORAGE.
  - It requests only POST_NOTIFICATIONS, SYSTEM_ALERT_WINDOW, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK and REQUEST_IGNORE_BATTERY_OPTIMIZATIONS.
- **Backups.** `allowBackup=false`, and data-extraction rules exclude everything.
- **Exported components.**
  - Exported: the launcher activity, the notification listener (guarded by `BIND_NOTIFICATION_LISTENER_SERVICE`) and the tile (guarded by `BIND_QUICK_SETTINGS_TILE`).
  - Not exported: the playback service and the action receiver.
- **SDKs.** The dex input contains no Firebase, Play Services, Crashlytics, Sentry, Bugsnag, Facebook, Amplitude, Mixpanel, Segment, AppsFlyer, Adjust, Google Ads, App Center, OkHttp, Retrofit or Volley classes.
- **App sources.**
  - no logging calls, networking APIs, clipboard use, or audio-file writes
  - persistent writes only in settings and the encrypted voice-assignment store
  - settings never store message text

## 5. Not tested (requires a physical device)

No device or emulator was available. Section 2 gives Robolectric coverage of the framework code paths, which is not the same as running on hardware. These still need checking on a Galaxy Z Fold7:

- **Real ART execution**
  - the dx-generated dex and ONNX Runtime native libraries loading on the phone
  - Kokoro latency, memory and battery use on the Snapdragon 8 Elite
- **Real messaging-app notifications** from Google Messages and Samsung Messages
- **Audio behavior**
  - audio focus and ducking against real media apps
  - phone-call interruption
  - wired, Bluetooth and car routing, and disconnects
- **Foreground-service starts** from each trigger on Android 16
- **Overlay and tile UX**
  - the floating button's drag and position on the real cover and inner displays
  - the Quick Settings tile
  - the "restricted settings" onboarding flow on One UI 8
- **Encryption.** AndroidKeyStore-backed encryption (Robolectric has no AndroidKeyStore provider)
