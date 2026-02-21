plugins {
    id("orpheus.kmp.compose")
    alias(libs.plugins.metro)
    alias(libs.plugins.ksp)
}

kotlin {
    androidLibrary {
        namespace = "org.balch.orpheus.core.features"
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:foundation"))

            // DI
            api(libs.metro.runtime)
            implementation(libs.metrox.viewmodel)

            // Lifecycle (ViewModel base class for SynthFeatureRegistry)
            implementation(libs.androidx.lifecycle.viewmodel)

            // Coroutines & concurrency
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.atomicfu)
        }
    }
}
