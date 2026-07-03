rootProject.name = "Orpheus"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

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
