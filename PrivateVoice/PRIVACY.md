# PrivateVoice privacy design

PrivateVoice reads message notifications aloud on your phone. It is built so that message content exists only briefly, only in memory, and only on the device.

## What PrivateVoice never does

- **No internet permission.** The APK does not request `android.permission.INTERNET`, so the app process cannot open network connections. This is checked by the automated audit.
- **No cloud or analytics.** There is no Firebase, Google Play Services, advertising, analytics or crash-reporting SDK, and no HTTP client library. This is checked against the classes that go into the APK.
- **No message storage.** Message text is never written to SQLite/Room, SharedPreferences/DataStore, files, logs, crash reports, the clipboard, Android backups, or temporary audio files.
- **No logging.** App code contains no `Log.*`, `println` or `printStackTrace` calls.
- **No extra permissions.** It does not request the microphone, contacts, phone state, storage or accessibility, and never sends or receives SMS.
- **SMS history is opt-in.** The optional Text Messages screen asks for `READ_SMS` only when you turn it on. Texts are queried from the inbox when you open that screen or tap ▶, never copied or stored, and the permission can be switched off again from the same screen.
- **No bypassing restrictions.** It does not use an AccessibilityService to get around notification limits.

## How message content is handled

1. Android delivers a message notification to PrivateVoice's `NotificationListenerService`. You grant this access in system settings.
2. The sender, conversation title and text are copied into a short-lived in-memory object.
   - These objects are held in a bounded list of at most 20 items.
   - Each expires after a configurable time: 1 hour by default; 10 minutes, 4 hours or 24 hours can be chosen.
   - A message stays in this list after it is read aloud or after you open the conversation in your messaging app, so you can hear it again later. You can choose to forget it on opening instead.
   - At most 50 messages are held.
3. De-duplication uses notification metadata only: the notification key plus the message timestamp. No message text or content hash is kept for this.
4. When a message is read, its text goes to the on-device speech engine. The generated audio stays in memory and is played with `AudioTrack`.
   - The last reading's audio is kept in memory for **Repeat** for at most 30 minutes.
5. Temporary content is dropped and audio buffers are zeroed in these cases:
   - you tap **Dismiss** or **Clear temporary content now**
   - the message expires
   - automatic reading is turned off
   - notification access is revoked or the listener disconnects
   - the app process ends

PrivateVoice's own notification shows only the sender's name. On the lock screen it shows only "New message ready to read". It never contains message text.

## What *is* stored, on purpose

| Data | Where | Protection |
|---|---|---|
| Settings (toggles, chosen voices, speed, quiet hours, approved Bluetooth device names) | App-private SharedPreferences | Excluded from backup and device transfer |
| **Sender-to-voice assignments** (the name you typed or the messaging app's sender ID, app package, chosen voice/category) | App `no_backup` directory | AES-256-GCM with a non-exportable Android Keystore key; excluded from backup |

Voice assignments are a deliberate exception to "store nothing": they are the preferences you create. They never include message text. **Privacy Center → Delete all** removes the file (overwriting it first) and destroys the Keystore key.

The neural voice model, voice tensors and pronunciation lexicon are bundled in the APK. They contain no user data.

## Honest limitations

- **Android keeps its own copy of notifications** (in the notification shade and the system's notification history if enabled). PrivateVoice cannot erase those.
- **No guaranteed memory erasure.** Java/Kotlin strings are immutable and garbage-collected, and the OS may keep memory pages around. PrivateVoice drops references promptly and zeroes its audio buffers, but it cannot guarantee that no trace of a message remains in RAM. This is not cryptographic erasure.
- **Bluetooth isn't necessarily private.** Bluetooth outputs can be car systems or speakers. "Headphones only" counts a Bluetooth device as headphones only after you approve it by name. BLE speakers never count.
- **System voice runs in another app.** If you choose the system voice, or the neural engine can't load, speech is produced by your phone's installed text-to-speech app. PrivateVoice only selects voices that report no network requirement, but that engine is a separate app with its own privacy policy.
- **Flash storage.** Overwriting a file before deleting it is best effort on flash storage; the Keystore key deletion is what makes old ciphertext unreadable.

## Verifying it yourself

```bash
./gradlew privacyAudit      # report: app/build/reports/privacy-audit.txt
aapt2 dump permissions app/build/outputs/apk/debug/PrivateVoice-debug.apk
```

The audit fails the build if any of the following is found:

- a network or sensitive permission
- backups enabled
- an exported component without a system-only permission
- a known analytics, ads or network SDK
- a logging call
- a networking API
- clipboard use
- an audio-file write
- a persistent write outside the two allowed stores
