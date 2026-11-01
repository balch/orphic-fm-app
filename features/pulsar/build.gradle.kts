import org.balch.orpheus.buildlogic.VibeSourceTrace
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("orpheus.kmp.compose")
    alias(libs.plugins.metro)
}

// ── C++ header inputs for the mirror-parity guards (PulsarSectionLimitsTest, BenchSummaryLayoutTest) ──
// The guards regex-parse these headers' text for specific constants (kMaxSections,
// kSectionDataFields, kMaxLickPool, HalfLickMode ordinals) — not a Kotlin source
// change, so without this a header-only edit leaves jvmTest falsely up-to-date and
// the guards silently never re-run.
tasks.named<Test>("jvmTest") {
    inputs.files(
        file("../../liborpheus_dsp/src/pulsar_limits.h"),
        file("../../liborpheus_dsp/src/orpheus_engine.h"),
        file("../../liborpheus_dsp/src/orpheus_unit_pulsar.h"),
        file("../../liborpheus_dsp/src/pulsar_score_clock.h"),
        file("../../liborpheus_dsp/src/pulsar_speech.h"),
        file("../../liborpheus_dsp/src/mix_bench.h"),
    )
        .withPropertyName("cppSectionLimitsHeaders")
        .withPathSensitivity(PathSensitivity.NAME_ONLY)
        .optional()
}

// ── MIDI score inspection (score-player tooling) ──────────────────────────────
// MidiScoreImporter lives in jvmTest — javax.sound.midi is JVM-only and free on the
// test classpath, and this tool never ships in the app. `inspectScore` reruns just
// the report test with a caller-supplied MIDI path, mirroring exportPresets above.
//
//   ./gradlew :features:pulsar:inspectScore -Pmidi=/path/to/score.mid
tasks.register<Test>("inspectScore") {
    description = "Print a per-part summary of a MIDI file for score mapping"
    group = "verification"
    testClassesDirs = tasks.named<Test>("jvmTest").get().testClassesDirs
    classpath = tasks.named<Test>("jvmTest").get().classpath
    filter { includeTestsMatching("*InspectScoreReportTest*") }
    systemProperty("orpheus.score.midi", providers.gradleProperty("midi").getOrElse(""))
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }
}

// ── Generated JSON score assets (score-player tooling) ────────────────────────
// `composeResources/files/scores/*.json` is generated from `scores/source/` (a MIDI
// file plus a same-named ScoreMapping JSON) and checked in for the app to load at
// runtime via NotatedScoreProvider — the raw MIDI itself never ships. A plain `jvmTest`
// only verifies the generated JSON, mirroring exportPresets in
// features/presets/build.gradle.kts. Both scores/source/ and the generated JSON live
// outside any tracked Kotlin source set, so both are declared as jvmTest inputs —
// otherwise a hand-edit to either could leave the guard falsely up-to-date.
//
//   ./gradlew :features:pulsar:importScore
//   ./gradlew jvmTest -Pexport-fixtures
val scoreWriteProperty = "orpheus.score.write"
val scoreSourceFiles = fileTree("../../scores/source")
val scoreFixtures = fileTree("src/commonMain/composeResources/files/scores") { include("*.json") }
val exportScoreFixtures = providers.gradleProperty("export-fixtures").isPresent

tasks.register<Test>("importScore") {
    description = "Regenerate checked-in notated-score JSON assets from scores/source/"
    group = "verification"
    testClassesDirs = tasks.named<Test>("jvmTest").get().testClassesDirs
    classpath = tasks.named<Test>("jvmTest").get().classpath
    filter { includeTestsMatching("*ImportScoreFixtureTest*") }
    systemProperty(scoreWriteProperty, "true")
    outputs.upToDateWhen { false }
}

tasks.named<Test>("jvmTest") {
    if (exportScoreFixtures) {
        systemProperty(scoreWriteProperty, "true")
        outputs.upToDateWhen { false }
    } else {
        // Re-run verification when scores/source/ or a checked-in asset is edited by
        // hand, otherwise Gradle's up-to-date check would skip the guard and let
        // drift through.
        inputs.files(scoreSourceFiles, scoreFixtures)
            .withPropertyName("notatedScoreFixtures")
            .withPathSensitivity(PathSensitivity.NAME_ONLY)
            .optional()
    }
}

kotlin {
    android {
        namespace = "org.balch.orpheus.features.pulsar"
    }

    @OptIn(ExperimentalWasmDsl::class)
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
            implementation(projects.core.audio)
            implementation(projects.core.pluginApi)
            implementation(projects.core.tts)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.collections.immutable)
        }
        // kotlin("test") comes from the orpheus.kmp.compose convention plugin.
        // coroutines-test must live here, not jvmTest: commonTest is compiled by
        // every target (wasmJs, iOS, androidHostTest), and deps only flow downward.
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            // Skia natives, so draw code can be rendered and inspected.
            implementation(compose.desktop.currentOs)
            implementation(libs.compose.ui.test)
            // VibePortDumpRenderHarness replays the plugin's port defaults, as the app's engine start does.
            implementation(projects.core.plugins.pulsar)
        }
    }
}

// ── Vibe source cuts: OG release and EXCLUSIVE ────────────────────────────────
// A removable file declares only vibes its build's cut leaves out (VibeSourceTrace in build-logic):
// -Porphic.liveVibesOnly=true (OG release) keeps only LIVE, and EXCLUSIVE vibes need
// -Porphic.exclusiveVibes=true on every build. Removable files leave every source set, and their
// Metro contributions with them. verifyVibeSourceTrace (Guard 1) keeps each cut compilable.
//
//   ./gradlew :features:pulsar:printRemovableVibes
//   ./gradlew :features:pulsar:verifyVibeSourceTrace
val vibeCut = VibeSourceTrace.Cut(
    liveOnly = providers.gradleProperty("orphic.liveVibesOnly").orNull == "true",
    exclusive = providers.gradleProperty("orphic.exclusiveVibes").orNull == "true",
)

object VibeTraceFiles {
    fun trace(
        catalog: File,
        sources: Iterable<File>,
        cut: VibeSourceTrace.Cut,
    ): Pair<Map<String, String>, VibeSourceTrace.Result> {
        val statuses = VibeSourceTrace.catalogStatuses(catalog.readText())
        val files = sources.filter { it.isFile }.associate { it.absolutePath to it.readText() }
        return statuses to VibeSourceTrace.removable(files, statuses, cut)
    }
}

abstract class RemovableVibeSources : ValueSource<Set<String>, RemovableVibeSources.Params> {
    interface Params : ValueSourceParameters {
        val catalog: RegularFileProperty
        val sources: ConfigurableFileCollection
        val liveOnly: Property<Boolean>
        val exclusive: Property<Boolean>
    }

    // Runs on every build, so a catalog flip or a new vibe file invalidates the configuration cache.
    override fun obtain(): Set<String> {
        val cut = VibeSourceTrace.Cut(parameters.liveOnly.get(), parameters.exclusive.get())
        return VibeTraceFiles.trace(parameters.catalog.get().asFile, parameters.sources, cut).second.removable
    }
}

abstract class VibeTraceTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val catalog: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val pulsarSources: ConfigurableFileCollection

    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    protected fun relative(path: String) = File(path).relativeTo(rootDirectory.get().asFile).path
}

@DisableCachingByDefault(because = "Cheap text scan")
abstract class VerifyVibeSourceTrace : VibeTraceTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val repoMainSources: ConfigurableFileCollection

    @get:OutputFile
    abstract val stamp: RegularFileProperty

    @TaskAction
    fun verify() {
        val (statuses, result) = VibeTraceFiles.trace(catalog.get().asFile, pulsarSources, VibeSourceTrace.Cut.OG_RELEASE)
        val problems = mutableListOf<String>()
        if (statuses.values.none { it == "LIVE" } || result.vibeFiles == 0 || result.removable.isEmpty()) {
            problems += "Implausible trace: ${statuses.size} catalog entries, ${result.vibeFiles} vibe files, " +
                "${result.removable.size} removable"
        }
        val catalogText = catalog.get().asFile.readText()
        val entryCalls = VibeSourceTrace.catalogEntryCalls(catalogText)
        if (entryCalls != statuses.size) {
            problems += "VibeCatalog.kt has $entryCalls CatalogEntry( calls but ${statuses.size} parsed entries:" +
                " write each as `VibeNames.X to CatalogEntry(VibeStatus.Y, ...)`"
        }
        val contributions = pulsarSources.filter { it.isFile }.sumOf { VibeSourceTrace.providerContributions(it.readText()) }
        if (contributions != result.names) {
            problems += "$contributions VibeProvider contributions but ${result.names} traced names: every VibeProvider" +
                " must declare `override val name: VibeName = VibeNames.X`"
        }
        for (cut in VibeSourceTrace.Cut.dropping) {
            val cutResult = VibeTraceFiles.trace(catalog.get().asFile, pulsarSources, cut).second
            val label = "liveOnly=${cut.liveOnly}, exclusive=${cut.exclusive}"
            cutResult.mixed.forEach { (path, hidden) ->
                problems += "[$label] ${relative(path)} mixes kept and dropped vibes (dropped: " +
                    "${hidden.sorted().joinToString()}). Move each status's vibes to their own file"
            }
            val declarations = cutResult.removable.flatMap { VibeSourceTrace.topLevelDeclarations(File(it).readText()) }.toSet()
            repoMainSources.filter { it.isFile && it.absolutePath !in cutResult.removable }.forEach { file ->
                val refs = VibeSourceTrace.referencesTo(declarations, file.readText())
                if (refs.isNotEmpty()) {
                    problems += "[$label] ${relative(file.absolutePath)} references removable ${refs.sorted().joinToString()}"
                }
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException("Vibe source trace failed (Guard 1):\n" + problems.joinToString("\n") { "  - $it" })
        }
        stamp.get().asFile.writeText("${result.removable.size} removable of ${result.vibeFiles} vibe files\n")
    }
}

@DisableCachingByDefault(because = "Prints to the console")
abstract class PrintRemovableVibes : VibeTraceTask() {
    @get:OutputFile
    abstract val removableClasses: RegularFileProperty

    /** -Porphic.exclusiveVibes=true: the OG release keeps EXCLUSIVE vibes, so they aren't listed. */
    @get:Input
    abstract val exclusive: Property<Boolean>

    @TaskAction
    fun print() {
        val cut = VibeSourceTrace.Cut(liveOnly = true, exclusive = exclusive.get())
        val result = VibeTraceFiles.trace(catalog.get().asFile, pulsarSources, cut).second
        val packageLine = Regex("^package\\s+([\\w.]+)", RegexOption.MULTILINE)
        val classLine = Regex(
            "^(?:@\\w+(?:\\([^)]*\\))?\\s+)*(?:(?:public|internal|private|data|sealed|abstract|open|enum|" +
                "annotation|value|inline|expect|actual|fun)\\s+)*(?:class|interface|object)\\s+(\\w+)",
            RegexOption.MULTILINE,
        )
        val classes = result.removable.flatMap { path ->
            val text = File(path).readText()
            val pkg = packageLine.find(text)?.groupValues?.get(1)?.let { "$it." } ?: ""
            // Plus the file facade top-level funs and vals compile into; absent if the file has none.
            classLine.findAll(text).map { pkg + it.groupValues[1] }.toList() + "$pkg${File(path).nameWithoutExtension}Kt"
        }.distinct().sorted()
        removableClasses.get().asFile.writeText(classes.joinToString("\n", postfix = "\n"))
        println("${result.removable.size} removable vibe files (of ${result.vibeFiles}):")
        result.removable.map(::relative).sorted().forEach { println("  $it") }
        println("${classes.size} classes -> ${relative(removableClasses.get().asFile.absolutePath)}")
    }
}

val vibeCatalogFile = layout.projectDirectory.file(
    "src/commonMain/kotlin/org/balch/orpheus/features/pulsar/vibes/VibeCatalog.kt",
)
val pulsarMainSources = fileTree("src") { include("*Main/**/*.kt") }
val repoMainSourceTree = fileTree(rootDir) {
    include("**/src/*Main/**/*.kt", "**/src/main/**/*.kt")
    exclude("**/build/**", ".claude/**", ".git/**", "build-logic/**", "**/.gradle/**")
}

fun VibeTraceTask.traceInputs() {
    catalog.set(vibeCatalogFile)
    pulsarSources.from(pulsarMainSources)
    rootDirectory.set(rootDir)
}

val verifyVibeSourceTrace = tasks.register<VerifyVibeSourceTrace>("verifyVibeSourceTrace") {
    description = "Guard 1: every vibe source cut can be dropped without breaking any main source"
    group = "verification"
    traceInputs()
    repoMainSources.from(repoMainSourceTree)
    stamp.set(layout.buildDirectory.file("vibe-trace/verified.txt"))
}

tasks.register<PrintRemovableVibes>("printRemovableVibes") {
    description = "List vibe sources -Porphic.liveVibesOnly=true drops, and their classes"
    group = "help"
    traceInputs()
    exclusive.set(vibeCut.exclusive)
    removableClasses.set(layout.buildDirectory.file("vibe-trace/removable-classes.txt"))
    outputs.upToDateWhen { false }
}

tasks.named("jvmTest") { dependsOn(verifyVibeSourceTrace) }

if (vibeCut.dropsAnything) {
    val removable = providers.of(RemovableVibeSources::class) {
        parameters {
            catalog.set(vibeCatalogFile)
            sources.from(pulsarMainSources)
            liveOnly.set(vibeCut.liveOnly)
            exclusive.set(vibeCut.exclusive)
        }
    }.get()
    kotlin.sourceSets.configureEach {
        kotlin.exclude { it.file.absolutePath in removable }
    }
    // The source-set filter misses AGP's KMP android compile, so filter every compile task too.
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.AbstractKotlinCompileTool<*>>().configureEach {
        exclude { it.file.absolutePath in removable }
    }
}
