# FlowForge — Build & Installation Instructions

FlowForge builds with **Gradle only** — no Android Gradle Plugin (AGP), no Android Studio required.
`app/build.gradle.kts` runs the same steps AGP runs internally:

```
aapt2 compile → aapt2 link (manifest, resources, R.java) → kotlinc/javac
→ dex (d8, or dx in bootstrap mode) → package → zipalign → apksigner sign + verify
```

Why: the environment this project was created in blocks `dl.google.com`, which serves both the
Android SDK and Google's Maven repository (AGP, AndroidX, Jetpack Compose, D8/R8). The pipeline
therefore supports two toolchains and picks one automatically.

## 1. Requirements

| Item | Version | Notes |
|---|---|---|
| JDK | 17 or 21 | Built and tested with OpenJDK 21.0.12 |
| Gradle | 8.14.3 | Downloaded by `./gradlew` (SHA-256 pinned in `gradle/wrapper/gradle-wrapper.properties`) |
| Python 3 | 3.8+ | Only for `./gradlew check` (public-API check, `tools/api_check.py`) |
| Toolchain | **one** of A or B below | |

### Toolchain A — official Android SDK (preferred when available)
Install with Android Studio or `sdkmanager`:
- `platforms;android-36`
- `build-tools;36.0.0` (any version containing `aapt2`, `d8`, `zipalign`, `apksigner`)

Then point Gradle at it, either with `ANDROID_HOME` or a `local.properties` file:
```
sdk.dir=/path/to/Android/Sdk
```
The build log shows `official-sdk(36.x.x)` as the toolchain (also printed in the app under Help → Diagnostics).

### Toolchain B — bootstrap (what produced the delivered APK)
Used automatically when no official SDK is found (or with `-Pflowforge.forceBootstrap=true`).
- Ubuntu/Debian packages: `aapt` (aapt2, Android 14 build), `dalvik-exchange` (dx), `zipalign`,
  `apksigner` — install with `tools/bootstrap-toolchain.sh` (uses apt; needs sudo if not root).
- Android framework jars, downloaded by Gradle from Maven Central (`org.robolectric:android-all`,
  built from AOSP release tags):
  - **Android 16 / API 36** (`16-robolectric-13921718`, build BLR2.250402.052) — compile classpath.
  - **Android 14 / API 34** (`14-robolectric-10818077`, build UP1A.231105.001) — resource linking
    only, because the distro aapt2 cannot parse Android 15+ resource tables. Public resource IDs
    are stable across API levels, so this is safe for the attributes FlowForge uses.

Tool paths can be overridden in `local.properties` or with `-P`:
`flowforge.aapt2`, `flowforge.dx`, `flowforge.zipalign`, `flowforge.apksigner`.

Limitations of the bootstrap toolchain (documented, not hidden):
- `dx` instead of `d8`: no code shrinking/optimization (dex ≈ 3.6 MB; Kotlin is compiled with
  `-Xlambdas=class` so no `invokedynamic` desugaring is needed, and every library must be Java 8
  bytecode without `invokedynamic` — which is why jbzip2 is used instead of Commons Compress).
- Ubuntu's `zipalign` has no `-P 16` (16 KB page alignment of uncompressed native libraries), so
  the native libraries are packaged compressed with `android:extractNativeLibs="true"`; Android
  extracts them at install time.
- The framework jar contains hidden APIs too. `./gradlew check` therefore runs
  `tools/api_check.py`, which fails the build if any call is not part of Android 16's public API
  or is newer than minSdk 33 without a reviewed version guard.

## 2. Build

```bash
./gradlew assembleDebug        # → app/build/outputs/apk/debug/FlowForge-debug.apk (signed, verified)
./gradlew check                # unit tests (core + app) and the public-API check
./gradlew test                 # unit tests only
./gradlew :app:apiCheck        # public-API check only (report: app/build/reports/api-check.txt)
```

`assembleDebug` ends by running `apksigner verify` and printing the package metadata, e.g.:
```
package: name='com.flowforge.dictation' versionCode='2' versionName='0.2.0' ... compileSdkVersion='36'
sdkVersion:'33'
targetSdkVersion:'36'
```

All Maven dependencies come from Maven Central (via Google's official Maven Central mirror on Cloud
Storage, falling back to repo.maven.apache.org). Maven Central rate-limits bursts with HTTP 429;
`gradle.properties` makes Gradle retry patiently.

The offline speech runtime, **sherpa-onnx 1.13.8** (Apache-2.0), is not on Maven Central. Gradle
downloads its AAR from the project's GitHub release
(`https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar`, declared
in `settings.gradle.kts` for that single module) and checks it against the SHA-256 in
`gradle/verification-metadata.xml`. `extractSherpaOnnx` unpacks `classes.jar` (compiled against
and dexed into the app) and the two `arm64-v8a` libraries the app needs (`libonnxruntime.so`,
`libsherpa-onnx-jni.so`), which `package*Apk` adds under `lib/arm64-v8a/`. So the build needs
access to `github.com` (and its release-asset CDN) as well as Maven Central.

Speech **models** are not part of the build or the APK; the app downloads the one the user picks.

### Optional: offline model integration tests
These run FlowForge's offline recognition code on a desktop against real models, using sherpa-onnx's
Linux build of the same native library (`sherpa-onnx-v1.13.8-linux-x64-jni.tar.bz2` from the same
release page) and a model archive from `https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models`
(e.g. `sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.tar.bz2`, extracted):
```bash
FLOWFORGE_REAL_ARCHIVE=/path/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.tar.bz2 \
  ./gradlew :core:test --tests '*ModelInstallerTest*'          # install the real archive
FLOWFORGE_SHERPA_LIB=/path/sherpa-onnx-v1.13.8-linux-x64-jni/lib \
FLOWFORGE_MODEL_DIR=/path/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8 \
FLOWFORGE_MODEL_ID=parakeet-tdt-0.6b-v2-int8 FLOWFORGE_TEST_WAVS=a.wav,b.wav \
  ./gradlew :app:test --tests '*LocalTranscriberIntegrationTest*' -i   # 16 kHz mono 16-bit WAVs
```
Without these variables the tests are skipped.

### Signing
- **Debug:** `app/debug.keystore` is committed on purpose (password `android`) so APKs built on
  any machine can be installed over each other. It is not a secret and must never sign a release.
- **Release:** create `keystore.properties` in the project root (git-ignored):
  ```
  storeFile=/absolute/path/release.jks
  storePassword=...
  keyAlias=...
  keyPassword=...
  ```
  then `./gradlew assembleRelease`. Note: Google Play requires an Android App Bundle (AAB); see
  RELEASE_READINESS.md for the path to AAB builds.

## 3. Install on a phone (Galaxy Z Fold7 or any Android 13+ device)

### Option 1 — adb (USB or wireless debugging)
1. On the phone: Settings → About phone → Software information → tap **Build number** 7 times;
   then Settings → Developer options → enable **USB debugging** (or Wireless debugging).
2. On the computer: `adb install -r FlowForge-debug.apk`

### Option 2 — sideload the file directly
1. Copy `FlowForge-debug.apk` to the phone (or download it from the repository's `dist/` folder).
2. Open it with My Files / Files and allow "Install unknown apps" for that app when asked.
3. Samsung Auto Blocker (Settings → Security and privacy → Auto Blocker) must be off to sideload.

### First run
1. Open FlowForge → follow **Setup & permissions**: allow Microphone and Notifications.
2. Allow **Display over other apps** so the bubble can float over your apps (you keep your normal keyboard).
3. Turn on **FlowForge text insertion** (an accessibility service) so the bubble can type for you.
   Because the APK is sideloaded, Android 13+ blocks this switch at first ("Restricted setting").
   Do these in order (Setup shows a button for each):
   1. Settings → Accessibility → Installed apps → **FlowForge text insertion** → try to turn it on →
      Android says "Restricted setting" → tap OK. (This first attempt is what makes the unlock appear.)
   2. Settings → Apps → **FlowForge** → **⋮** (top right) → **Allow restricted settings** → confirm.
   3. Back to Accessibility → FlowForge text insertion → turn it on → Allow.
4. Speech engine → **Download** the offline model (Parakeet: 482 MB download, 661 MB installed;
   Moonshine: 251 MB / 287 MB). Wi-Fi recommended. Until it is installed, Android's recognizer is used.
5. Optional: add the **FlowForge bubble** tile to Quick Settings for one-tap access.

Using it: **hold** the bubble, talk, **let go** — the text is typed at your cursor. Or **tap** once,
talk, and tap again. Slide your finger far away before letting go to cancel. To check text before
it is inserted, choose Dictation settings → Insertion → **Preview first**.

Updating from 0.1.0: install over it (same debug key); your data is kept. The FlowForge keyboard is
gone in 0.2.0 — if it was your active keyboard, Android switches back to your default keyboard.
Updating: install a newer APK over the old one (same debug key) — your data is kept.
Uninstalling removes all FlowForge data (nothing is backed up by design).

## 4. Troubleshooting the build

| Symptom | Fix |
|---|---|
| `aapt2 not found` / `Dexer not found` | Run `tools/bootstrap-toolchain.sh`, or set `sdk.dir` to an official SDK |
| `Failed to retrieve resource table` | The aapt2 in use can't read the link jar. Use the configured API 34 link jar (default) or the official SDK |
| `PARSE ERROR: unknown tag byte` from dx | A dependency contains Java 9+ class files; FlowForge strips `META-INF/**` and `module-info.class` already — check new dependencies |
| HTTP 429 from Maven Central | Re-run; retries are configured. The Cloud Storage mirror is tried first |
| `Dependency verification failed` for `com.k2fsa:sherpa-onnx` | The AAR downloaded from GitHub doesn't match the pinned SHA-256. Don't update the pin without checking the release asset independently |
| `Could not GET 'https://github.com/k2-fsa/...'` | `github.com` (or its release-asset CDN) isn't reachable from the build machine |
| `API check failed` | Read `app/build/reports/api-check.txt`; replace the hidden API, or guard newer APIs with `Build.VERSION.SDK_INT` and list them in `tools/api_guarded_allowlist.txt` |
