import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.ksp)
    alias(libs.plugins.metro)
}

kotlin {
    androidLibrary {
        namespace = "org.balch.orpheus.core.plugins.resonator"
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
            implementation(libs.kotlinx.serialization.json)
        }
        androidMain.dependencies {
            implementation(libs.jsyn)
        }
        jvmMain.dependencies {
            implementation(libs.jsyn)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
