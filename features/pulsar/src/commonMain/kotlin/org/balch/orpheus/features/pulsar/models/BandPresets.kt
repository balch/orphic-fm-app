package org.balch.orpheus.features.pulsar.models

/**
 * Ready-made [Band] casts, factored out of the bands that already ship.
 *
 * A [Vibe] whose arrangement declares any [SoloMode] MUST also declare a [Vibe.band] — the engine
 * only starts a section solo when one exists, so a bandless solo section silently plays as an
 * ordinary section ([Vibe]'s init enforces this). These presets exist so getting a working jam
 * never requires inventing two 4x4 probability matrices by hand.
 *
 * Pick the preset whose cast matches the vibe, pass it the track indices, done:
 * ```
 * band = BandPresets.quartet(
 *     kit = listOf(0, 1, 2, 7), bass = listOf(3), lead = listOf(4), colour = listOf(5, 6),
 * )
 * ```
 *
 * ## Which tracks can actually lead
 * A JAM solo renders an improvised melodic LINE, so its lead must own at least one track whose
 * ROLE is [TrackRole.Melodic]. A melodic-sounding ENGINE is irrelevant — an organ on a
 * [TrackRole.Chordal] track cannot lead a jam. Give every soloing member at least one melodic
 * track, or the engine falls back to whoever is left and the jam reads as nothing happening.
 *
 * ## The other silent-solo trap
 * `TrackSectionOverride(density = 0f)` is a render MUTE for the whole section, and the solo
 * system does not lift it. Zeroing a would-be soloist's track inside its own solo section makes
 * that solo inaudible whenever that member wins the lead. Thin it (0.1-0.3) instead of muting it.
 *
 * Presets are data only. A vibe that wants bespoke member names or hand-tuned weights should
 * build [Band] directly — [bandMatrix] and [row] are the helpers for that.
 */
object BandPresets {

    /** Widest track index a member may own; [Vibe] is always exactly 8 tracks. */
    private const val MAX_TRACK_INDEX = 7

    /**
     * The workhorse four-piece: an always-active kit plus bass, lead and one colour voice.
     * Modelled on `DogHouseVibe` / `FilterFunkVibe` / `CosmicTechnoVibe`, whose matrices are
     * near-identical. Handoff leans toward the lead and the lead trades mostly with the bass;
     * `colour` (pad / FX / texture) takes the lead least often.
     *
     * Expects the standard 8-track layout — kit on 0-2, bass on 3, and the melodic/chordal
     * voices above — but any grouping works as long as `bass` and `lead` are melodic.
     *
     * @param kit Percussion tracks. Never ducks and never takes the lead.
     * @param bass The bass voice. Melodic, so it can take a jam.
     * @param lead The star voice — highest handoff weight. Must be melodic to lead a jam.
     * @param colour Pads / comping / FX. Melodic-role tracks here can lead too; chordal ones
     *   still take LongFill and LickBuilder leads, which do not need a melodic line.
     */
    fun quartet(
        kit: List<Int>,
        bass: List<Int>,
        lead: List<Int>,
        colour: List<Int>,
    ): Band {
        validate("quartet", kit, bass, lead, colour)
        return Band(
            members = listOf(
                BandMember("Drummer", kit, alwaysActive = true, loudness = 0.7f, creativity = 0.30f),
                BandMember("Bassist", bass, loudness = 0.8f, creativity = 0.50f),
                BandMember("Lead", lead, loudness = 0.65f, creativity = 0.60f),
                BandMember("Colour", colour, loudness = 0.4f, creativity = 0.70f),
            ),
            // The DRUM column is inert — select_next_lead zeroes always-active members — but the
            // weights are written out anyway so adding a non-drum member here reads correctly.
            handoffMatrix = bandMatrix(
                //            DRUM   BASS   LEAD   COLOUR
                "Drummer" to row(0.00f, 0.30f, 0.45f, 0.10f),
                "Bassist" to row(0.20f, 0.00f, 0.50f, 0.15f),
                "Lead" to row(0.15f, 0.40f, 0.00f, 0.25f),
                "Colour" to row(0.10f, 0.30f, 0.45f, 0.00f),
            ),
            pullInMatrix = bandMatrix(
                //            DRUM   BASS   LEAD   COLOUR
                "Drummer" to row(0.00f, 0.30f, 0.25f, 0.10f),
                "Bassist" to row(0.25f, 0.00f, 0.40f, 0.15f),
                "Lead" to row(0.20f, 0.40f, 0.00f, 0.20f),
                "Colour" to row(0.10f, 0.25f, 0.30f, 0.00f),
            ),
            pullInBarsMin = 2, pullInBarsMax = 4,
            barsPerLeadMin = 4, barsPerLeadMax = 8,
        )
    }

    /**
     * Four-piece whose two front-line voices trade the solo back and forth — the shape behind
     * `SwampSwaggerVibe` (slide/keys), `VelvetLeashVibe` (marimba/keys) and `SpaceDroneVibe`
     * (vibraphone/textures, the keys-led ensemble). The kit anchors, the bass is the third
     * option, and `leadA`/`leadB` hand off to each other far more than to anyone else.
     *
     * Leads turn over faster here than in [quartet] (2-6 bars), which is what makes it read as
     * a conversation rather than one long feature.
     *
     * @param kit Percussion tracks. Never ducks and never takes the lead.
     * @param bass The bass voice.
     * @param leadA First front-line voice. Melodic role required to take a jam.
     * @param leadB Second front-line voice, the one A trades with. Same requirement.
     */
    fun tradingLeads(
        kit: List<Int>,
        bass: List<Int>,
        leadA: List<Int>,
        leadB: List<Int>,
    ): Band {
        validate("tradingLeads", kit, bass, leadA, leadB)
        return Band(
            members = listOf(
                BandMember("Drummer", kit, alwaysActive = true, loudness = 0.7f, creativity = 0.25f),
                BandMember("Bassist", bass, loudness = 0.75f, creativity = 0.40f),
                BandMember("Lead A", leadA, loudness = 0.7f, creativity = 0.55f),
                BandMember("Lead B", leadB, loudness = 0.6f, creativity = 0.55f),
            ),
            handoffMatrix = bandMatrix(
                //            DRUM   BASS   A      B
                "Drummer" to row(0.00f, 0.25f, 0.40f, 0.35f),
                "Bassist" to row(0.10f, 0.00f, 0.45f, 0.45f),
                "Lead A" to row(0.10f, 0.30f, 0.00f, 0.60f),
                "Lead B" to row(0.10f, 0.30f, 0.60f, 0.00f),
            ),
            pullInMatrix = bandMatrix(
                //            DRUM   BASS   A      B
                "Drummer" to row(0.00f, 0.40f, 0.30f, 0.25f),
                "Bassist" to row(0.30f, 0.00f, 0.45f, 0.40f),
                "Lead A" to row(0.20f, 0.40f, 0.00f, 0.40f),
                "Lead B" to row(0.20f, 0.40f, 0.40f, 0.00f),
            ),
            pullInBarsMin = 2, pullInBarsMax = 4,
            barsPerLeadMin = 2, barsPerLeadMax = 6,
        )
    }

    /**
     * The sparse end of the catalog: two voices passing a slow line over a standing bed.
     * For ambient / drone vibes with no real rhythm section.
     *
     * Three members, not two, on purpose. A two-member band deadlocks: the only handoff target
     * is the always-active bed, which the engine refuses as a lead until it runs out of options
     * and hands it the solo anyway. Two non-bed voices give the handoff somewhere to go.
     * `bed` also has to hold every track that is not `voiceA` or `voiceB` — a track in NO member
     * gets the full support duck whenever a solo runs, which is not what a bed is for.
     *
     * @param bed Drums, drones, anything that should keep sounding underneath. Never leads.
     * @param voiceA First soloing voice. Melodic role required to take a jam.
     * @param voiceB Second soloing voice. Same requirement.
     */
    fun twoVoiceTexture(
        bed: List<Int>,
        voiceA: List<Int>,
        voiceB: List<Int>,
    ): Band {
        validate("twoVoiceTexture", bed, voiceA, voiceB)
        return Band(
            members = listOf(
                BandMember("Bed", bed, alwaysActive = true, loudness = 0.5f, creativity = 0.20f),
                BandMember("Voice A", voiceA, loudness = 0.55f, creativity = 0.60f),
                BandMember("Voice B", voiceB, loudness = 0.5f, creativity = 0.65f),
            ),
            handoffMatrix = bandMatrix(
                //          BED    A      B
                "Bed" to row(0.00f, 0.50f, 0.50f),
                "Voice A" to row(0.05f, 0.00f, 0.85f),
                "Voice B" to row(0.05f, 0.85f, 0.00f),
            ),
            pullInMatrix = bandMatrix(
                //          BED    A      B
                "Bed" to row(0.00f, 0.35f, 0.35f),
                "Voice A" to row(0.15f, 0.00f, 0.45f),
                "Voice B" to row(0.15f, 0.45f, 0.00f),
            ),
            pullInBarsMin = 2, pullInBarsMax = 6,
            barsPerLeadMin = 4, barsPerLeadMax = 8,
        )
    }

    /**
     * Shared shape check for every preset. Each group must be non-empty (an empty member can
     * never lead and never ducks anything) and no track may appear twice: the engine's
     * track-to-member lookup keeps only the LAST member that claims a track, so a duplicate
     * silently drops one member's ducking.
     */
    private fun validate(preset: String, vararg groups: List<Int>) {
        groups.forEachIndexed { i, tracks ->
            require(tracks.isNotEmpty()) {
                "BandPresets.$preset: member $i has no tracks — every member needs at least one"
            }
            require(tracks.all { it in 0..MAX_TRACK_INDEX }) {
                "BandPresets.$preset: member $i has track indices $tracks outside 0..$MAX_TRACK_INDEX"
            }
        }
        val all = groups.flatMap { it }
        val duplicated = all.groupBy { it }.filterValues { it.size > 1 }.keys.sorted()
        require(duplicated.isEmpty()) {
            "BandPresets.$preset: track(s) $duplicated appear in more than one member — the " +
                "engine keeps only the last member that claims a track"
        }
    }
}
