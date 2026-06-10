rootProject.name = "transit-hideout"

include(":core")

// The Android app module needs the Android SDK and Google's Maven repository.
// Include it only when an SDK is available so the pure-JVM core can be built
// and tested in environments without Android tooling.
val androidSdkPresent = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    File(rootDir, "local.properties").let { it.exists() && it.readText().contains("sdk.dir") }

if (androidSdkPresent) {
    include(":app")
}

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}
