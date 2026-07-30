plugins {
    id("orpheus.kmp.compose")
}

kotlin {
    android {
        namespace = "org.balch.orpheus.ui.widgets"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    
    jvm()
    
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }
    
    // Re-asserted explicitly: the dependsOn edges below would otherwise switch the default
    // hierarchy template off, and iosMain would stop existing along with it.
    applyDefaultHierarchyTemplate()

    sourceSets {
        // JVM, WASM and iOS all render through skiko, so anything written against a skiko-only
        // API has exactly one implementation shared by the three. Named after Compose
        // Multiplatform's own `skikoMain`, which is the source set those APIs ship from.
        val skikoMain by creating { dependsOn(commonMain.get()) }
        jvmMain.get().dependsOn(skikoMain)
        wasmJsMain.get().dependsOn(skikoMain)
        iosMain.get().dependsOn(skikoMain)

        commonMain.dependencies {
            api(project(":ui:theme"))
            api(project(":core:foundation"))

            implementation(libs.compose.material.icons)

            implementation(libs.liquid)
            implementation(libs.compose.ui.tooling.preview)
        }

        androidMain.dependencies {
            // WindowCompat / WindowInsetsControllerCompat for re-hiding system bars on
            // the ModalBottomSheet's own window (ImmersiveSheetEffect).
            implementation(libs.androidx.core.ktx)
        }
    }
}
