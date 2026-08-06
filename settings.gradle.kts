rootProject.name = "Orpheus"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// ── local.properties bootstrap ────────────────────────────────────────────────
// The Android Gradle Plugin needs sdk.dir, and local.properties is gitignored, so it never
// travels to a new worktree or a fresh clone. Its absence breaks EVERY Gradle task, not
// just Android ones — :apps:djapp:androidApp is configured before task selection, so even
// :features:pulsar:jvmTest dies with "SDK location not found". Deriving the path is
// strictly better than making each checkout hand-copy the file.
//
// Only sdk.dir is written. An org.gradle.java.home line used to ride along in this file
// and is inert: Gradle reads that key from gradle.properties, never from local.properties.
val localPropsFile = file("local.properties")
if (!localPropsFile.exists()) {
    val home = System.getProperty("user.home")
    val sdkDir = System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: listOf("$home/Library/Android/sdk", "$home/Android/Sdk")  // macOS, Linux
            .firstOrNull { File(it).isDirectory }
    if (sdkDir != null) {
        localPropsFile.writeText("sdk.dir=$sdkDir\n")
        logger.lifecycle("Generated local.properties with sdk.dir=$sdkDir")
    }
    // No SDK found: fall through deliberately. AGP's own "SDK location not found" error
    // already names both the file and ANDROID_HOME, so inventing a second message here
    // would only add a place for the two to disagree.
}

pluginManagement {
    includeBuild("build-logic/convention")
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":apps:orpheus:androidApp")
include(":apps:orpheus:shared")
include(":apps:orpheus:desktopApp")
include(":apps:orpheus:webApp")
include(":apps:djapp:shared")
include(":apps:djapp:ai")
include(":apps:djapp:desktopApp")
include(":apps:djapp:androidApp")
include(":apps:macrobenchmark")
include(":core:ai")
include(":core:audio")
include(":core:dsp-engine")
include(":core:features")
include(":core:foundation")
include(":core:midi")
include(":core:tidal")
include(":core:tts")
include(":core:plugin-api")
include(":core:gestures")
include(":core:mediapipe")
include(":core:plugins:beats")
include(":core:plugins:delay")
include(":core:plugins:looper")
include(":core:plugins:perstringbender")
include(":core:plugins:distortion")
include(":core:plugins:flux")
include(":core:plugins:resonator")
include(":core:plugins:reverb")
include(":core:plugins:bender")
include(":core:plugins:stereo")
include(":core:plugins:vibrato")
include(":core:plugins:warps")
include(":core:plugins:bass")
include(":core:plugins:dj")
include(":core:plugins:app")
include(":core:plugins:grains")
include(":core:plugins:horn")
include(":core:plugins:drum")
include(":core:plugins:plaits")
include(":core:plugins:duolfo")
include(":core:plugins:voice")
include(":core:plugins:pulsar")
include(":core:plugins:tides")
include(":ui:panels")
include(":ui:theme")
include(":ui:widgets")
include(":features:warps")
include(":features:drum")
include(":features:flux")
include(":features:grains")
include(":features:horn")
include(":features:resonator")
include(":features:reverb")
include(":features:lfo")
include(":features:delay")
include(":features:distortion")
include(":features:looper")
include(":features:beats")
include(":features:draw")
include(":features:evo")
include(":features:tidal")
include(":features:speech")
include(":features:visualizations")
include(":features:ai")
include(":features:ai-orpheus")
include(":features:debug")
include(":features:midi")
include(":features:presets")
include(":features:tweaks")
include(":features:voice")
include(":features:mediapipe")
include(":features:bass")
include(":features:dj")
include(":features:tides")
include(":features:pulsar")
include(":features:timer")
include(":tools:vibe-codegen")
