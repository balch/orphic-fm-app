plugins {
    id("orpheus.kmp.compose")
    alias(libs.plugins.metro)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    android {
        namespace = "org.balch.orpheus.features.record"
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    // Re-asserted explicitly: the dependsOn edges below would otherwise switch the default
    // hierarchy template off, and iosMain would stop existing along with it.
    applyDefaultHierarchyTemplate()

    sourceSets {
        // java.io code shared by JVM and Android only; wasmJs and iOS never see it.
        val jvmAndroidMain = create("jvmAndroidMain") { dependsOn(commonMain.get()) }
        jvmMain.get().dependsOn(jvmAndroidMain)
        androidMain.get().dependsOn(jvmAndroidMain)

        commonMain.dependencies {
            implementation(projects.core.pluginApi)
            implementation(projects.core.foundation)
            implementation(projects.core.features)
            implementation(projects.core.audio)
            implementation(projects.ui.widgets)
            implementation(projects.ui.theme)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.atomicfu)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
