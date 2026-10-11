# Licenses

## PrivateVoice source code

The PrivateVoice application source code in this repository is provided under the **MIT License** (see the end of this file). The repository owner may relicense their own code as they wish.

## Bundled in the APK

| Component | Version | License | Source |
|---|---|---|---|
| Kokoro-82M (model weights) | v1.0, 8-bit ONNX export | Apache-2.0 | hexgrad/Kokoro-82M; export by onnx-community/Kokoro-82M-v1.0-ONNX; obtained from npm `kokoro-q8-shards@1.0.0` (byte-identical, SHA-256 verified) |
| Kokoro voice style tensors (28 English voices) | v1.0 | Apache-2.0 | npm `kokoro-js@1.2.1` (`voices/*.bin`) |
| misaki pronunciation lexicon (`us_gold.json`, `us_silver.json`); G2P rules ported to Kotlin | 0.9.4 | Apache-2.0 | PyPI `misaki` / github.com/hexgrad/misaki |
| ONNX Runtime for Android | 1.20.0 | MIT | Maven Central `com.microsoft.onnxruntime:onnxruntime-android` |
| Kotlin Standard Library | 2.2.21 | Apache-2.0 | JetBrains |
| kotlinx.coroutines (core + android) | 1.10.2 | Apache-2.0 | JetBrains |
| JetBrains Java Annotations | 23.0.0 | Apache-2.0 | JetBrains |

No other code is bundled. The app shows the same notices under **About → Open-source licenses** (`app/src/main/assets/licenses.txt`).

## Build and test only (not in the APK)

| Component | License | Use |
|---|---|---|
| Robolectric `android-all` 16 (Android framework) | Apache-2.0 | Compile classpath (API 36) and Robolectric tests |
| Robolectric 4.16 | MIT | Component tests |
| androidx.test monitor / espresso idling-resource sources (vendored in `test-support/androidx-test-shim`) | Apache-2.0 | Robolectric runtime dependency (Google Maven unavailable) |
| ONNX Runtime JVM 1.20.0 | MIT | Real inference in unit tests |
| ASM 9.8 | BSD-3-Clause | Lambda desugaring in `buildSrc` |
| JUnit 4.13.2 | EPL-1.0 | Tests |
| Debian/Ubuntu aapt2, dx (dalvik-exchange), zipalign, apksigner, framework-res | Apache-2.0 | APK build tools |
| Gradle 8.14.3 (wrapper) | Apache-2.0 | Build |

## Commercial use notes

- Kokoro-82M and misaki are Apache-2.0. Redistribution, including commercial use, is permitted with attribution and the license notice. Kokoro's model card asks that it not be used for impersonation or deception.
- ONNX Runtime is MIT.
- Nothing in the APK is GPL/LGPL. espeak-ng (GPL-3.0), which Kokoro's reference pipeline normally uses for unknown words, is **not** used. A rule-based letter-to-sound fallback is used instead.

---

### MIT License

```
Copyright (c) 2026 PrivateVoice contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### Apache License 2.0

Full text: https://www.apache.org/licenses/LICENSE-2.0
