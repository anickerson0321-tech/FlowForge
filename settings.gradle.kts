// FlowForge build settings.
//
// Every dependency resolves from Maven Central. Google's official Cloud Storage mirror of
// Maven Central is listed first because the canonical host rate-limits heavy CI traffic;
// Gradle falls back to repo.maven.apache.org if the mirror lacks an artifact.
// Google Maven (maven.google.com) is intentionally NOT required: see BUILD_INSTRUCTIONS.md.
pluginManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralMirror" }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralMirror" }
        mavenCentral()
    }
}

rootProject.name = "FlowForge"
include(":core", ":app")
