import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

// FlowForge Android application module.
//
// This module builds an installable APK WITHOUT the Android Gradle Plugin. The pipeline is the
// same one AGP runs internally, driven by plain Gradle tasks:
//
//   aapt2 compile -> aapt2 link (manifest, resources, R.java) -> kotlinc/javac
//   -> dex (d8, or dx in bootstrap mode) -> package -> zipalign -> apksigner sign/verify
//
// Two toolchains are supported (see BUILD_INSTRUCTIONS.md):
//   * Official SDK:  sdk.dir / ANDROID_HOME with platforms/android-36 and build-tools (aapt2, d8).
//   * Bootstrap:     Ubuntu's aapt2/dx/zipalign/apksigner plus AOSP framework jars from
//                    Maven Central. Used when Google's SDK hosts are unreachable.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

val appId = "com.flowforge.dictation"
val minSdk = 33
val targetSdk = 36
val appVersionCode = 1
val appVersionName = "0.1.0"

// ---------------------------------------------------------------------------------------------
// Toolchain detection
// ---------------------------------------------------------------------------------------------
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

fun prop(name: String): String? = (findProperty(name) as String?) ?: localProps.getProperty(name)

fun versionKey(name: String): List<Int> = name.split('.', '-').map { it.toIntOrNull() ?: 0 }

val sdkDir: File? = (prop("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT"))
    ?.let(::File)?.takeIf { it.isDirectory }
val officialBuildTools: File? = sdkDir?.resolve("build-tools")?.listFiles()
    ?.filter { it.resolve("aapt2").isFile && it.resolve("d8").isFile }
    ?.maxWithOrNull { a, b ->
        val ka = versionKey(a.name)
        val kb = versionKey(b.name)
        (0 until maxOf(ka.size, kb.size)).map { (ka.getOrElse(it) { 0 }).compareTo(kb.getOrElse(it) { 0 }) }
            .firstOrNull { it != 0 } ?: 0
    }
val officialPlatformJar: File? = sdkDir?.resolve("platforms/android-$targetSdk/android.jar")?.takeIf { it.isFile }
val useOfficialSdk = officialBuildTools != null && officialPlatformJar != null && prop("flowforge.forceBootstrap") != "true"
val toolchainName = if (useOfficialSdk) "official-sdk(${officialBuildTools!!.name})" else "bootstrap(aapt2+dx)"

fun tool(official: String, bootstrapProp: String, bootstrapDefault: String): File =
    if (useOfficialSdk) officialBuildTools!!.resolve(official) else File(prop(bootstrapProp) ?: bootstrapDefault)

val aapt2Tool = tool("aapt2", "flowforge.aapt2", "/usr/bin/aapt2")
val dexTool = tool("d8", "flowforge.dx", "/usr/lib/android-sdk/build-tools/debian/dx")
val zipalignTool = tool("zipalign", "flowforge.zipalign", "/usr/bin/zipalign")
val apksignerTool = tool("apksigner", "flowforge.apksigner", "/usr/bin/apksigner")

logger.info("FlowForge toolchain: $toolchainName")

// Framework jars for the bootstrap toolchain (ignored when the official SDK is present).
val androidCompileJar: Configuration by configurations.creating { isTransitive = false }
val androidLinkJar: Configuration by configurations.creating { isTransitive = false }

dependencies {
    if (!useOfficialSdk) {
        androidCompileJar(libs.android.compile)
        androidLinkJar(libs.android.link)
    }
    implementation(project(":core"))
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
}

val androidCompileClasspath: FileCollection =
    if (useOfficialSdk) files(officialPlatformJar) else androidCompileJar
val androidLinkInclude: FileCollection =
    if (useOfficialSdk) files(officialPlatformJar) else androidLinkJar

if (useOfficialSdk) {
    dependencies { compileOnly(files(officialPlatformJar)) }
} else {
    configurations.compileOnly { extendsFrom(androidCompileJar) }
}

tasks.withType<JavaCompile>().configureEach { options.release.set(8) }

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        freeCompilerArgs.addAll(
            "-Xjdk-release=1.8",
            // dx cannot desugar invokedynamic; compile lambdas/SAMs to classes instead.
            "-Xlambdas=class",
            "-Xsam-conversions=class",
            "-Xstring-concat=inline",
        )
    }
}

tasks.test { useJUnit() }

// ---------------------------------------------------------------------------------------------
// Generated sources
// ---------------------------------------------------------------------------------------------
val ffBuild = layout.buildDirectory.dir("flowforge")
val buildConfigDir = layout.buildDirectory.dir("generated/flowforge/buildconfig")
val rJavaDir = layout.buildDirectory.dir("generated/flowforge/r")

val generateBuildConfig by tasks.registering {
    val out = buildConfigDir
    inputs.property("versionName", appVersionName)
    inputs.property("versionCode", appVersionCode)
    inputs.property("toolchain", toolchainName)
    outputs.dir(out)
    doLast {
        val file = out.get().file("com/flowforge/dictation/BuildConfig.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |// Generated by app/build.gradle.kts. Do not edit.
            |package com.flowforge.dictation
            |
            |object BuildConfig {
            |    const val APPLICATION_ID = "$appId"
            |    const val VERSION_CODE = $appVersionCode
            |    const val VERSION_NAME = "$appVersionName"
            |    const val MIN_SDK = $minSdk
            |    const val TARGET_SDK = $targetSdk
            |    const val TOOLCHAIN = "$toolchainName"
            |}
            |""".trimMargin(),
        )
    }
}

val resDir = file("src/main/res")
val manifestFile = file("src/main/AndroidManifest.xml")

val compileAndroidResources by tasks.registering(Exec::class) {
    description = "Compiles Android resources with aapt2."
    val out = ffBuild.map { it.file("res/compiled.zip") }
    inputs.dir(resDir)
    outputs.file(out)
    doFirst {
        require(aapt2Tool.canExecute()) { "aapt2 not found at $aapt2Tool. Run tools/bootstrap-toolchain.sh or set sdk.dir." }
        out.get().asFile.parentFile.mkdirs()
    }
    commandLine(aapt2Tool, "compile", "--dir", resDir, "-o", out.get().asFile)
}

fun registerLink(variant: String, debuggable: Boolean) = tasks.register<Exec>("link${variant}Resources") {
    description = "Links the $variant manifest and resources with aapt2."
    dependsOn(compileAndroidResources)
    val compiled = ffBuild.map { it.file("res/compiled.zip") }
    val apOut = ffBuild.map { it.file("res/resources-$variant.ap_") }
    val rOut = ffBuild.map { it.dir("res/r-$variant") }
    inputs.file(manifestFile)
    inputs.files(compiled)
    inputs.files(androidLinkInclude)
    outputs.file(apOut)
    outputs.dir(rOut)
    doFirst {
        require(aapt2Tool.canExecute()) { "aapt2 not found at $aapt2Tool. Run tools/bootstrap-toolchain.sh or set sdk.dir." }
        rOut.get().asFile.deleteRecursively()
        val args = mutableListOf<Any>(
            aapt2Tool, "link",
            "-I", androidLinkInclude.singleFile,
            "--manifest", manifestFile,
            "-o", apOut.get().asFile,
            "--java", rOut.get().asFile,
            "--min-sdk-version", minSdk.toString(),
            "--target-sdk-version", targetSdk.toString(),
            "--version-code", appVersionCode.toString(),
            "--version-name", appVersionName,
            "--rename-manifest-package", appId,
            "--no-auto-version",
            // Code is compiled against the API 36 framework; record that instead of the link jar's level.
            "--compile-sdk-version-code", targetSdk.toString(),
            "--compile-sdk-version-name", "16",
        )
        if (debuggable) args += "--debug-mode"
        args += compiled.get().asFile
        commandLine(args)
    }
}

val linkDebugResources = registerLink("Debug", debuggable = true)
val linkReleaseResources = registerLink("Release", debuggable = false)

val generateRJava by tasks.registering(Sync::class) {
    description = "Publishes R.java generated by aapt2 as a source directory."
    dependsOn(linkReleaseResources)
    from(ffBuild.map { it.dir("res/r-Release") })
    into(rJavaDir)
}

sourceSets.main {
    java.srcDir(files(rJavaDir).builtBy(generateRJava))
}
kotlin.sourceSets.main {
    kotlin.srcDir(files(buildConfigDir).builtBy(generateBuildConfig))
}
tasks.named("compileKotlin") { dependsOn(generateRJava, generateBuildConfig) }

// ---------------------------------------------------------------------------------------------
// Dex
// ---------------------------------------------------------------------------------------------
val mergeDexInputs by tasks.registering(Jar::class) {
    description = "Merges app, core and library classes into one jar for the dexer."
    destinationDirectory.set(ffBuild.map { it.dir("dex") })
    archiveFileName.set("dex-input.jar")
    duplicatesStrategy = DuplicatesStrategy.FAIL
    from(sourceSets.main.get().output.classesDirs)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } }) {
        exclude("META-INF/**", "**/module-info.class")
        include("**/*.class")
    }
    dependsOn(tasks.named("classes"), configurations.runtimeClasspath)
}

val dexClasses by tasks.registering(Exec::class) {
    description = "Converts JVM bytecode to Dalvik bytecode (d8 or dx)."
    val input = mergeDexInputs.flatMap { it.archiveFile }
    val outDir = ffBuild.map { it.dir("dex/out") }
    inputs.file(input)
    outputs.dir(outDir)
    doFirst {
        require(dexTool.canExecute()) { "Dexer not found at $dexTool. Run tools/bootstrap-toolchain.sh or set sdk.dir." }
        outDir.get().asFile.deleteRecursively()
        outDir.get().asFile.mkdirs()
        if (useOfficialSdk) {
            commandLine(
                dexTool, "--release", "--min-api", minSdk.toString(),
                "--lib", officialPlatformJar!!, "--output", outDir.get().asFile, input.get().asFile,
            )
        } else {
            // dx predates API 33; 26 selects the newest dex format it understands (038).
            commandLine(
                dexTool, "--dex", "--multi-dex", "--min-sdk-version=26",
                "--output=${outDir.get().asFile}", input.get().asFile,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Package, align, sign
// ---------------------------------------------------------------------------------------------
fun storedEntry(name: String, bytes: ByteArray) = ZipEntry(name).apply {
    method = ZipEntry.STORED
    size = bytes.size.toLong()
    compressedSize = bytes.size.toLong()
    crc = CRC32().apply { update(bytes) }.value
}

fun registerPackage(variant: String, link: TaskProvider<Exec>) = tasks.register("package${variant}Apk") {
    description = "Assembles the unsigned, unaligned $variant APK."
    dependsOn(link, dexClasses)
    val resApk = ffBuild.map { it.file("res/resources-$variant.ap_") }
    val dexDir = ffBuild.map { it.dir("dex/out") }
    val runtimeJars = configurations.runtimeClasspath
    val out = ffBuild.map { it.file("apk/$variant-unaligned.apk") }
    inputs.file(resApk)
    inputs.dir(dexDir)
    inputs.files(runtimeJars)
    outputs.file(out)
    doLast {
        val target = out.get().asFile
        target.parentFile.mkdirs()
        // META-INF/services files are needed at runtime (e.g. coroutines' Main dispatcher).
        val services = sortedMapOf<String, StringBuilder>()
        runtimeJars.get().filter { it.name.endsWith(".jar") }.forEach { jar ->
            ZipFile(jar).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.startsWith("META-INF/services/") }
                    .forEach { e ->
                        val text = zip.getInputStream(e).readBytes().toString(Charsets.UTF_8)
                        services.getOrPut(e.name) { StringBuilder() }.append(text.trimEnd()).append('\n')
                    }
            }
        }
        ZipOutputStream(target.outputStream().buffered()).use { zos ->
            ZipFile(resApk.get().asFile).use { res ->
                res.entries().asSequence().forEach { e ->
                    val bytes = res.getInputStream(e).readBytes()
                    if (e.method == ZipEntry.STORED) {
                        zos.putNextEntry(storedEntry(e.name, bytes))
                    } else {
                        zos.putNextEntry(ZipEntry(e.name))
                    }
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }
            // Dex stored uncompressed so ART can map it directly (same as AGP for minSdk >= 28).
            dexDir.get().asFile.listFiles { f -> f.name.endsWith(".dex") }!!.sortedBy { it.name }.forEach { dex ->
                val bytes = dex.readBytes()
                zos.putNextEntry(storedEntry(dex.name, bytes))
                zos.write(bytes)
                zos.closeEntry()
            }
            services.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toString().toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
    }
}

val packageDebugApk = registerPackage("Debug", linkDebugResources)
val packageReleaseApk = registerPackage("Release", linkReleaseResources)

fun registerAlign(variant: String, pkg: TaskProvider<Task>) = tasks.register<Exec>("zipalign${variant}Apk") {
    dependsOn(pkg)
    val input = ffBuild.map { it.file("apk/$variant-unaligned.apk") }
    val out = ffBuild.map { it.file("apk/$variant-aligned.apk") }
    inputs.file(input)
    outputs.file(out)
    doFirst { require(zipalignTool.canExecute()) { "zipalign not found at $zipalignTool" } }
    commandLine(zipalignTool, "-f", "-p", "4", input.get().asFile, out.get().asFile)
}

val zipalignDebugApk = registerAlign("Debug", packageDebugApk)
val zipalignReleaseApk = registerAlign("Release", packageReleaseApk)

// The debug keystore is committed on purpose so every machine/session produces APKs that can be
// installed over each other. It is NOT a secret and must never be used for release builds.
val debugKeystore = file("debug.keystore")

val createDebugKeystore by tasks.registering(Exec::class) {
    onlyIf { !debugKeystore.exists() }
    outputs.file(debugKeystore)
    commandLine(
        "keytool", "-genkeypair", "-keystore", debugKeystore, "-storepass", "android", "-keypass", "android",
        "-alias", "androiddebugkey", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
        "-dname", "CN=FlowForge Debug,O=FlowForge,C=US", "-storetype", "PKCS12",
    )
}

data class SigningConfig(val store: File, val storePass: String, val alias: String, val keyPass: String)

fun releaseSigning(): SigningConfig? {
    val f = rootProject.file("keystore.properties").takeIf { it.isFile } ?: return null
    val p = Properties().apply { f.inputStream().use { load(it) } }
    return SigningConfig(
        rootProject.file(p.getProperty("storeFile")), p.getProperty("storePassword"),
        p.getProperty("keyAlias"), p.getProperty("keyPassword"),
    )
}

val apkOutputDir = layout.buildDirectory.dir("outputs/apk")

fun registerSign(variant: String, align: TaskProvider<Exec>) = tasks.register<Exec>("sign${variant}Apk") {
    dependsOn(align)
    if (variant == "Debug") dependsOn(createDebugKeystore)
    val input = ffBuild.map { it.file("apk/$variant-aligned.apk") }
    val out = apkOutputDir.map { it.file("${variant.lowercase()}/FlowForge-${variant.lowercase()}.apk") }
    inputs.file(input)
    outputs.file(out)
    doFirst {
        require(apksignerTool.canExecute()) { "apksigner not found at $apksignerTool" }
        out.get().asFile.parentFile.mkdirs()
        val cfg = if (variant == "Debug") {
            SigningConfig(debugKeystore, "android", "androiddebugkey", "android")
        } else {
            releaseSigning() ?: throw GradleException(
                "Release signing requires keystore.properties (storeFile, storePassword, keyAlias, keyPassword). " +
                    "See RELEASE_READINESS.md. Never commit release keys.",
            )
        }
        commandLine(
            apksignerTool, "sign",
            "--ks", cfg.store, "--ks-pass", "pass:${cfg.storePass}",
            "--ks-key-alias", cfg.alias, "--key-pass", "pass:${cfg.keyPass}",
            "--min-sdk-version", minSdk.toString(),
            "--v1-signing-enabled", "false", "--v2-signing-enabled", "true", "--v3-signing-enabled", "true",
            "--out", out.get().asFile, input.get().asFile,
        )
    }
}

val signDebugApk = registerSign("Debug", zipalignDebugApk)
val signReleaseApk = registerSign("Release", zipalignReleaseApk)

fun registerVerify(variant: String, sign: TaskProvider<Exec>) = tasks.register<Exec>("verify${variant}Apk") {
    description = "Verifies the APK signature and prints package metadata."
    dependsOn(sign)
    val apk = apkOutputDir.map { it.file("${variant.lowercase()}/FlowForge-${variant.lowercase()}.apk") }
    inputs.file(apk)
    commandLine(apksignerTool, "verify", "--min-sdk-version", minSdk.toString(), "--verbose", apk.get().asFile)
    doLast {
        val badging = providers.exec { commandLine(aapt2Tool, "dump", "badging", apk.get().asFile) }
            .standardOutput.asText.get().lineSequence().take(4).joinToString("\n")
        logger.lifecycle("FlowForge $variant APK: ${apk.get().asFile}\n$badging")
    }
}

val verifyDebugApk = registerVerify("Debug", signDebugApk)
val verifyReleaseApk = registerVerify("Release", signReleaseApk)

tasks.register("assembleDebug") {
    group = "build"
    description = "Builds, signs (debug key) and verifies FlowForge-debug.apk."
    dependsOn(verifyDebugApk)
}

tasks.register("assembleRelease") {
    group = "build"
    description = "Builds and signs FlowForge-release.apk with the key from keystore.properties."
    dependsOn(verifyReleaseApk)
}
