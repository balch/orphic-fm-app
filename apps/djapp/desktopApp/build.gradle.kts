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
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
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

val buildDesktopNative by tasks.registering(Exec::class) {
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
        "cmake -B build -DCMAKE_BUILD_TYPE=Release -DEURORACK_DIR=$eurorackDir && cmake --build build --config Release && " +
        "mkdir -p ${targetDir.asFile.absolutePath} && cp build/$libName ${targetDir.asFile.absolutePath}/$libName"
    )
}

tasks.named("processResources") {
    dependsOn(buildDesktopNative)
}
