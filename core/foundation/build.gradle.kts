plugins {
    id("orpheus.kmp.compose")
    alias(libs.plugins.metro)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
}

kotlin {
    androidLibrary {
        namespace = "org.balch.orpheus.core.foundation"
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":core:audio"))

                api(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.collections.immutable)
                implementation(libs.kotlinx.atomicfu)
                implementation(libs.kotlinx.coroutines.core)

                // UI & Lifecycle (for SynthFeature and ViewModels)
                implementation(libs.androidx.lifecycle.viewmodel)

                // DI
                api(libs.metro.runtime)
                implementation(libs.metrox.viewmodel)
                implementation(libs.metrox.viewmodel.compose)
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.jsyn)
                implementation(libs.slf4j.api)
            }
        }
        val androidMain by getting {
        }
        val wasmJsMain by getting {
        }
    }
}
