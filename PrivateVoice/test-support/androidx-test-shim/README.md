# androidx-test-shim (test-only)

Robolectric depends on `androidx.test:monitor` and `androidx.test.espresso:espresso-idling-resource`,
which are published only on Google's Maven repository (unreachable from the environment this
project was built in). This module compiles the original Apache-2.0 sources of the classes
Robolectric uses, fetched from https://github.com/android/android-test (branch `main`,
`runner/monitor/java` and `espresso/idling_resource/java`), plus minimal stand-ins for the
`androidx.annotation` / errorprone annotations they reference.

It is used **only** on the unit-test classpath. Nothing from it is packaged into the APK.
On a machine with Google Maven access you can delete this module and let Robolectric
resolve `androidx.test:monitor` normally.
