plugins {
    id("orpheus.kmp.compose")
    alias(libs.plugins.metro)
}

kotlin {
    android {
        namespace = "org.balch.orpheus.core.features"
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:foundation"))

            // DI
            api(libs.metro.runtime)
            // api: InjectedViewModelFactory extends MetroViewModelFactory in its public signature,
            // and app graphs inherit ViewModelGraph from here.
            api(libs.metrox.viewmodel)

            // Lifecycle (ViewModel base class for SynthFeatureRegistry)
            api(libs.androidx.lifecycle.viewmodel)

            // Coroutines & concurrency
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.atomicfu)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

// The guard tests scan the repo tree through java.io.File, which Gradle cannot see. Without this
// the task is UP-TO-DATE whenever only *other* modules changed, so the regressions they exist to
// catch never re-run them. An input, not a caching opt-out, so unrelated rebuilds still cache.
tasks.withType<Test>().configureEach {
    inputs.files(
        rootProject.fileTree(rootProject.projectDir) {
            include("core/**/*.kt", "features/**/*.kt", "apps/**/*.kt")
            exclude("**/build/**", "**/bin/**", "**/.claude/**")
        }
    )
        .withPropertyName("startupGuardSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
