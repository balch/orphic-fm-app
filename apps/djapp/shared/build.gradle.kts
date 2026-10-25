import com.codingfeline.buildkonfig.compiler.FieldSpec

plugins {
    id("orpheus.kmp.compose")
    alias(libs.plugins.metro)
    alias(libs.plugins.buildkonfig)
}

// -PshowKraken=true puts the Kraken pad in the UI (DjBuildKonfig.SHOW_KRAKEN). Local builds only:
// the store builds' tasks here, in :apps:djapp:androidApp and in :apps:djapp:ai refuse it.
val showKraken = providers.gradleProperty("showKraken").orNull.toBoolean()
// The Mix tab's TRACKS view ships on (DjBuildKonfig.SHOW_TUNING); -PshowTuning=false is the kill switch.
val showTuning = providers.gradleProperty("showTuning").orNull?.toBoolean() ?: true
// -PtrackEdit=true lets the Pulsar grid select a track and open its detail strip
// (DjBuildKonfig.SHOW_TRACK_EDIT). Local builds only, refused by the same store tasks as showKraken.
val trackEdit = providers.gradleProperty("trackEdit").orNull.toBoolean()

buildkonfig {
    packageName = "org.balch.orpheus.djapp"
    objectName = "DjBuildKonfig"
    defaultConfigs {
        buildConfigField(FieldSpec.Type.BOOLEAN, "SHOW_KRAKEN", showKraken.toString())
        buildConfigField(FieldSpec.Type.BOOLEAN, "SHOW_TUNING", showTuning.toString())
        buildConfigField(FieldSpec.Type.BOOLEAN, "SHOW_TRACK_EDIT", trackEdit.toString())
    }
}

// ── Golden fixture guarding the Kotlin<->Swift widget wire schema ─────────────
// fixtures/snapshot-golden.json lives outside this module (in iosApp/), so without
// an explicit Gradle input, a fixture-only edit leaves jvmTest UP-TO-DATE and
// DjWidgetGoldenFixtureTest silently never reruns. See its KDoc for how to
// regenerate the fixture.
tasks.named<Test>("jvmTest") {
    inputs.files(file("../iosApp/DjWidgetExtension/fixtures/snapshot-golden.json"))
        .withPropertyName("widgetGoldenFixture")
        .withPathSensitivity(PathSensitivity.NAME_ONLY)
        .optional()
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
            implementation(libs.androidx.window)
            implementation(libs.play.review)
            implementation(libs.play.review.ktx)
        }
        commonMain.dependencies {
            api(project(":core:audio"))
            api(project(":core:dsp-engine"))
            api(project(":core:features"))
            api(project(":core:foundation"))
            api(project(":core:tts"))
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
            // Back handling for the stage's own sheets; navigation3 only brings it transitively.
            implementation(libs.jetbrains.navigationevent.compose)
            implementation(libs.compose.material.icons)
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.liquid)
            implementation(libs.reorderable)
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
            implementation(kotlin("test"))
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
                // The App Store archive links the release framework.
                if (buildType == "Release" && showKraken) {
                    doFirst { throw GradleException("-PshowKraken=true is for local builds; drop it to link a release framework.") }
                }
                if (buildType == "Release" && trackEdit) {
                    doFirst { throw GradleException("-PtrackEdit=true is for local builds; drop it to link a release framework.") }
                }
            }
        }
    }

// The OG iOS app links this framework, so its release links ship only LIVE vibes
// (see :features:pulsar's orphic.liveVibesOnly). :apps:djapp:ai refuses the switch.
val liveVibesOnly = providers.gradleProperty("orphic.liveVibesOnly").orNull == "true"
if (!liveVibesOnly) {
    tasks.configureEach {
        if (name.startsWith("linkReleaseFrameworkIos")) {
            doFirst { throw GradleException("OG release framework needs -Porphic.liveVibesOnly=true") }
        }
    }
}

// Reads the linked binary's symbol table (local symbols too: Kotlin classes link as `s`/`t`, e.g.
// `_kclass:<fqcn>`). A removed class hits on its own name or any nested `.`/`$` name under it.
listOf("IosArm64" to "iosArm64", "IosSimulatorArm64" to "iosSimulatorArm64")
    .forEach { (linkSuffix, kmpTarget) ->
        val link = "linkReleaseFramework$linkSuffix"
        val verify = tasks.register("verifyOgFrameworkHasOnlyLiveVibes$linkSuffix") {
            group = "verification"
            description = "Fails if the $kmpTarget OG release framework links a non-LIVE vibe class."

            val binary = layout.buildDirectory.file("bin/$kmpTarget/releaseFramework/DjAppShared.framework/DjAppShared")
            val removableList = rootProject.layout.projectDirectory.file("features/pulsar/build/vibe-trace/removable-classes.txt")
            dependsOn(link, ":features:pulsar:printRemovableVibes")
            inputs.file(binary)
            inputs.file(removableList)

            doLast {
                val liveControl = "org.balch.orpheus.features.pulsar.vibes.DogHouseVibe"
                val removable = removableList.asFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                require(removable.isNotEmpty()) { "Empty removable-classes list at ${removableList.asFile}" }
                val watched = removable + liveControl
                val nm = ProcessBuilder("nm", binary.get().asFile.absolutePath).redirectErrorStream(true).start()
                val hits = mutableMapOf<String, String>()
                val nameToken = Regex("[A-Za-z_][A-Za-z0-9_$.]*")
                nm.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        nameToken.findAll(line).forEach { match ->
                            val token = match.value
                            // The token itself, then each prefix that ends before a `.` or `$`.
                            val owners = sequenceOf(token) +
                                token.indices.asSequence().filter { token[it] == '.' || token[it] == '$' }.map { token.substring(0, it) }
                            owners.firstOrNull { it in watched }?.let { hits.putIfAbsent(it, line.trim()) }
                        }
                    }
                }
                check(nm.waitFor() == 0) { "nm failed on ${binary.get().asFile}" }
                require(liveControl in hits) {
                    "Expected LIVE $liveControl in the $kmpTarget framework symbols; this check would pass against any binary"
                }
                val kept = (hits.keys - liveControl).sorted()
                if (kept.isNotEmpty()) {
                    throw GradleException(
                        "OG $kmpTarget framework links non-LIVE vibe classes (build with -Porphic.liveVibesOnly=true):\n" +
                            kept.joinToString("\n") { "  - $it   e.g. ${hits.getValue(it)}" },
                    )
                }
            }
        }
        // Without the switch the link already throws; a second error from verify would only confuse.
        if (liveVibesOnly) tasks.named(link) { finalizedBy(verify) }
    }

// The jvmTest guard sees only the JVM runtime classpath. This sees every target, and names the
// configuration carrying the GPL module rather than just reporting that a class is present.
tasks.register("checkGplIsolation") {
    group = "verification"
    description = "Fails if :core:plugins:drum-patterns is reachable from this app."

    // Resolution *roots* are captured here as Providers, not the live configuration container:
    // that container is not serializable, so holding it across into doLast breaks the
    // configuration cache.
    val gplModule = ":core:plugins:drum-patterns"
    val roots = configurations
        .matching { it.isCanBeResolved && it.name.endsWith("RuntimeClasspath") }
        .map { it.name to it.incoming.resolutionResult.rootComponent }
        .toList()

    doLast {
        val offenders = roots.filter { (_, rootProvider) ->
            val seen = mutableSetOf<ComponentIdentifier>()
            val queue = ArrayDeque(listOf(rootProvider.get()))
            var found = false
            while (queue.isNotEmpty() && !found) {
                val component = queue.removeFirst()
                if (!seen.add(component.id)) continue
                val id = component.id
                if (id is ProjectComponentIdentifier && id.projectPath == gplModule) {
                    found = true
                } else {
                    component.dependencies
                        .filterIsInstance<ResolvedDependencyResult>()
                        .forEach { queue.add(it.selected) }
                }
            }
            found
        }.map { it.first }

        if (offenders.isNotEmpty()) {
            throw GradleException(
                "$gplModule (GPL-3.0) is reachable from this app via: " +
                    offenders.joinToString() +
                    "\nThis app ships commercially and must not link MI Grids."
            )
        }
    }
}

tasks.named("check") { dependsOn("checkGplIsolation") }
