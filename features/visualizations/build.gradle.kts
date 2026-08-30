import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("orpheus.kmp.compose")
    alias(libs.plugins.metro)
}

kotlin {
    android {
        namespace = "org.balch.orpheus.features.visualizations"
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    // Re-asserted explicitly: the dependsOn edge below would otherwise switch the default
    // hierarchy template off, and iosMain would stop existing along with it.
    applyDefaultHierarchyTemplate()

    sourceSets {
        // JVM, WASM and iOS all render through skiko, so the SkSL-based MetaballsRenderer has
        // exactly one implementation shared by the three. Named for the capability the members
        // share (RuntimeEffect-backed shaders), not for the renderer they happen to have in
        // common — mirrors the `skikoMain` split in ui/widgets/build.gradle.kts.
        val skikoShaderMain = create("skikoShaderMain") { dependsOn(commonMain.get()) }
        jvmMain.get().dependsOn(skikoShaderMain)
        wasmJsMain.get().dependsOn(skikoShaderMain)
        iosMain.get().dependsOn(skikoShaderMain)

        commonMain.dependencies {
             // Core deps provided by convention
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            // Skia natives for ImageComposeScene, so draw code can be rendered and inspected.
            implementation(compose.desktop.currentOs)
        }
    }
}
