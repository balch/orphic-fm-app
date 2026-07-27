plugins {
    id("orpheus.kmp.compose")
    alias(libs.plugins.metro)
}

kotlin {
    android {
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
            // api: InjectedViewModelFactory extends MetroViewModelFactory in its public signature,
            // and app graphs inherit ViewModelGraph from here.
            api(libs.metrox.viewmodel)

            // Lifecycle (ViewModel base class for SynthFeatureRegistry)
            api(libs.androidx.lifecycle.viewmodel)

            // Coroutines & concurrency
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.atomicfu)
        }
    }
}
