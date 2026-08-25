package org.balch.orpheus.core.features

import kotlin.test.Test
import kotlin.test.fail

/**
 * Pins the `@StartupRoot` inventory rather than inferring it.
 *
 * Inference was tried and measured. The best heuristic -- `@SingleIn(AppScope::class)` with
 * `.launch` in `init` -- gave 9 false positives and 2 false negatives out of 14. The distinguishing
 * question is "does anything else already inject this?", a property of the whole graph and
 * invisible in the file being scanned. **Do not replace these rules with a heuristic.**
 *
 * KNOWN LIMIT: a new singleton that *ought* to be a root is not flagged. What this guarantees is
 * that the inventory cannot shrink silently or drift from its wiring.
 */
class StartupRootGuardTest {

    private companion object {
        /** Editing this list is the deliberate act. */
        val EXPECTED_ROOTS = setOf(
            "PlaybackController",
            "PulsarPlaybackBridge",
            "PulsarSongEnding",
            "PulsarSongAdvancer",
            "AndroidAppLifecycleManager",
            "DjAppLifecycleManager",
            "InAppReviewManager",
        )

        /**
         * Scope and qualifier matched together: `binding<@StartupRoot Any>()` type-checks against
         * `Any`, so a contribution aimed at the wrong scope compiles and resolves nowhere.
         */
        val ANNOTATION = Regex(
            """@ContributesIntoSet\(\s*AppScope::class[\s\S]{0,80}?binding<\s*@StartupRoot\s+Any\s*>"""
        )

        val DECLARATION = Regex("""\b(?:class|object)\s+(\w+)""")

        /** Entry points that used to name roots by hand. Pinned so a new one cannot skip the rule. */
        val ENTRY_POINTS = listOf(
            "apps/orpheus/androidApp/src/main/kotlin/org/balch/orpheus/OrpheusApplication.kt",
            "apps/orpheus/desktopApp/src/main/kotlin/org/balch/orpheus/main.kt",
            "apps/orpheus/shared/src/iosMain/kotlin/org/balch/orpheus/main.ios.kt",
            "apps/orpheus/webApp/src/wasmJsMain/kotlin/org/balch/orpheus/main.wasmJs.kt",
            "apps/djapp/androidApp/src/main/kotlin/org/balch/djapp/DjAppApplication.kt",
            "apps/djapp/desktopApp/src/main/kotlin/org/balch/orpheus/djapp/main.kt",
            "apps/djapp/shared/src/iosMain/kotlin/org/balch/orpheus/djapp/main.ios.kt",
        )

        /** Roots that no longer belong on a graph interface at all. */
        val REMOVED_ACCESSORS = listOf(
            "pulsarPlaybackBridge",
            "pulsarSongEnding",
            "pulsarSongAdvancer",
            "androidAppLifecycleManager",
            "djAppLifecycleManager",
        )

        /** Hoisted: one Regex per accessor, not one per accessor per line per file. */
        val BARE_TOUCH = REMOVED_ACCESSORS.associateWith { accessor ->
            Regex("""^(remember\s*(\([^)]*\))?\s*\{\s*)?graph\.$accessor\s*\}?$""")
        }
    }

    @Test
    fun contributedRootsMatchThePinnedInventory() {
        val found = contributedRoots()
        val missing = EXPECTED_ROOTS - found.keys
        val unexpected = found.keys - EXPECTED_ROOTS

        if (missing.isNotEmpty() || unexpected.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("The @StartupRoot inventory drifted from EXPECTED_ROOTS.")
                    if (missing.isNotEmpty()) {
                        appendLine()
                        appendLine("Declared in the test but NOT found in source: $missing")
                        appendLine(
                            "Its init {} never runs and nothing reports it: media session " +
                                "callbacks go unregistered, song-ending stays silently disabled. " +
                                "Also fires if the contribution names the wrong scope.",
                        )
                    }
                    if (unexpected.isNotEmpty()) {
                        appendLine()
                        appendLine("Found in source but NOT in EXPECTED_ROOTS: $unexpected")
                        appendLine(
                            "If genuine, add it. That edit is the point: it makes adding a " +
                                "root a deliberate, reviewed act.",
                        )
                    }
                },
            )
        }
    }

    /** Class name -> source, for every file carrying the contribution annotation. */
    private fun contributedRoots(): Map<String, SourceScan.Source> =
        SourceScan.sources.mapNotNull { source ->
            val annotation = ANNOTATION.find(source.code) ?: return@mapNotNull null
            // The annotated declaration is the next class/object after the annotation.
            val decl = DECLARATION.find(source.code, annotation.range.last) ?: return@mapNotNull null
            decl.groupValues[1] to source
        }.toMap()

    @Test
    fun noEntryPointTouchesRootsByHand() {
        val handOffenders = mutableListOf<String>()
        val missingHookOffenders = mutableListOf<String>()

        for (path in ENTRY_POINTS) {
            val file = java.io.File(SourceScan.repoRoot, path)
            if (!file.isFile) fail("pinned entry point no longer exists: $path")
            val text = file.readText()
            if (!SourceScan.stripComments(text).contains("startupInitializer.run()")) {
                missingHookOffenders += "  $path"
            }
            text.lines().forEachIndexed { index, line ->
                val trimmed = line.trim()
                for ((accessor, bare) in BARE_TOUCH) {
                    // A bare `graph.x` statement, not a use like `graph.x.state.collect`.
                    if (bare.containsMatchIn(trimmed)) {
                        handOffenders += "  $path:${index + 1}  $trimmed  ($accessor)"
                    }
                }
            }
        }

        if (handOffenders.isNotEmpty()) {
            fail(
                "These entry points still construct startup roots by hand:\n" +
                    handOffenders.joinToString("\n") + "\n\n" +
                    "That is the duplication StartupInitializer replaced -- adding a root would " +
                    "mean remembering all seven files. Replace with:\n" +
                    "  graph.startupInitializer.run()",
            )
        }

        if (missingHookOffenders.isNotEmpty()) {
            fail(
                "These pinned entry points no longer call the startup hook:\n" +
                    missingHookOffenders.joinToString("\n") + "\n\n" +
                    "Without it that platform builds no roots and no startup features, and " +
                    "nothing else catches it -- every other check here is source-level wiring, not " +
                    "whether the wiring is invoked. Add:\n" +
                    "  graph.startupInitializer.run()",
            )
        }
    }
}
