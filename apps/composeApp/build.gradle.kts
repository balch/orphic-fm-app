
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.FileInputStream
import java.util.Properties

// Load local.properties for API keys
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(FileInputStream(localPropertiesFile))
    }
}

plugins {
    id("orpheus.kmp.compose")
    id("org.jetbrains.compose.hot-reload")
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.metro)

}

kotlin {
    // Override namespace for this specific module
    androidLibrary {
        namespace = "org.balch.orpheus.shared"
    }

    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "orpheus.js"
            }
        }
        binaries.executable()
    }

    listOf(iosArm64(), iosSimulatorArm64(), iosX64()).forEach { target ->
        target.binaries.framework {
            baseName = "OrpheusShared"
            isStatic = true
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.androidx.media)
            implementation(libs.androidx.profileinstaller)
        }
        commonMain.dependencies {
            api(project(":core:audio"))
            api(project(":core:dsp-engine"))
            api(project(":core:features"))
            api(project(":core:foundation"))
            api(project(":core:midi"))
            api(project(":core:ai"))
            api(project(":core:tidal"))
            api(project(":core:tts"))
            api(project(":core:plugins:beats"))
            api(project(":core:plugins:voice"))
            api(project(":core:plugins:delay"))
            api(project(":core:plugins:distortion"))
            api(project(":core:plugins:horn"))
            api(project(":core:plugins:resonator"))
            api(project(":core:plugins:reverb"))
            api(project(":core:plugins:bender"))
            api(project(":core:plugins:stereo"))
            api(project(":core:plugins:vibrato"))
            api(project(":core:plugins:warps"))
            api(project(":core:plugins:grains"))
            api(project(":core:plugins:drum"))
            api(project(":core:plugins:plaits"))
            api(project(":core:plugins:duolfo"))
            api(project(":core:plugins:flux"))
            api(project(":core:plugins:looper"))
            api(project(":core:plugins:perstringbender"))
            api(project(":core:plugins:bass"))
            api(project(":core:plugins:dj"))
            api(project(":core:plugins:tides"))
            api(project(":ui:panels"))
            api(project(":ui:theme"))
            api(project(":ui:widgets"))
            api(project(":features:warps"))
            api(project(":features:drum"))
            api(project(":features:flux"))
            api(project(":features:grains"))
            api(project(":features:horn"))
            api(project(":features:resonator"))
            api(project(":features:reverb"))
            api(project(":features:lfo"))
            api(project(":features:delay"))
            api(project(":features:distortion"))
            api(project(":features:looper"))
            api(project(":features:beats"))
            api(project(":features:draw"))
            api(project(":features:evo"))
            api(project(":features:tidal"))
            api(project(":features:speech"))
            api(project(":features:visualizations"))
            api(project(":features:ai"))
            api(project(":features:debug"))
            api(project(":features:midi"))
            api(project(":features:presets"))
            api(project(":features:tweaks"))
            api(project(":features:voice"))
            api(project(":features:mediapipe"))
            api(project(":features:bass"))
            api(project(":features:dj"))
            api(project(":features:tides"))
            api(project(":core:mediapipe"))
            implementation(libs.compose.material.icons)
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.liquid)
            implementation(libs.kmlogging)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.collections.immutable)
            implementation(libs.ktmidi)
            implementation(libs.metrox.viewmodel.compose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        val jvmTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
            implementation(libs.ktmidi.jvm.desktop)
            implementation(libs.coremidi4j)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.slf4j.api)
            implementation(libs.logback.classic)
        }
        wasmJsMain.dependencies {
            // ktmidi provides WebMidiAccess for browser MIDI
            // Web Audio API used directly via Kotlin/JS interop
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

// For Compose previews with AGP 9.0 and the android KMP library plugin
dependencies {
    androidRuntimeClasspath(libs.compose.ui.tooling)
}

compose.desktop {
    application {
        mainClass = "org.balch.orpheus.MainKt"

        buildTypes.release.proguard {
            configurationFiles.from(project.file("compose-desktop.pro"))
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Orphic-FM"
            packageVersion = "1.0.0"

            macOS {
                iconFile.set(project.file("src/jvmMain/resources/icon.icns"))
                dockName = "Orphic-FM"
            }
            windows {
                iconFile.set(project.file("src/jvmMain/resources/icon.ico"))
            }
            linux {
                iconFile.set(project.file("src/jvmMain/resources/icon.png"))
            }
        }

        // Forward debug flags and engine selection from Gradle to the App
        jvmArgs += listOf(
            "-Dorpheus.debug.gc=${System.getProperty("orpheus.debug.gc", "false")}",
            "-Dorpheus.engine=cpp"
        )
        // When using C++ engine, add native library path
        val nativePath = System.getProperty("orpheus.native.path", "")
        if (nativePath.isNotEmpty()) {
            jvmArgs += "-Djava.library.path=$nativePath"
        }
    }
}

// ── Native C++ DSP builds ───────────────────────────────────────────
// These tasks compile liborpheus_dsp for each platform target.
// They run automatically when the corresponding Kotlin target builds.

val eurorackDir = File(System.getProperty("user.home"), "Source/eurorack").absolutePath
val emsdkDir = File(System.getProperty("user.home"), "emsdk")

// Build native liborpheus_desktop for JVM desktop C++ DSP engine
val buildDesktopNative by tasks.registering(Exec::class) {
    group = "build"
    description = "Build liborpheus_desktop native library for JVM desktop"

    val desktopDir = rootProject.file("liborpheus_dsp/desktop")
    val arch = System.getProperty("os.arch").let {
        if (it == "aarch64" || it == "arm64") "aarch64" else "x86_64"
    }
    val osName = System.getProperty("os.name").lowercase().let {
        when {
            "mac" in it -> "darwin"
            "linux" in it -> "linux"
            else -> "windows"
        }
    }
    val libName = System.mapLibraryName("orpheus_desktop")
    val targetDir = layout.projectDirectory.dir("src/jvmMain/resources/native/$osName-$arch")

    workingDir = desktopDir
    commandLine("bash", "-c",
        "cmake -B build -DCMAKE_BUILD_TYPE=Release -DEURORACK_DIR=$eurorackDir && cmake --build build --config Release && " +
        "mkdir -p ${targetDir.asFile.absolutePath} && cp build/$libName ${targetDir.asFile.absolutePath}/$libName"
    )
}

// Build WASM DSP module via Emscripten
val buildWasmNative by tasks.registering(Exec::class) {
    group = "build"
    description = "Build orpheus_dsp WASM module via Emscripten"

    val wasmDir = rootProject.file("liborpheus_dsp/platform/wasm")
    val emcmake = File(emsdkDir, "upstream/emscripten/emcmake")

    // Only run if Emscripten is installed
    onlyIf { emcmake.exists() }

    workingDir = wasmDir
    commandLine("bash", "-c",
        "${emcmake.absolutePath} cmake -B build -DCMAKE_BUILD_TYPE=Release -DEURORACK_DIR=$eurorackDir && " +
        "cmake --build build --config Release"
    )
}

// Copy Emscripten WASM DSP module to app resources for serving alongside the app.
// The worker script (orpheus-dsp-worker.js) loads orpheus_dsp.js which loads orpheus_dsp.wasm.
val copyWasmDsp by tasks.registering(Copy::class) {
    dependsOn(buildWasmNative)
    from("${rootProject.projectDir}/liborpheus_dsp/platform/wasm/build") {
        include("orpheus_dsp.js", "orpheus_dsp.wasm")
    }
    into(layout.buildDirectory.dir("processedResources/wasmJs/main"))
}

tasks.named("wasmJsProcessResources") {
    dependsOn(copyWasmDsp)
}

// Desktop: ensure native library is built before JVM resources are processed
tasks.matching { it.name == "jvmProcessResources" }.configureEach {
    dependsOn(buildDesktopNative)
}

// BuildKonfig configuration for cross-platform BuildConfig
