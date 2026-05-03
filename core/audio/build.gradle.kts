plugins {
    id("orpheus.kmp.library")
}

kotlin {
    android {
        namespace = "org.balch.orpheus.core.audio"
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    val iosBuildDir = rootProject.file("liborpheus_dsp/platform/ios")
    // Device target gets device library path; simulator targets get simulator library path
    val deviceTargets = listOf(iosArm64())
    val simTargets = listOf(iosSimulatorArm64())
    (deviceTargets + simTargets).forEach { target ->
        val isDevice = target in deviceTargets
        val libSubdir = if (isDevice) "build-device" else "build-sim"
        target.compilations.getByName("main") {
            cinterops {
                create("orpheus_dsp") {
                    defFile = file("src/nativeInterop/cinterop/orpheus_dsp.def")
                    includeDirs("${rootProject.projectDir}/liborpheus_dsp/include")
                    extraOpts("-libraryPath", "$iosBuildDir/$libSubdir/orpheus_dsp")
                }
            }
        }
    }

    sourceSets {
        iosMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        commonMain.dependencies {
            api(projects.core.pluginApi)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.collections.immutable)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}

// Build native liborpheus_dsp.a for iOS simulator (arm64)
tasks.register<Exec>("buildIosSimNative") {
    group = "build"
    description = "Build liborpheus_dsp.a for iOS simulator (arm64)"

    val iosDir = rootProject.file("liborpheus_dsp/platform/ios")
    val eurorackDir = System.getenv("EURORACK_DIR")
        ?: File(System.getProperty("user.home"), "Source/eurorack").absolutePath

    workingDir = iosDir
    commandLine("bash", "-c",
        "cmake -B build-sim " +
        "-DCMAKE_SYSTEM_NAME=iOS " +
        "-DCMAKE_OSX_SYSROOT=iphonesimulator " +
        "-DCMAKE_OSX_ARCHITECTURES=arm64 " +
        "-DCMAKE_BUILD_TYPE=Release " +
        "-DEURORACK_DIR=$eurorackDir && " +
        "cmake --build build-sim --config Release"
    )
}

// Build native liborpheus_dsp.a for iOS device (arm64)
tasks.register<Exec>("buildIosDeviceNative") {
    group = "build"
    description = "Build liborpheus_dsp.a for iOS device (arm64)"

    val iosDir = rootProject.file("liborpheus_dsp/platform/ios")
    val eurorackDir = System.getenv("EURORACK_DIR")
        ?: File(System.getProperty("user.home"), "Source/eurorack").absolutePath

    workingDir = iosDir
    commandLine("bash", "-c",
        "cmake -B build-device " +
        "-DCMAKE_SYSTEM_NAME=iOS " +
        "-DCMAKE_OSX_SYSROOT=iphoneos " +
        "-DCMAKE_OSX_ARCHITECTURES=arm64 " +
        "-DCMAKE_BUILD_TYPE=Release " +
        "-DEURORACK_DIR=$eurorackDir && " +
        "cmake --build build-device --config Release"
    )
}

// Wire cinterop tasks to depend on the correct iOS native build
tasks.matching { it.name == "cinteropOrpheus_dspIosArm64" }.configureEach {
    dependsOn("buildIosDeviceNative")
}
tasks.matching {
    it.name == "cinteropOrpheus_dspIosSimulatorArm64" || it.name == "cinteropOrpheus_dspIosX64"
}.configureEach {
    dependsOn("buildIosSimNative")
}
