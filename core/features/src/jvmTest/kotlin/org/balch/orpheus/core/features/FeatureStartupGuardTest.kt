package org.balch.orpheus.core.features

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A feature ViewModel that restores engine ports must be built at startup.
 *
 * The multibinding is provider-valued, so registering a feature does not construct it. A feature
 * whose `init {}` calls `persistence.bind(onRestore = ...)` sits inert until something asks for it
 * -- which Orpheus does by accident (`keyActions` drains `allFeatures`) and the DJ app never does.
 * The symptom is a saved setting that silently fails to apply until its tab is visited.
 *
 * Membership is declared once, by `startup = true` on the `@SynthFeatureKey` the class already
 * carries, so there is no half-done state to catch. All this guard does is nag about a feature that
 * looks like it *should* be flagged, inferred from `onRestore =`.
 *
 * KNOWN LIMIT: that infers port-restorers, not callback-registrars. `PulsarViewModel` registers
 * MediaSession callbacks and has no `onRestore`, so nothing here demands its flag; losing it is
 * caught by lock-screen transport breaking, not by a test.
 */
class FeatureStartupGuardTest {

    private companion object {
        /** Floor so a drifted regex cannot pass vacuously. 6 when written; bump deliberately. */
        const val MIN_RESTORING_FEATURES = 5

        // [^)]*? so named args in either order still match: (startup = true, value = X::class).
        val KEY = Regex("""@SynthFeatureKey\([^)]*?(\w+)::class""")

        /** Named argument only -- positional `true` would be unreadable anyway. */
        val STARTUP = Regex("""@SynthFeatureKey\([^)]*\bstartup\s*=\s*true""")

        val RESTORES = Regex("""onRestore\s*=""")

        /**
         * Distortion writes DRIVE and MIX, the same ports MixerViewModel's DIST fader owns and
         * restores from a different key. Building both at startup is last-writer-wins, and the
         * UiState defaults are 0.0f, so a saved DIST value can be silently zeroed.
         */
        val DELIBERATELY_LAZY_RESTORERS = setOf("DistortionFeature.kt")
    }

    @Test
    fun everyPortRestoringFeatureIsStartup() {
        val restoring = featureFiles().filter { RESTORES.containsMatchIn(it.code) }

        assertTrue(
            restoring.size >= MIN_RESTORING_FEATURES,
            "parsed only ${restoring.size} port-restoring features, expected at least " +
                "$MIN_RESTORING_FEATURES. The parser has drifted from the source style, so this " +
                "guard is no longer checking anything. Fix the regex before touching this number.",
        )

        val unmarked = restoring
            .filterNot { it.name in DELIBERATELY_LAZY_RESTORERS }
            .filterNot { STARTUP.containsMatchIn(it.code) }
        if (unmarked.isNotEmpty()) {
            val detail = unmarked.joinToString("\n") { "  ${it.name}  ${it.path}" }
            fail(
                "These feature ViewModels restore engine ports in init {} but are not built at " +
                    "startup:\n" + detail + "\n\n" +
                    "Nothing forces them into existence. In the DJ app nothing reads allFeatures " +
                    "at all, so the restore never runs and the user's saved value is silently " +
                    "replaced by the C++ default until they happen to open that tab.\n\n" +
                    "Add the flag to the key the class already carries:\n" +
                    "  @SynthFeatureKey(YourFeature::class, startup = true)\n\n" +
                    "If eager would be wrong -- another feature owns the same ports, say -- add the " +
                    "file to DELIBERATELY_LAZY_RESTORERS with the reason instead.",
            )
        }
    }

    /** Files that register a feature ViewModel. */
    private fun featureFiles(): List<SourceScan.Source> =
        SourceScan.sources.filter { KEY.containsMatchIn(it.code) }
}
