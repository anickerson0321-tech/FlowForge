import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/*
 * PrivateVoice build.
 *
 * Google's Maven repository (AGP, AndroidX, Compose) and dl.google.com (SDK
 * manager) are not reachable from the environment this project was built in,
 * so the APK is assembled with a small, explicit pipeline instead of the
 * Android Gradle Plugin:
 *
 *   aapt2 compile/link  -> resources.ap_ + R.java   (API 36 framework from Robolectric android-all)
 *   kotlinc (JVM 1.8)   -> classes                   (compiled against android-all 16 = API 36)
 *   dx                  -> classes.dex
 *   zip                 -> unsigned APK (+ assets, native libs)
 *   zipalign, apksigner -> signed debug APK
 *
 * Tool locations can be overridden in gradle.properties / local.properties /
 * -P flags (pv.aapt2, pv.dxJar, pv.zipalign, pv.apksigner).
 */

plugins {
    kotlin("jvm")
}

val appId = "com.privatevoice.app"
val appVersionCode = 1
val appVersionName = "1.0.0"
val minSdk = 31
val targetSdk = 36
val androidAllVersion = "16-robolectric-13921718"
val ortVersion = "1.20.0"
val coroutinesVersion = "1.10.2"

fun tool(name: String, default: String): String =
    (findProperty(name) as String?) ?: System.getenv(name.uppercase().replace('.', '_')) ?: default

val aapt2 = tool("pv.aapt2", "/usr/bin/aapt2")
val dxJar = tool("pv.dxJar", "/usr/share/java/com.android.dx.jar")
val zipalign = tool("pv.zipalign", "/usr/bin/zipalign")
val apksigner = tool("pv.apksigner", "/usr/bin/apksigner")
// Framework resource table used for aapt2 linking. Attribute IDs are stable across
// releases; the API 36 table needs a newer aapt2 than Debian ships, so link against API 33.
val frameworkRes = tool("pv.frameworkRes", "/usr/share/android-framework-res/framework-res.apk")

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
        // dx cannot translate invokedynamic; keep lambdas/SAM/string concat as classic bytecode.
        freeCompilerArgs.addAll(
            "-Xlambdas=class",
            "-Xsam-conversions=class",
            "-Xstring-concat=inline",
            "-Xno-param-assertions",
            "-Xno-call-assertions",
            "-Xno-receiver-assertions",
        )
    }
}
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}
tasks.withType<JavaCompile>().configureEach { options.release.set(8) }

val androidPlatform: Configuration by configurations.creating
val androidAar: Configuration by configurations.creating { isTransitive = false }

dependencies {
    androidPlatform("org.robolectric:android-all:$androidAllVersion")
    compileOnly("org.robolectric:android-all:$androidAllVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")
    // ONNX Runtime: the JVM artifact shares the exact Java API with the Android AAR.
    // It is used to compile and to run real inference in unit tests; the APK packages
    // the Android AAR's classes.jar and arm64 native libraries instead.
    compileOnly("com.microsoft.onnxruntime:onnxruntime:$ortVersion")
    androidAar("com.microsoft.onnxruntime:onnxruntime-android:$ortVersion@aar")

    testImplementation("junit:junit:4.13.2")
    // Robolectric runs the real Android 16 framework on the JVM for component tests.
    testImplementation("org.robolectric:robolectric:4.16") {
        exclude(group = "androidx.test")
        exclude(group = "androidx.test.espresso")
    }
    testImplementation(project(":androidx-test-shim"))
    testImplementation("org.robolectric:android-all:$androidAllVersion")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:$ortVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
}

val srcMain = layout.projectDirectory.dir("src/main")
val genDir = layout.buildDirectory.dir("generated/aapt2/java")
val interDir = layout.buildDirectory.dir("intermediates")
val outDir = layout.buildDirectory.dir("outputs/apk/debug")

sourceSets {
    main {
        kotlin.srcDir("src/main/kotlin")
        java.srcDir(genDir)
        resources.setSrcDirs(emptyList<String>())
    }
    test {
        kotlin.srcDir("src/test/kotlin")
    }
}

// ---------------------------------------------------------------------------
// Voice model assets (fetched at build time, verified by SHA-256, never at runtime)
// ---------------------------------------------------------------------------
apply(from = "voice-assets.gradle.kts")
val prepareVoiceAssets = tasks.named("prepareVoiceAssets")
val voiceAssetsDir = layout.buildDirectory.dir("generated/voiceAssets")

// ---------------------------------------------------------------------------
// Resources
// ---------------------------------------------------------------------------
val compileResources by tasks.registering(Exec::class) {
    group = "android"
    val resDir = srcMain.dir("res")
    val out = interDir.map { it.file("res/compiled.zip") }
    inputs.dir(resDir)
    outputs.file(out)
    doFirst { out.get().asFile.parentFile.mkdirs() }
    commandLine(aapt2, "compile", "--dir", resDir.asFile.absolutePath, "-o", out.get().asFile.absolutePath)
}

val linkResources by tasks.registering(Exec::class) {
    group = "android"
    dependsOn(compileResources)
    val manifest = srcMain.file("AndroidManifest.xml")
    val compiled = interDir.map { it.file("res/compiled.zip") }
    val ap = interDir.map { it.file("res/resources.ap_") }
    inputs.file(manifest)
    inputs.file(compiled)
    inputs.file(frameworkRes)
    outputs.file(ap)
    outputs.dir(genDir)
    doFirst {
        genDir.get().asFile.deleteRecursively()
        genDir.get().asFile.mkdirs()
    }
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "link",
            "-I", frameworkRes,
            "--manifest", manifest.asFile.absolutePath,
            "--java", genDir.get().asFile.absolutePath,
            "--min-sdk-version", "$minSdk",
            "--target-sdk-version", "$targetSdk",
            "--version-code", "$appVersionCode",
            "--version-name", appVersionName,
            "--rename-manifest-package", appId,
            "--auto-add-overlay",
            "--no-version-vectors",
            "-o", ap.get().asFile.absolutePath,
            compiled.get().asFile.absolutePath,
        )
    })
    executable = aapt2
}
tasks.named("compileKotlin") { dependsOn(linkResources) }
tasks.named("compileJava") { dependsOn(linkResources) }

// ---------------------------------------------------------------------------
// Native libraries + Android ORT classes from the AAR
// ---------------------------------------------------------------------------
val extractAar by tasks.registering(Copy::class) {
    group = "android"
    val dest = interDir.map { it.dir("aar/onnxruntime") }
    from({ zipTree(androidAar.singleFile) }) {
        include("classes.jar", "jni/arm64-v8a/**")
    }
    into(dest)
}

// ---------------------------------------------------------------------------
// Dex
// ---------------------------------------------------------------------------
val desugar by tasks.registering {
    group = "android"
    description = "Rewrites Java 8 lambdas (invokedynamic) into classes so dx output runs on ART."
    dependsOn("classes", extractAar)
    val classDirs = sourceSets.main.get().output.classesDirs
    val runtimeJars = configurations.runtimeClasspath.get()
    val aarClasses = interDir.map { it.file("aar/onnxruntime/classes.jar") }
    val out = interDir.map { it.file("desugar/desugared.jar") }
    inputs.files(classDirs, runtimeJars, aarClasses)
    outputs.file(out)
    doLast {
        val result = LambdaDesugarer.desugar(classDirs.files + runtimeJars.files + aarClasses.get().asFile, out.get().asFile)
        val remaining = LambdaDesugarer.countIndy(out.get().asFile)
        println("Desugared ${result.lambdaSites} lambda call sites across ${result.classes} classes; remaining invokedynamic: $remaining")
        check(remaining == 0) { "invokedynamic instructions remain after desugaring" }
    }
}

val dex by tasks.registering(JavaExec::class) {
    group = "android"
    dependsOn(desugar)
    val dexOut = interDir.map { it.dir("dex") }
    val desugared = interDir.map { it.file("desugar/desugared.jar") }
    inputs.file(desugared)
    outputs.dir(dexOut)
    classpath = files(dxJar)
    mainClass.set("com.android.dx.command.Main")
    maxHeapSize = "3g"
    doFirst {
        dexOut.get().asFile.deleteRecursively()
        dexOut.get().asFile.mkdirs()
        args = listOf(
            "--dex", "--multi-dex", "--min-sdk-version=26",
            "--output=${dexOut.get().asFile.absolutePath}", desugared.get().asFile.absolutePath,
        )
    }
}

// ---------------------------------------------------------------------------
// Package, align, sign
// ---------------------------------------------------------------------------
val packageApk by tasks.registering {
    group = "android"
    dependsOn(linkResources, dex, extractAar, prepareVoiceAssets)
    val ap = interDir.map { it.file("res/resources.ap_") }
    val dexDir = interDir.map { it.dir("dex") }
    val jniDir = interDir.map { it.dir("aar/onnxruntime/jni") }
    val assetsSrc = srcMain.dir("assets")
    val unsigned = interDir.map { it.file("apk/app-unsigned.apk") }
    inputs.file(ap); inputs.dir(dexDir); inputs.dir(jniDir); inputs.dir(voiceAssetsDir)
    inputs.files(fileTree(assetsSrc))
    outputs.file(unsigned)
    doLast {
        val out = unsigned.get().asFile
        out.parentFile.mkdirs()
        ZipOutputStream(out.outputStream().buffered()).use { zos ->
            fun stored(name: String, bytes: ByteArray) {
                val e = ZipEntry(name)
                e.method = ZipEntry.STORED
                e.size = bytes.size.toLong(); e.compressedSize = bytes.size.toLong()
                e.crc = CRC32().apply { update(bytes) }.value
                zos.putNextEntry(e); zos.write(bytes); zos.closeEntry()
            }
            fun deflated(name: String, bytes: ByteArray) {
                zos.putNextEntry(ZipEntry(name)); zos.write(bytes); zos.closeEntry()
            }
            ZipFile(ap.get().asFile).use { zf ->
                for (e in zf.entries()) {
                    val bytes = zf.getInputStream(e).readBytes()
                    if (e.method == ZipEntry.STORED || e.name == "resources.arsc") stored(e.name, bytes) else deflated(e.name, bytes)
                }
            }
            dexDir.get().asFile.listFiles()!!.filter { it.name.endsWith(".dex") }.sortedBy { it.name }
                .forEach { deflated(it.name, it.readBytes()) }
            jniDir.get().asFile.walkTopDown().filter { it.isFile && it.name.endsWith(".so") }.forEach {
                deflated("lib/" + it.relativeTo(jniDir.get().asFile).invariantSeparatorsPath, it.readBytes())
            }
            val assetRoots = listOf(assetsSrc.asFile, voiceAssetsDir.get().asFile).filter { it.exists() }
            assetRoots.forEach { root ->
                root.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { f ->
                    val name = "assets/" + f.relativeTo(root).invariantSeparatorsPath
                    // Model and voice tensors are memory-mapped at runtime: store uncompressed.
                    if (f.name.endsWith(".onnx") || f.name.endsWith(".bin")) stored(name, f.readBytes())
                    else deflated(name, f.readBytes())
                }
            }
        }
    }
}

val alignApk by tasks.registering(Exec::class) {
    group = "android"
    dependsOn(packageApk)
    val unsigned = interDir.map { it.file("apk/app-unsigned.apk") }
    val aligned = interDir.map { it.file("apk/app-aligned.apk") }
    inputs.file(unsigned); outputs.file(aligned)
    commandLine(zipalign, "-f", "-p", "4", unsigned.get().asFile.absolutePath, aligned.get().asFile.absolutePath)
}

val debugKeystore = File(System.getProperty("user.home"), ".android/debug.keystore")

val createDebugKeystore by tasks.registering(Exec::class) {
    group = "android"
    onlyIf { !debugKeystore.exists() }
    doFirst { debugKeystore.parentFile.mkdirs() }
    commandLine(
        "keytool", "-genkeypair", "-keystore", debugKeystore.absolutePath, "-storepass", "android",
        "-alias", "androiddebugkey", "-keypass", "android", "-keyalg", "RSA", "-keysize", "2048",
        "-validity", "10000", "-dname", "CN=Android Debug,O=Android,C=US",
    )
}

val signApk by tasks.registering(Exec::class) {
    group = "android"
    dependsOn(alignApk, createDebugKeystore)
    val aligned = interDir.map { it.file("apk/app-aligned.apk") }
    val signed = outDir.map { it.file("PrivateVoice-debug.apk") }
    inputs.file(aligned); outputs.file(signed)
    doFirst { signed.get().asFile.parentFile.mkdirs() }
    commandLine(
        apksigner, "sign", "--ks", debugKeystore.absolutePath, "--ks-pass", "pass:android",
        "--ks-key-alias", "androiddebugkey", "--key-pass", "pass:android",
        "--min-sdk-version", "$minSdk",
        "--v2-signing-enabled", "true", "--v3-signing-enabled", "true",
        "--out", signed.get().asFile.absolutePath, aligned.get().asFile.absolutePath,
    )
}

tasks.register("assembleDebug") {
    group = "android"
    description = "Builds, aligns and signs the debug APK."
    dependsOn(signApk)
    doLast {
        val apk = outDir.get().file("PrivateVoice-debug.apk").asFile
        val sha = MessageDigest.getInstance("SHA-256").digest(apk.readBytes()).joinToString("") { "%02x".format(it) }
        File(apk.parentFile, apk.name + ".sha256").writeText("$sha  ${apk.name}\n")
        println("APK: ${apk.absolutePath}")
        println("Size: ${apk.length()} bytes")
        println("SHA-256: $sha")
    }
}

// Robolectric reads this file (normally generated by the Android Gradle Plugin) from the test classpath.
val robolectricConfigDir = layout.buildDirectory.dir("generated/robolectric")
val generateRobolectricConfig by tasks.registering {
    dependsOn(linkResources)
    val out = robolectricConfigDir.map { it.file("com/android/tools/test_config.properties") }
    outputs.file(out)
    doLast {
        out.get().asFile.apply { parentFile.mkdirs() }.writeText(
            "android_merged_manifest=" + srcMain.file("AndroidManifest.xml").asFile.absolutePath + "\n" +
                "android_resource_apk=" + interDir.get().file("res/resources.ap_").asFile.absolutePath + "\n" +
                "android_merged_assets=" + srcMain.dir("assets").asFile.absolutePath + "\n" +
                "android_custom_package=$appId\n",
        )
    }
}
sourceSets { test { resources.srcDir(robolectricConfigDir) } }
tasks.named("processTestResources") { dependsOn(generateRobolectricConfig) }

tasks.withType<Test>().configureEach {
    maxHeapSize = "4g"
    systemProperty("pv.voiceAssets", voiceAssetsDir.get().asFile.absolutePath)
    // Robolectric: fetch android-all-instrumented through the same Maven Central mirror, keep it offline afterwards.
    systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2")
    systemProperty("robolectric.dependency.repo.id", "central-mirror")
    systemProperty("robolectric.logging.enabled", "false")
    dependsOn(linkResources)
    systemProperty("pv.testOutput", layout.buildDirectory.dir("test-audio").get().asFile.absolutePath)
    dependsOn(prepareVoiceAssets)
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
apply(from = "privacy-audit.gradle.kts")

// Runs the whole unit-test suite against the desugared classes that are actually dexed into the APK
// (app + kotlin-stdlib + coroutines after lambda rewriting), validating the desugarer's semantics.
tasks.register<Test>("testDesugared") {
    group = "verification"
    description = "Runs unit tests against the desugared (APK) bytecode."
    dependsOn(desugar, "testClasses")
    val desugaredJar = interDir.map { it.file("desugar/desugared.jar") }
    val mainOutputs = sourceSets.main.get().output.classesDirs.files
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = files(sourceSets.test.get().output) +
        // Library jars except those replaced by their desugared copies; JVM ONNX Runtime stays ahead of
        // the Android ORT classes inside the desugared jar so inference runs on the desktop natives.
        configurations.testRuntimeClasspath.get().filter { f ->
            f !in mainOutputs && !f.name.startsWith("kotlin-stdlib") && !f.name.startsWith("kotlinx-coroutines-core") &&
                !f.name.startsWith("kotlinx-coroutines-android")
        } + files(desugaredJar)
}
