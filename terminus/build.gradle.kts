plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
    // :app (Android) plugins — declared here so the app module can apply them
    // without versions. :app is only included when an Android SDK is present
    // (see settings.gradle.kts). The Android Gradle Plugin itself is versioned in
    // app/build.gradle.kts instead: its artifacts live only on Google's Maven
    // repository, and declaring it here would break `:core`-only builds in
    // environments that cannot reach dl.google.com.
    kotlin("android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
