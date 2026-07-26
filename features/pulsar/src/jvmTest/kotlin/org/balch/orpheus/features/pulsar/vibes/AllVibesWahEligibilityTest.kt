package org.balch.orpheus.features.pulsar.vibes

import org.balch.orpheus.features.pulsar.anonmalies.WahAnomaly
import org.balch.orpheus.features.pulsar.models.VibeProvider
import org.balch.orpheus.features.pulsar.models.WahEligibility
import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Whole-catalog authoring guard for the wah anomaly.
 *
 * The wah is a per-track insert on eligible LEAD tracks with **no whole-mix fallback**, so a
 * vibe that declares a [WahAnomaly] without a single eligible track is dead config: the
 * anomaly can never fire, and it reads to the player as "my wah never happens". That is only
 * worth catching if the check sees *every* vibe, which is why this discovers providers off the
 * classpath instead of naming them.
 *
 * A hand-maintained list cannot do this job. It enforces nothing about the vibe someone adds
 * tomorrow, which is the only vibe the guard needed to catch, and it fails spuriously when a
 * listed vibe legitimately drops its anomaly. Filenames are no better: `CompLabVibe.kt` declares
 * eight providers and no class named `CompLabVibe`, `GarageBlitzVibe.kt` declares five, and
 * `DailyDriftVibe.kt` declares none of its own name. Compiled class files are the ground truth.
 *
 * Free side effect worth knowing about: this constructs every [VibeProvider], so every
 * `Vibe.init` `require` runs for every shipped vibe. Most vibes are otherwise never
 * force-instantiated by any test.
 */
class AllVibesWahEligibilityTest {

    private companion object {
        const val PACKAGE_PATH = "org/balch/orpheus/features/pulsar/vibes"
        const val PACKAGE_NAME = "org.balch.orpheus.features.pulsar.vibes"

        /**
         * Sanity floor for the classpath scan. Well below the real count (47 providers as of
         * this writing) so ordinary curation never trips it, but high enough that a scan
         * returning nothing or a handful fails loudly instead of passing vacuously. A silently
         * empty sweep is the exact failure mode this test replaced.
         */
        const val MIN_EXPECTED_PROVIDERS = 40
    }

    private fun discoverProviderClasses(): List<Class<*>> {
        val loader = javaClass.classLoader
        val roots = loader.getResources(PACKAGE_PATH).toList()
            .filter { it.protocol == "file" }
            .map { File(it.toURI()) }
            .filter { it.isDirectory }

        assertTrue(
            roots.isNotEmpty(),
            "found no classpath directory for $PACKAGE_PATH, so nothing could be scanned. If " +
                "the build started packaging this module's classes into a jar, teach this test " +
                "to read jar entries. Do NOT let it pass with an empty scan.",
        )

        return roots
            .flatMap { it.listFiles().orEmpty().toList() }
            .filter { it.isFile && it.name.endsWith(".class") && !it.name.contains('$') }
            .map { PACKAGE_NAME + "." + it.name.removeSuffix(".class") }
            .distinct()
            .sorted()
            .mapNotNull { runCatching { Class.forName(it, false, loader) }.getOrNull() }
            .filter { VibeProvider::class.java.isAssignableFrom(it) }
            .filter { !it.isInterface && !Modifier.isAbstract(it.modifiers) }
    }

    @Test
    fun everyVibeDeclaringTheWahHasAnEligibleLead() {
        val classes = discoverProviderClasses()

        // A provider we cannot construct is a provider we cannot check, which would put us back
        // to enforcing nothing about it. Surface it rather than skipping it quietly.
        val unconstructable = classes.filter {
            runCatching { it.getDeclaredConstructor() }.isFailure
        }
        assertTrue(
            unconstructable.isEmpty(),
            "these VibeProviders have no no-arg constructor, so this sweep silently skips " +
                "them: ${unconstructable.map { it.simpleName }}. Give the test a way to build " +
                "them, or it stops covering them.",
        )

        val providers = classes.map {
            it.getDeclaredConstructor().apply { isAccessible = true }.newInstance() as VibeProvider
        }
        assertTrue(
            providers.size >= MIN_EXPECTED_PROVIDERS,
            "classpath scan found only ${providers.size} VibeProviders (expected at least " +
                "$MIN_EXPECTED_PROVIDERS). Discovery is broken, so this guard is not actually " +
                "checking anything.",
        )

        val declaring = providers.filter { p -> p.vibe.anomalies.any { it is WahAnomaly } }
        assertTrue(
            declaring.isNotEmpty(),
            "no shipped vibe declares a WahAnomaly, so this sweep passes vacuously. If the " +
                "anomaly was retired, delete this test with it.",
        )

        val noEligibleLead = declaring
            .filter { WahEligibility.eligibleTracks(it.vibe).isEmpty() }
            .map { it.name }
        assertTrue(
            noEligibleLead.isEmpty(),
            "$noEligibleLead declare a WahAnomaly but have no eligible lead track, so it can " +
                "never fire. A track qualifies only when its role is Melodic, its lickSource is " +
                "LEAD, and it is not track ${WahEligibility.BASS_TRACK_INDEX} (the bass). Give " +
                "the vibe an eligible lead or drop the anomaly.",
        )
    }
}
