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
include(":foundation")
include(":foundation:audio")
include(":foundation:plugins:delay")
include(":foundation:plugins:distortion")
include(":foundation:plugins:resonator")
include(":foundation:plugins:bender")
include(":foundation:plugins:stereo")
include(":foundation:plugins:vibrato")
include(":foundation:plugins:warps")
include(":foundation:plugins:grains")
include(":foundation:plugins:drum")
include(":foundation:plugins:duolfo")
include(":ui:theme")
include(":ui:widgets")
// TODO: Re-enable after moving shared code (SynthEngine, SynthFeature) to core:audio
// include(":features:warps")
// include(":features:drum")
include(":foundation:plugins:flux")
include(":foundation:plugins:looper")
include(":foundation:plugins:perstringbender")