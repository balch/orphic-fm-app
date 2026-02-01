plugins {
    id("orpheus.kmp.compose")
}

kotlin {
    androidLibrary {
        namespace = "org.balch.orpheus.ui.theme"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    
    jvm()
    
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }
    
}
