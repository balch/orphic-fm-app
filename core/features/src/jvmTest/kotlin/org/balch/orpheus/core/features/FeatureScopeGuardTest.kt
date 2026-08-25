package org.balch.orpheus.core.features

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every feature ViewModel must be `@SingleIn(FeatureScope::class)`.
 *
 * [FeatureCollection] holds no cache, so single-instance comes entirely from the scope annotation.
 * Drop it and the binding is still legal Metro -- it just constructs a fresh ViewModel per resolve,
 * silently.
 *
 * That is expensive: ViewModels register with `AppScope` singletons in `init` (MediaSessionManager's
 * `onPlay` slots, say), so a second instance overwrites the first's handlers and the lock screen
 * drives a ViewModel the UI never observes. Same bug `FeatureGraphHolder` prevents, one level down.
 *
 * Sibling guard: `FeatureProviderScopeGuardTest` enforces the opposite rule one layer up.
 */
class FeatureScopeGuardTest {

    private companion object {
        /**
         * Lower bound on how many feature ViewModels the parser must find. A regex that silently
         * matches nothing would otherwise let this test pass vacuously, which is the failure mode
         * it is least allowed to have. There were 33 when this was written; bump it only alongside
         * a real change.
         */
        const val MIN_FEATURE_VIEWMODELS = 30

        // The key takes a second argument now, and named args may be reordered, so match the
        // interface anywhere inside the call. Requiring `::class\)` silently dropped all six.
        val KEY = Regex("""@SynthFeatureKey\([^)]*?(\w+)::class""")
        val SCOPE = Regex("""@SingleIn\(\s*FeatureScope::class\s*\)""")
    }

    private data class FeatureRegistration(
        val file: File,
        val featureInterface: String,
        val isScoped: Boolean,
    )

    @Test
    fun everyFeatureViewModelIsFeatureScoped() {
        val registrations = scanRegistrations()

        assertTrue(
            registrations.size >= MIN_FEATURE_VIEWMODELS,
            "parsed only ${registrations.size} @SynthFeatureKey registrations, expected at least " +
                "$MIN_FEATURE_VIEWMODELS. The parser has drifted from the source style, so this " +
                "guard is no longer checking anything. Fix the regex before touching this number.",
        )

        val unscoped = registrations.filterNot { it.isScoped }
        if (unscoped.isNotEmpty()) {
            val detail = unscoped.joinToString("\n") { "  ${it.featureInterface}  ${it.file.path}" }
            fail(
                "These feature ViewModels are registered with @SynthFeatureKey but are not " +
                    "@SingleIn(FeatureScope::class):\n" + detail + "\n\n" +
                    "FeatureCollection does not cache. Without the scope, Metro constructs a new " +
                    "ViewModel on every resolve, so the panel, the AI tools and any background " +
                    "service each get their own. Whichever one loses the race still registered " +
                    "itself with the AppScope singletons on the way out -- MediaSessionManager's " +
                    "callback slots are var fields, so the last writer wins and the UI observes a " +
                    "different instance than the notification drives.\n\n" +
                    "Add @SingleIn(FeatureScope::class) to the class. Do not add a cache to " +
                    "FeatureCollection; that is the second memoization layer whose lock used to " +
                    "deadlock startup.",
            )
        }
    }

    /** The feature interface named in the key must be a supertype of the class carrying it. */
    @Test
    fun everyRegistrationKeysItsOwnInterface() {
        val mismatches = mutableListOf<String>()
        for (reg in scanRegistrations()) {
            val text = reg.file.readText()
            // The declaration lists supertypes after the constructor's closing paren.
            val declaresIt = Regex("""[:,]\s*${Regex.escape(reg.featureInterface)}\b""")
                .containsMatchIn(text)
            if (!declaresIt) {
                mismatches += "  ${reg.file.name} keys ${reg.featureInterface} but does not implement it"
            }
        }
        if (mismatches.isNotEmpty()) {
            fail(
                "A @SynthFeatureKey naming an interface the class does not implement compiles " +
                    "cleanly -- both sides only have to satisfy KClass<out SynthFeature<*, *>> -- " +
                    "and throws ClassCastException on first resolve:\n" + mismatches.joinToString("\n"),
            )
        }
    }

    private fun scanRegistrations(): List<FeatureRegistration> {
        val root = repoRoot()
        val roots = listOf(File(root, "features"), File(root, "apps"))
            .filter { it.isDirectory }

        return roots
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" } }
            .filterNot { it.path.contains("/build/") }
            .flatMap { file ->
                val text = file.readText()
                KEY.findAll(text).map { m ->
                    // Annotations sit in one contiguous block above the class declaration; look at
                    // the 400 chars before the key, which comfortably covers it.
                    val window = text.substring(maxOf(0, m.range.first - 400), m.range.first)
                    FeatureRegistration(file, m.groupValues[1], SCOPE.containsMatchIn(window))
                }
            }
            .toList()
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        fail("no settings.gradle.kts above ${File("").absolutePath}; cannot locate the repo root")
    }
}
