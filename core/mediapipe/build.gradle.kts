plugins {
    id("orpheus.kmp.library")
}

kotlin {
    android {
        namespace = "org.balch.orpheus.core.mediapipe"

        // publish=true is REQUIRED despite its name: without it a KMP android library
        // exports no consumer rules at all, even to same-build project consumers. See the
        // same block in core/ai.
        @Suppress("UnstableApiUsage")
        optimization {
            consumerKeepRules.publish = true
            consumerKeepRules.files(project.file("consumer-rules.pro"))
        }
    }

    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:gestures"))
            implementation(project(":core:foundation"))
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.mediapipe.tasks.vision)
            implementation(libs.camerax.core)
            implementation(libs.camerax.camera2)
            implementation(libs.camerax.lifecycle)
            implementation(libs.camerax.view)
        }
        jvmMain.dependencies {
            implementation(libs.javacv.platform)
        }
    }
}
