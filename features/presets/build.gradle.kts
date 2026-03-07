import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("orpheus.kmp.compose")
    alias(libs.plugins.ksp)
    alias(libs.plugins.metro)
}

kotlin {
    androidLibrary {
        namespace = "org.balch.orpheus.features.presets"
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
             implementation(project(":features:visualizations")) // For LiquidPreview
             implementation(libs.kotlinx.datetime)
             implementation(project(":core:plugins:beats"))
             implementation(project(":core:plugins:delay"))
             implementation(project(":core:plugins:distortion"))
             implementation(project(":core:plugins:drum"))
             implementation(project(":core:plugins:duolfo"))
        }
        jvmMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

val generateBundledPresets by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Generate BundledPresets.kt from SynthPatch classes in patches/"

    dependsOn("compileKotlinJvm")

    val jvmCompilation = kotlin.jvm().compilations.getByName("main")
    classpath = files(
        jvmCompilation.output.allOutputs,
        jvmCompilation.runtimeDependencyFiles
    )

    mainClass.set("org.balch.orpheus.features.presets.PresetJsonGeneratorKt")

    val patchesDir = file("src/commonMain/kotlin/org/balch/orpheus/features/presets/patches")
    val outputDir = layout.buildDirectory.dir("generated/bundledPresets/kotlin")

    inputs.dir(patchesDir)
    outputs.dir(outputDir)

    args(outputDir.get().asFile.absolutePath, patchesDir.absolutePath)
}

// Wire generated source into wasmJsMain so only WASM builds include it
kotlin.sourceSets.getByName("wasmJsMain") {
    kotlin.srcDir(generateBundledPresets.map { it.outputs.files.singleFile })
}
