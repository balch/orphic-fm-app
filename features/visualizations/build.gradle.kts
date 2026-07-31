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
        // Not `skikoMain`: iOS is a skiko target too, but it stubs MetaballsRenderer out rather
        // than running the SkSL path, so it is deliberately not a member. Named for the capability
        // the members share, not for the renderer they happen to have in common.
        val skikoShaderMain = create("skikoShaderMain") { dependsOn(commonMain.get()) }
        jvmMain.get().dependsOn(skikoShaderMain)
        wasmJsMain.get().dependsOn(skikoShaderMain)

        commonMain.dependencies {
             // Core deps provided by convention
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
