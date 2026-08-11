import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("orpheus.desktop.app")
}

val edition = (findProperty("edition") as String?) ?: "core"
val catalog = (findProperty("catalog") as String?) ?: "live"

dependencies {
    implementation(projects.apps.djapp.shared)
    if (edition == "ai") implementation(project(":apps:djapp:ai"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)
    implementation(libs.kmlogging)
    implementation(libs.metrox.viewmodel)
    implementation(libs.metrox.viewmodel.compose)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
}

compose.desktop {
    application {
        mainClass = "org.balch.orpheus.djapp.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb)
            packageName = "DjApp"
            packageVersion = "1.0.0"
            macOS { dockName = "Orphic DJ" }
        }

        jvmArgs += listOf("-Dorpheus.engine=cpp")
        jvmArgs += "-Dcatalog=$catalog"
        val nativePath = System.getProperty("orpheus.native.path", "")
        if (nativePath.isNotEmpty()) {
            jvmArgs += "-Djava.library.path=$nativePath"
        }
    }
}

// Native C++ DSP build (relocated from the shared module)
val eurorackDir = File(System.getProperty("user.home"), "Source/eurorack").absolutePath

val buildDesktopNative = tasks.register<Exec>("buildDesktopNative") {
    group = "build"
    description = "Build liborpheus_desktop native library"
    val desktopDir = rootProject.file("liborpheus_dsp/desktop")
    val arch = System.getProperty("os.arch").let {
        if (it == "aarch64" || it == "arm64") "aarch64" else "x86_64"
    }
    val osName = System.getProperty("os.name").lowercase().let {
        when { "mac" in it -> "darwin"; "linux" in it -> "linux"; else -> "windows" }
    }
    val libName = System.mapLibraryName("orpheus_desktop")
    val targetDir = layout.projectDirectory.dir("src/main/resources/native/$osName-$arch")
    workingDir = desktopDir
    commandLine("bash", "-c",
        // Own build dir, not just an own flag: all three desktop apps share liborpheus_dsp/desktop,
        // so a cached ORPHEUS_WITH_GRIDS=OFF here would silently strip Grids from Orpheus.
        "cmake -B build-nogrids -DCMAKE_BUILD_TYPE=Release -DORPHEUS_WITH_GRIDS=OFF -DEURORACK_DIR=$eurorackDir && " +
        "cmake --build build-nogrids --config Release && " +
        "mkdir -p ${targetDir.asFile.absolutePath} && cp build-nogrids/$libName ${targetDir.asFile.absolutePath}/$libName"
    )
}

tasks.named("processResources") {
    dependsOn(buildDesktopNative)
}
