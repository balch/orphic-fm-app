package org.balch.orpheus.core.features

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Authoring guard: a `@Provides` that calls `FeatureCollection.getFeature` must not be scoped.
 *
 * The scope buys no memoization. Every feature ViewModel is `@SingleIn(FeatureScope::class)`
 * (enforced by `FeatureScopeGuardTest`), so the instance behind `getFeature` is already a
 * singleton. What a scope adds is an AppScope `DoubleCheck` whose monitor is held across the
 * whole provider body, and that body is a cross-graph call Metro can neither see nor order.
 * A monitor held across that kind of hidden edge is the shape that used to deadlock startup:
 * a scoped provider's `DoubleCheck` and `FeatureCollection`'s then-extant cache lock, taken in
 * opposite orders by the composing UI thread and a background coroutine, hung an app four
 * launches out of five. The collection's cache and lock are gone, which removed that cycle;
 * this guard keeps anyone from rebuilding half of it.
 *
 * `@SingleIn(AppScope::class)` looks like the obviously-correct annotation on a provider that
 * returns a singleton, which is exactly why this test exists: it is a tempting one-word edit that
 * compiles, passes every module test, and re-arms a class of hang that only shows up when the
 * real app launches. Compilation cannot see it. This can.
 *
 * Source-parsing precedent in this repo: `ExportOdwgTest` in `:core:dsp-engine` reaches out of its
 * module with a relative path. `:core:features` is where this lives because it is the module that
 * owns the invariant, and nothing else can see every app module at once -- `:apps:*:shared`
 * are siblings that never depend on each other.
 */
class FeatureProviderScopeGuardTest {

    private companion object {
        /**
         * The DI modules that must be covered, relative to the repo root. Listed explicitly *and*
         * cross-checked against a glob below: the list catches a file that moved, the glob catches
         * an app added later that nobody thought to list here.
         *
         * The glob is what covers an app this list has not heard of -- it scans every
         * `*Module.kt` under `apps/`, so a new one is picked up the moment it lands. Add it
         * here as well when that happens, so a *move* of the file is caught too.
         */
        val REQUIRED_MODULE_FILES = listOf(
            "apps/orpheus/shared/src/commonMain/kotlin/org/balch/orpheus/di/OrpheusModule.kt",
            "apps/djapp/shared/src/commonMain/kotlin/org/balch/orpheus/djapp/di/DjAppModule.kt",
        )

        /**
         * Minimum number of feature providers each file must yield. A parser that silently matches
         * nothing would otherwise let this test pass vacuously, which is the failure mode it is
         * least allowed to have. Bump these only alongside a real change to the modules.
         */
        val MIN_FEATURE_PROVIDERS = mapOf(
            "OrpheusModule.kt" to 3, // Pulsar, Timer, AiOptions
            "DjAppModule.kt" to 2,   // Pulsar, Timer
        )
    }

    /** One `@Provides fun ...` declaration, with the annotations attached to it. */
    private data class ProviderDecl(
        val file: File,
        val line: Int,
        val name: String,
        val annotations: List<String>,
        val body: String,
    ) {
        val callsGetFeature: Boolean get() = "getFeature" in body
        val isScoped: Boolean get() = annotations.any { it.startsWith("@SingleIn") }
    }

    @Test
    fun providersThatResolveFeaturesAreNotScoped() {
        val root = repoRoot()
        val files = moduleFilesToScan(root)

        val offenders = mutableListOf<ProviderDecl>()
        for (file in files) {
            val decls = parseProviders(file)
            val featureProviders = decls.filter { it.callsGetFeature }

            MIN_FEATURE_PROVIDERS[file.name]?.let { minimum ->
                assertTrue(
                    featureProviders.size >= minimum,
                    "parsed only ${featureProviders.size} @Provides calling getFeature in " +
                        "${file.name}, expected at least $minimum. The parser has drifted from " +
                        "the source style, so this guard is no longer checking anything. Fix the " +
                        "parser in FeatureProviderScopeGuardTest before touching this number.",
                )
            }

            offenders += featureProviders.filter { it.isScoped }
        }

        if (offenders.isNotEmpty()) {
            val detail = offenders.joinToString("\n") { d ->
                "  ${d.file.name}:${d.line}  ${d.name}  ${d.annotations.joinToString(" ")}"
            }
            fail(
                "These @Provides call FeatureCollection.getFeature AND carry a Metro scope:\n" +
                    detail + "\n\n" +
                    "The scope buys no memoization: the feature ViewModel behind getFeature is " +
                    "already @SingleIn(FeatureScope::class), so the instance is a singleton " +
                    "without any help from this layer. What the scope adds is a DoubleCheck " +
                    "monitor held across a cross-graph call, the lock-across-hidden-edge shape " +
                    "that once produced an AB-BA startup deadlock against FeatureCollection's " +
                    "since-removed cache lock. Delete the @SingleIn.",
            )
        }
    }

    /**
     * Path resolution supports the whole guard. If the working directory ever changes, one that
     * reads zero files would pass silently, so every path is asserted rather than assumed.
     */
    @Test
    fun everyRequiredModuleFileWasFound() {
        val root = repoRoot()
        val missing = REQUIRED_MODULE_FILES.filterNot { File(root, it).isFile }
        assertEquals(
            emptyList(), missing,
            "could not read these DI modules under $root. If a module moved, update " +
                "REQUIRED_MODULE_FILES -- do NOT let this guard run against a shorter list.",
        )
    }

    // --- Source scanning ------------------------------------------------------------------------

    /**
     * Walks up from the test's working directory (Gradle sets it to the module dir) to the
     * directory holding `settings.gradle.kts`. Sturdier than a fixed `../..` and it still works
     * from a worktree.
     */
    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        fail("no settings.gradle.kts above ${File("").absolutePath}; cannot locate the repo root")
    }

    /** The three required files plus any other app DI module, so a fourth app is covered on day one. */
    private fun moduleFilesToScan(root: File): List<File> {
        val required = REQUIRED_MODULE_FILES.map { File(root, it) }.filter { it.isFile }
        val discovered = File(root, "apps").listFiles().orEmpty()
            .map { File(it, "shared/src/commonMain/kotlin") }
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.name.endsWith("Module.kt") } }

        val all = (required + discovered).distinctBy { it.canonicalPath }
        assertTrue(all.isNotEmpty(), "found no app DI modules to scan under $root/apps")
        return all
    }

    /**
     * Minimal Kotlin scanner: collects the annotation lines immediately preceding a `fun`, then
     * takes the declaration's body as everything up to the next blank line, annotation, or
     * declaration. That covers the expression-bodied `@Provides fun x(...): T = expr` shape these
     * modules use, including the two-line form where the expression wraps.
     *
     * A full parser would be overkill and a regex over the whole file would miss which annotation
     * belongs to which function. The per-file minimums above are what keep this honest: if the
     * source style outgrows this scanner, the counts drop and the test fails loudly instead of
     * quietly approving everything.
     */
    private fun parseProviders(file: File): List<ProviderDecl> {
        val lines = file.readLines()
        val decls = mutableListOf<ProviderDecl>()
        var pending = mutableListOf<String>()

        var i = 0
        while (i < lines.size) {
            val trimmed = lines[i].trim()
            when {
                trimmed.startsWith("@") -> pending += trimmed
                trimmed.startsWith("fun ") || trimmed.contains(" fun ") -> {
                    if (pending.any { it.startsWith("@Provides") }) {
                        val body = StringBuilder(trimmed)
                        var j = i + 1
                        while (j < lines.size) {
                            val next = lines[j].trim()
                            if (next.isEmpty() || next.startsWith("@") || next.startsWith("}") ||
                                next.startsWith("fun ") || next.startsWith("val ") ||
                                next.startsWith("/*") || next.startsWith("//")
                            ) break
                            body.append('\n').append(next)
                            j++
                        }
                        decls += ProviderDecl(
                            file = file,
                            line = i + 1,
                            name = trimmed.substringAfter("fun ").substringBefore('(').trim(),
                            annotations = pending.toList(),
                            body = body.toString(),
                        )
                        i = j - 1
                    }
                    pending = mutableListOf()
                }
                // Blank lines, comments and KDoc do not break the annotation/declaration pairing;
                // anything else (a `val`, a brace) does.
                trimmed.isEmpty() || trimmed.startsWith("*") || trimmed.startsWith("/") -> Unit
                else -> pending = mutableListOf()
            }
            i++
        }
        return decls
    }
}
