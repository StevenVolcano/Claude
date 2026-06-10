// AGP lives only on Google's Maven repository, which some environments (e.g.
// the cloud container used for :core-only work) cannot reach. Resolve it onto
// the ROOT build classpath — required so the Kotlin Android plugin (also root
// classpath) can see AGP classes — but only when an Android SDK is present.
// This mirrors the :app inclusion check in settings.gradle.kts.
buildscript {
    val androidSdkPresent = System.getenv("ANDROID_HOME") != null ||
        System.getenv("ANDROID_SDK_ROOT") != null ||
        File(rootDir, "local.properties").let { it.exists() && it.readText().contains("sdk.dir") }
    if (androidSdkPresent) {
        repositories {
            google()
            mavenCentral()
        }
        dependencies {
            classpath("com.android.tools.build:gradle:8.7.3")
        }
    }
}

plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
    // Same artifact as kotlin("jvm") (resolved from Maven Central / Plugin
    // Portal, no Google repo needed); it only needs AGP classes when actually
    // applied, which happens solely in :app on SDK-equipped machines.
    kotlin("android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
