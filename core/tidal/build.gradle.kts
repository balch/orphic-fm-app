plugins {
    id("orpheus.kmp.library")
}

kotlin {
    androidLibrary {
        namespace = "org.balch.orpheus.core.tidal"
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:foundation"))

            // Coroutines (TidalScheduler, TidalRepl)
            implementation(libs.kotlinx.coroutines.core)

            // DateTime (TidalRepl uses Clock)
            implementation(libs.kotlinx.datetime)
        }
    }
}
