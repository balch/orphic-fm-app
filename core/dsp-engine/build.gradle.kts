plugins {
    id("orpheus.kmp.library")
}

kotlin {
    androidLibrary {
        namespace = "org.balch.orpheus.core.dsp.engine"
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.core.audio)
            api(projects.core.foundation)
            api(projects.core.tts)
            api(projects.core.plugins.beats)
            api(projects.core.plugins.voice)
            api(projects.core.plugins.delay)
            api(projects.core.plugins.distortion)
            api(projects.core.plugins.resonator)
            api(projects.core.plugins.reverb)
            api(projects.core.plugins.bender)
            api(projects.core.plugins.stereo)
            api(projects.core.plugins.vibrato)
            api(projects.core.plugins.warps)
            api(projects.core.plugins.grains)
            api(projects.core.plugins.drum)
            api(projects.core.plugins.plaits)
            api(projects.core.plugins.duolfo)
            api(projects.core.plugins.flux)
            api(projects.core.plugins.looper)
            api(projects.core.plugins.perstringbender)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.collections.immutable)
        }
    }
}

// ── Export production ODWG graph for C++ tests ──────────────────────
// Usage: ./gradlew :core:dsp-engine:exportDefaultGraph
// Output: liborpheus_dsp/test/data/default_graph.odwg
tasks.register<JavaExec>("exportDefaultGraph") {
    description = "Serialize the production ODWG graph descriptor for C++ tests"
    group = "verification"

    dependsOn("jvmMainClasses")

    mainClass.set("org.balch.orpheus.core.audio.dsp.ExportDefaultGraphKt")
    val jvmMain = kotlin.targets.getByName("jvm").compilations.getByName("main")
    classpath = files(jvmMain.output.allOutputs) + (jvmMain.runtimeDependencyFiles ?: files())

    val outputFile = rootProject.file("liborpheus_dsp/test/data/default_graph.odwg")
    args = listOf(outputFile.absolutePath)

    doFirst {
        outputFile.parentFile.mkdirs()
    }
}
