plugins {
    id("orpheus.kmp.library")
}

kotlin {
    androidLibrary {
        namespace = "org.balch.orpheus.core.audio"
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.core.pluginApi)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.collections.immutable)
            implementation(libs.kotlinx.datetime)
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.jsyn)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.jsyn)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}
