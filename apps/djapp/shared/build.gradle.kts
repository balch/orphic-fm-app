plugins {
    id("orpheus.kmp.compose")
    alias(libs.plugins.metro)
}

kotlin {
    android {
        namespace = "org.balch.orpheus.djapp"
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "DjAppShared"
            isStatic = true
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.play.review)
            implementation(libs.play.review.ktx)
        }
        commonMain.dependencies {
            api(project(":core:audio"))
            api(project(":core:dsp-engine"))
            api(project(":core:features"))
            api(project(":core:foundation"))
            api(project(":core:plugins:pulsar"))
            api(project(":core:plugins:dj"))
            api(project(":core:plugins:app"))
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
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.ui.tooling)
}

// The iOS app is built via bare xcodegen + xcodebuild (no
// embedAndSignAppleFrameworkForXcode integration), so nothing triggers the
// KMP resource aggregation that integration would run. Hang it off the
// framework link tasks so project.yml's "Copy Compose Resources" phase
// always finds a fresh aggregated-resources tree to bundle. Without it,
// Res.readBytes on device throws MissingResourceException and album art
// silently degrades to null (macOS/Android never hit this — their
// pipelines package resources themselves).
listOf("IosArm64" to "iosArm64", "IosSimulatorArm64" to "iosSimulatorArm64")
    .forEach { (linkSuffix, kmpTarget) ->
        listOf("Debug", "Release").forEach { buildType ->
            tasks.named("link${buildType}Framework$linkSuffix") {
                dependsOn("${kmpTarget}AggregateResources")
            }
        }
    }
