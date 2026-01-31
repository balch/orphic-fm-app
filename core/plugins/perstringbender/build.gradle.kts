import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.ksp)
    alias(libs.plugins.metro)
}

kotlin {
    androidLibrary {
        namespace = "org.balch.orpheus.core.plugins.perstringbender"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    
    jvm()
    
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }
    
    sourceSets {
        commonMain.dependencies {
            api(project(":core:audio"))
            implementation(project(":core:plugins:resonator")) // Required for strumming
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
        }
    }
}
