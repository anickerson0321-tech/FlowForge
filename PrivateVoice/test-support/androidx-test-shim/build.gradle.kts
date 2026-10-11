plugins { `java-library` }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
tasks.withType<JavaCompile>().configureEach { options.release.set(8); options.compilerArgs.add("-nowarn") }
dependencies {
    compileOnly("org.robolectric:android-all:16-robolectric-13921718")
}
