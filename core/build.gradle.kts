import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM module holding all platform-independent FlowForge logic
// (cleanup, dictionary, insertion planning, session state, privacy policy).
// It is bundled into the APK, so it must stay dex-compatible: Java 8 bytecode,
// Java 8 JDK API surface, and no invokedynamic (lambdas compiled as classes).
plugins {
    alias(libs.plugins.kotlin.jvm)
}


tasks.withType<JavaCompile>().configureEach { options.release.set(8) }

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        freeCompilerArgs.addAll(
            "-Xjdk-release=1.8",
            "-Xlambdas=class",
            "-Xsam-conversions=class",
            "-Xstring-concat=inline",
        )
    }
}

dependencies {
    // Provided by the Android framework at runtime (android.jar includes org.json).
    compileOnly(libs.org.json)
    // Decompresses downloaded speech-model archives (.tar.bz2).
    implementation(libs.jbzip2)
    testImplementation(libs.org.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
}

tasks.test {
    useJUnit()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
