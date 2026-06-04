import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("orpheus.desktop.app")
    id("org.jetbrains.compose.hot-reload")
}

dependencies {
    implementation(projects.apps.orpheus.shared)
    implementation(compose.desktop.currentOs)
    implementation(compose.components.resources)
    implementation(libs.kotlinx.coroutinesSwing)
    implementation(libs.kmlogging)
    implementation(libs.metrox.viewmodel.compose)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
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
                iconFile.set(project.file("src/main/resources/icon.icns"))
                dockName = "Orphic-FM"
            }
            windows {
                iconFile.set(project.file("src/main/resources/icon.ico"))
            }
            linux {
                iconFile.set(project.file("src/main/resources/icon.png"))
            }
        }

        jvmArgs += listOf(
            "-Dorpheus.debug.gc=${System.getProperty("orpheus.debug.gc", "false")}",
            "-Dorpheus.engine=cpp"
        )
        val nativePath = System.getProperty("orpheus.native.path", "")
        if (nativePath.isNotEmpty()) {
            jvmArgs += "-Djava.library.path=$nativePath"
        }
    }
}

// Native C++ DSP build (relocated from the shared module)
val eurorackDir = File(System.getProperty("user.home"), "Source/eurorack").absolutePath

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
    val targetDir = layout.projectDirectory.dir("src/main/resources/native/$osName-$arch")

    workingDir = desktopDir
    commandLine("bash", "-c",
        "cmake -B build -DCMAKE_BUILD_TYPE=Release -DEURORACK_DIR=$eurorackDir && cmake --build build --config Release && " +
        "mkdir -p ${targetDir.asFile.absolutePath} && cp build/$libName ${targetDir.asFile.absolutePath}/$libName"
    )
}

tasks.named("processResources") {
    dependsOn(buildDesktopNative)
}
