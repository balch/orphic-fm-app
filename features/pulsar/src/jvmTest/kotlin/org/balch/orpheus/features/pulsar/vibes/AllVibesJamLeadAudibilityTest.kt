package org.balch.orpheus.features.pulsar.vibes

import org.balch.orpheus.features.pulsar.models.SoloMode
import org.balch.orpheus.features.pulsar.models.TrackRole
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Whole-catalog authoring guard for [SoloMode.Jam] sections.
 *
 * A jam passes the lead around the band's handoff matrix, and the engine's eligibility check
 * gates on track ROLE alone: a member owning a `Melodic`-role track is a lead candidate no
 * matter what the section does to its density. So a jam that mutes that track hands the lead
 * to a member with nothing to play — silence, with the rest of the band ducked behind it for
 * the length of that member's turn. The vibe still loads, so only a sweep over every vibe
 * catches the next one.
 */
class AllVibesJamLeadAudibilityTest {

    @Test
    fun everyJamSectionKeepsItsLeadCapableMembersAudible() {
        val providers = VibeCatalogScan.allProviders()

        val offenders = mutableListOf<String>()
        var jamSections = 0

        providers.forEach { provider ->
            val vibe = provider.vibe
            val band = vibe.band ?: return@forEach
            vibe.arrangement?.sections.orEmpty().forEach section@{ section ->
                if (section.soloMode !is SoloMode.Jam) return@section
                jamSections++
                band.members.forEach member@{ member ->
                    if (member.alwaysActive) return@member  // never leads
                    // Mirrors the engine: a jam renders its line into the FIRST Melodic-role
                    // track in the member's declared order. A member with none can't lead.
                    val lineTrack = member.tracks.firstOrNull {
                        vibe.tracks.getOrNull(it)?.role is TrackRole.Melodic
                    } ?: return@member
                    val density = section.trackOverrides?.get(lineTrack)?.density ?: return@member
                    if (density <= 0f) {
                        offenders += "${vibe.name}/${section.name}: '${member.name}' leads on " +
                            "track $lineTrack, muted there"
                    }
                }
            }
        }

        assertTrue(
            jamSections > 0,
            "no shipped vibe has a band and a SoloMode.Jam section, so this sweep passes " +
                "vacuously. If jam solos were retired, delete this test with them.",
        )
        assertTrue(
            offenders.isEmpty(),
            "$offenders — a jam lead whose melodic track is muted plays nothing for its whole " +
                "turn. Give the track a density in that section, or take the member out of the " +
                "band so the handoff matrix can never reach it.",
        )
    }
}
