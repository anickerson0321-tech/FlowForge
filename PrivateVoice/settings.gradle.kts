pluginManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2") { name = "MavenCentralMirror" }
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2") { name = "MavenCentralMirror" }
        mavenCentral()
    }
}
rootProject.name = "PrivateVoice"
include(":app")
include(":androidx-test-shim")
project(":androidx-test-shim").projectDir = file("test-support/androidx-test-shim")
