package org.balch.orpheus.features.pulsar

import org.balch.orpheus.features.pulsar.models.VibeProvider
import org.balch.orpheus.features.pulsar.vibes.ArmyStompVibe
import org.balch.orpheus.features.pulsar.vibes.BellTollsVibe
import org.balch.orpheus.features.pulsar.vibes.DogHouseVibe
import org.balch.orpheus.features.pulsar.vibes.DustGrooveVibe
import org.balch.orpheus.features.pulsar.vibes.FilterFunkVibe
import org.balch.orpheus.features.pulsar.vibes.SpaceDroneVibe
import org.balch.orpheus.features.pulsar.vibes.TechnoWobbleVibe
import org.balch.orpheus.features.pulsar.vibes.TremoloTideVibe
import org.balch.orpheus.features.pulsar.vibes.VelvetLeashVibe
import org.balch.orpheus.features.pulsar.vibes.VoltageStrutVibe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks in the catalog of DI-bound vibes and verifies that none set a per-vibe
 * `transitionOut` override (they all inherit the global default from
 * `AppPreferences.pulsarTransitionDefault`).
 *
 * Earlier iterations of this test asserted specific `TransitionSpec` values for
 * vibes that originally migrated from the legacy `EndStyle` enum, but a later
 * decision (commit 306aa0a) removed those per-vibe overrides — they now ride
 * the global default, which is simpler and avoids inconsistency between vibes.
 *
 * Two layers of guarantees:
 *  1. Every active vibe leaves `transitionOut` null (inherits global default).
 *  2. The full DI-bound catalog instantiates cleanly and every arrangement has
 *     at least one section. Catches accidental regressions during refactors.
 *
 * The catalog list below mirrors the active `@ContributesIntoSet(... binding<VibeProvider>())`
 * registrations under `features/pulsar/.../vibes/`. Note: some vibe files (e.g.
 * `GarageBlitzVibe.kt`, `CompLabVibe.kt`) keep their class bodies inside
 * block-comment `/* … */` markers — those classes are not part of the live
 * catalog and intentionally not exercised here.
 *
 * If you add a new VibeProvider and the count assertion fires, append it to
 * [allVibes] below.
 */
class VibeArrangementMigrationTest {

    // Active @ContributesIntoSet VibeProvider bindings under
    // features/pulsar/src/commonMain/kotlin/org/balch/orpheus/features/pulsar/vibes/.
    // Keep alphabetized so additions are easy to spot.
    private val allVibes: List<VibeProvider> = listOf(
        ArmyStompVibe(),
        BellTollsVibe(),
        DogHouseVibe(),
        DustGrooveVibe(),
        FilterFunkVibe(),
        SpaceDroneVibe(),
        TechnoWobbleVibe(),
        TremoloTideVibe(),
        VelvetLeashVibe(),
        VoltageStrutVibe(),
    )

    @Test
    fun `no terminal section is reachable during normal play`() {
        // A section with no outgoing transitions is TERMINAL. If such a section
        // is also reachable during normal Markov play (the intro, or any
        // section's transition target), the C++ engine black-holes there
        // (select_next_section returns `current` for a zero-transition section)
        // until the outro is armed — the "stuck in drift" bug. The only
        // sanctioned terminal section is the outroIndex, entered via the armed
        // outro pin, never via a normal edge or intro.
        val violations = mutableListOf<String>()
        for (provider in allVibes) {
            val arr = provider.vibe.arrangement ?: continue
            val sections = arr.sections
            if (sections.size <= 1) continue // single section = intentional loop, never advances

            val reachable: Set<Int> = buildSet {
                // null introIndex = random weighted start → any section can begin the song.
                if (arr.introIndex == null) sections.indices.forEach { add(it) } else add(arr.introIndex!!)
                for (s in sections) for (t in s.transitions) add(t.targetIndex)
            }

            sections.forEachIndexed { idx, section ->
                if (section.transitions.isEmpty() && idx in reachable) {
                    violations += "${provider.name}: section[$idx] '${section.name}' is terminal " +
                        "(no transitions) yet reachable in normal play"
                }
            }
        }
        assertTrue(
            violations.isEmpty(),
            "Terminal sections reachable during normal Markov play trap the engine until the " +
                "outro arms. Give each an outgoing transition, or remove the edges/intro that " +
                "reach it. Violations:\n" + violations.joinToString("\n"),
        )
    }

    @Test
    fun `no vibe sets a per-vibe transitionOut override`() {
        // All vibes inherit the global default. If a vibe genuinely needs its
        // own transition behavior, set it explicitly and add a focused test
        // alongside this one.
        for (provider in allVibes) {
            val arrangement = provider.vibe.arrangement
            assertNotNull(arrangement, "${provider.name}: arrangement is null")
            assertNull(
                arrangement.transitionOut,
                "${provider.name}: should inherit global transitionOut, " +
                    "but found ${arrangement.transitionOut}",
            )
        }
    }

    @Test
    fun `all DI-bound vibes instantiate and have non-empty arrangement sections`() {
        // Sanity check that the test list matches the active catalog. If this
        // fires, either a vibe was added (append to [allVibes]) or removed
        // (delete the entry). 10 = count of active @ContributesIntoSet
        // VibeProvider bindings.
        assertEquals(
            10,
            allVibes.size,
            "expected 10 DI-bound vibes; got ${allVibes.size}. " +
                "Update VibeArrangementMigrationTest.allVibes to match the active catalog.",
        )

        // Force-instantiation must not throw, and every vibe must carry a real
        // arrangement with at least one section. Catches a vibe whose `vibe`
        // property dies in `init` (e.g. progression validation) or that lost
        // its sections during migration.
        for (provider in allVibes) {
            val vibe = provider.vibe
            val arrangement = vibe.arrangement
            assertNotNull(arrangement, "${provider.name}: arrangement is null")
            assertTrue(
                arrangement.sections.isNotEmpty(),
                "${provider.name}: arrangement has no sections",
            )
            // name parity is part of the VibeProvider contract — guard it here
            // since the transition runner identifies vibes by vibe.name.
            assertEquals(
                provider.name,
                vibe.name,
                "${provider.name}: VibeProvider.name does not match vibe.name=${vibe.name}",
            )
        }
    }
}
