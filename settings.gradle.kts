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

include(":apps:androidApp")
include(":apps:composeApp")
include(":apps:macrobenchmark")
include(":core:audio")
include(":core:foundation")
include(":core:plugins:delay")
include(":core:plugins:looper")
include(":core:plugins:perstringbender")
include(":core:plugins:distortion")
include(":core:plugins:flux")
include(":core:plugins:resonator")
include(":core:plugins:bender")
include(":core:plugins:stereo")
include(":core:plugins:vibrato")
include(":core:plugins:warps")
include(":core:plugins:grains")
include(":core:plugins:drum")
include(":core:plugins:duolfo")
include(":ui:theme")
include(":ui:widgets")
// TODO: Re-enable after moving shared code (SynthEngine, SynthFeature) to core:audio
include(":features:warps")
include(":features:drum")
