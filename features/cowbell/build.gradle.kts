plugins {
    id("orpheus.kmp.compose")
    alias(libs.plugins.metro)
}

kotlin {
    android {
        namespace = "org.balch.orpheus.features.cowbell"
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    // Re-asserted explicitly: the dependsOn edge below would otherwise switch the default
    // hierarchy template off, and iosMain would stop existing along with it.
    applyDefaultHierarchyTemplate()

    sourceSets {
        // JVM, WASM and iOS all render through skiko, so the SkSL-based LiquidRenderer has
        // exactly one implementation shared by the three. Named for the capability the members
        // share (RuntimeEffect-backed shaders), mirroring features/visualizations/build.gradle.kts.
        val skikoShaderMain = create("skikoShaderMain") { dependsOn(commonMain.get()) }
        jvmMain.get().dependsOn(skikoShaderMain)
        wasmJsMain.get().dependsOn(skikoShaderMain)
        iosMain.get().dependsOn(skikoShaderMain)

        // JVM desktop and wasm have no motion sensors, so both share one no-op MotionSource.
        val noMotionMain = create("noMotionMain") { dependsOn(commonMain.get()) }
        jvmMain.get().dependsOn(noMotionMain)
        wasmJsMain.get().dependsOn(noMotionMain)

        commonMain.dependencies {
            implementation(projects.core.pluginApi)
            api(projects.core.plugins.cowbell)
            // Back closes the DJ panel's settings view before it leaves the panel.
            implementation(libs.jetbrains.navigationevent.compose)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            // Off-screen scenes for the panel's fit, dispose and gesture tests.
            implementation(libs.compose.ui.test)
            implementation(compose.desktop.currentOs)
            // collectAsStateWithLifecycle needs a Main dispatcher, as the desktop app has.
            implementation(libs.kotlinx.coroutinesSwing)
        }
    }
}
