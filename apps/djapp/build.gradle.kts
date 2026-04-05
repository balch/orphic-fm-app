import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("orpheus.kmp.compose")
    alias(libs.plugins.ksp)
    alias(libs.plugins.metro)
}

kotlin {
    android {
        namespace = "org.balch.orpheus.djapp"
    }

    listOf(iosArm64(), iosSimulatorArm64(), iosX64()).forEach { target ->
        target.binaries.framework {
            baseName = "DjAppShared"
            isStatic = true
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
        commonMain.dependencies {
            api(project(":core:audio"))
            api(project(":core:dsp-engine"))
            api(project(":core:features"))
            api(project(":core:foundation"))
            api(project(":core:plugins:pulsar"))
            api(project(":core:plugins:dj"))
            api(project(":core:plugins:reverb"))
            api(project(":core:plugins:horn"))
            api(project(":core:plugins:distortion"))
            api(project(":ui:panels"))
            api(project(":ui:theme"))
            api(project(":ui:widgets"))
            api(project(":features:pulsar"))
            api(project(":features:dj"))
            api(project(":features:timer"))
            api(project(":features:reverb"))
            api(project(":features:horn"))
            api(project(":features:distortion"))
            api(project(":features:visualizations"))
            implementation(libs.jetbrains.navigation3.ui)
            implementation(compose.material3AdaptiveNavigationSuite)
            implementation(libs.compose.material.icons)
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.liquid)
            implementation(libs.kmlogging)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.collections.immutable)
            implementation(libs.metrox.viewmodel)
            implementation(libs.metrox.viewmodel.compose)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
            implementation(libs.slf4j.api)
            implementation(libs.logback.classic)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.ui.tooling)
}

compose.desktop {
    application {
        mainClass = "org.balch.orpheus.djapp.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "DjApp"
            packageVersion = "1.0.0"
            macOS { dockName = "DJ App" }
        }

        jvmArgs += listOf("-Dorpheus.engine=cpp")
        val nativePath = System.getProperty("orpheus.native.path", "")
        if (nativePath.isNotEmpty()) {
            jvmArgs += "-Djava.library.path=$nativePath"
        }
    }
}

// Native C++ DSP — reuse same build as Orpheus
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
    val targetDir = layout.projectDirectory.dir("src/jvmMain/resources/native/$osName-$arch")
    workingDir = desktopDir
    commandLine("bash", "-c",
        "cmake -B build -DCMAKE_BUILD_TYPE=Release -DEURORACK_DIR=$eurorackDir && cmake --build build --config Release && " +
        "mkdir -p ${targetDir.asFile.absolutePath} && cp build/$libName ${targetDir.asFile.absolutePath}/$libName"
    )
}

tasks.matching { it.name == "jvmProcessResources" }.configureEach {
    dependsOn(buildDesktopNative)
}
