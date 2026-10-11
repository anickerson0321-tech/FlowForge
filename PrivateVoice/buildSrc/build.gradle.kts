plugins {
    `kotlin-dsl`
}
repositories {
    maven("https://maven-central.storage-download.googleapis.com/maven2")
    mavenCentral()
    gradlePluginPortal()
}
dependencies {
    implementation("org.ow2.asm:asm:9.8")
    implementation("org.ow2.asm:asm-tree:9.8")
}
