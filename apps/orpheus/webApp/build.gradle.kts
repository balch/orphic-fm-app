import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("dev.zacsweers.metro")
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "orpheus.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(projects.apps.orpheus.shared)
            implementation(compose.runtime)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.kmlogging)
            implementation(libs.metrox.viewmodel.compose)
        }
    }
}

// WASM C++ DSP build via Emscripten (relocated from the shared module)
val eurorackDir = File(System.getProperty("user.home"), "Source/eurorack").absolutePath
val emsdkDir = File(System.getProperty("user.home"), "emsdk")

val buildWasmNative = tasks.register<Exec>("buildWasmNative") {
    group = "build"
    description = "Build orpheus_dsp WASM module via Emscripten"

    val wasmDir = rootProject.file("liborpheus_dsp/platform/wasm")
    val emcmake = File(emsdkDir, "upstream/emscripten/emcmake")

    onlyIf { emcmake.exists() }

    workingDir = wasmDir
    commandLine("bash", "-c",
        "${emcmake.absolutePath} cmake -B build -DCMAKE_BUILD_TYPE=Release -DEURORACK_DIR=$eurorackDir && " +
        "cmake --build build --config Release"
    )
}

val copyWasmDsp = tasks.register<Copy>("copyWasmDsp") {
    dependsOn(buildWasmNative)
    from("${rootProject.projectDir}/liborpheus_dsp/platform/wasm/build") {
        include("orpheus_dsp.js", "orpheus_dsp.wasm")
    }
    into(layout.buildDirectory.dir("processedResources/wasmJs/main"))
}

tasks.named("wasmJsProcessResources") {
    dependsOn(copyWasmDsp)
}
