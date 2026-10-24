plugins {
    id("orpheus.kmp.compose")
    alias(libs.plugins.metro)
}

kotlin {
    android {
        namespace = "org.balch.orpheus.djapp.ai"
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "DjAppAi"
            isStatic = true
        }
    }

    // Re-asserted explicitly: the dependsOn edges below would otherwise switch the default
    // hierarchy template off, and iosMain would stop existing along with it.
    applyDefaultHierarchyTemplate()

    sourceSets {
        // The MCP server and capture services, shared by desktop and Android; iOS never sees them.
        val jvmAndroidMain = create("jvmAndroidMain") { dependsOn(commonMain.get()) }
        jvmMain.get().dependsOn(jvmAndroidMain)
        androidMain.get().dependsOn(jvmAndroidMain)

        commonMain.dependencies {
            api(project(":apps:djapp:shared"))
            api(project(":features:ai"))
            api(project(":core:ai"))
            implementation(libs.compose.material.icons)
            implementation(libs.jetbrains.navigationevent.compose)
            implementation(libs.kmlogging)
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.metrox.viewmodel)
            implementation(libs.metrox.viewmodel.compose)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        // DjRoute extends NavKey; tests reading route fields need it on the classpath.
        jvmTest.dependencies {
            implementation(libs.jetbrains.navigation3.ui)
        }
        // Only the ai edition carries this module, so the og flavor never gets the server stack.
        jvmAndroidMain.dependencies {
            // The SDK pins ktor 3.5.1 for sse/websockets/content-negotiation; the BOM keeps every ktor module on one version.
            implementation(project.dependencies.platform(libs.ktor.bom))
            implementation(libs.mcp.kotlin.server)
            implementation(libs.ktor.server.cio)
        }
    }
}

// An App Store build must not carry the Kraken pad (see :apps:djapp:shared's showKraken).
if (providers.gradleProperty("showKraken").orNull.toBoolean()) {
    tasks.configureEach {
        if (name.startsWith("linkReleaseFrameworkIos")) {
            doFirst { throw GradleException("-PshowKraken=true is for local builds; drop it to link a release framework.") }
        }
    }
}

// Nor the Pulsar grid's track editing (see :apps:djapp:shared's trackEdit).
if (providers.gradleProperty("trackEdit").orNull.toBoolean()) {
    tasks.configureEach {
        if (name.startsWith("linkReleaseFrameworkIos")) {
            doFirst { throw GradleException("-PtrackEdit=true is for local builds; drop it to link a release framework.") }
        }
    }
}
