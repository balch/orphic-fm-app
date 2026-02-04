import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("orpheus.kmp.compose")
    alias(libs.plugins.ksp)
    alias(libs.plugins.metro)
}

kotlin {
    androidLibrary {
        namespace = "org.balch.orpheus.features.presets"
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
             implementation(project(":features:visualizations")) // For LiquidPreview
             implementation(libs.kotlinx.datetime)
             implementation(project(":core:plugins:delay"))
             implementation(project(":core:plugins:duolfo"))
             implementation(project(":core:plugins:distortion"))
        }
    }
}
