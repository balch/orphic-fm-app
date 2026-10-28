package org.balch.orpheus.djapp.vibepresets

enum class PresetModule(val label: String) {
    MIX("MIX"),
    HORN("HORN"),
    DJ("DJ"),
    PULSAR("PULSAR"),
}

/**
 * Every value a vibe preset can hold. Keys are stored in prefs, so they never change once
 * shipped; entry order is the order change chips are listed in.
 */
enum class PresetField(val key: String, val module: PresetModule, val label: String) {
    MIX_PERC("mix.perc", PresetModule.MIX, "PERC"),
    MIX_BASS("mix.bass", PresetModule.MIX, "BASS"),
    MIX_KEYS("mix.keys", PresetModule.MIX, "KEYS"),
    MIX_FX("mix.fx", PresetModule.MIX, "FX"),
    MIX_DIST("mix.dist", PresetModule.MIX, "DIST"),

    TRACKS_0_LEVEL("tracks.0.level", PresetModule.MIX, "T0 LEVEL"),
    TRACKS_1_LEVEL("tracks.1.level", PresetModule.MIX, "T1 LEVEL"),
    TRACKS_2_LEVEL("tracks.2.level", PresetModule.MIX, "T2 LEVEL"),
    TRACKS_3_LEVEL("tracks.3.level", PresetModule.MIX, "T3 LEVEL"),
    TRACKS_4_LEVEL("tracks.4.level", PresetModule.MIX, "T4 LEVEL"),
    TRACKS_5_LEVEL("tracks.5.level", PresetModule.MIX, "T5 LEVEL"),
    TRACKS_6_LEVEL("tracks.6.level", PresetModule.MIX, "T6 LEVEL"),
    TRACKS_7_LEVEL("tracks.7.level", PresetModule.MIX, "T7 LEVEL"),
    TRACKS_0_MUTE("tracks.0.mute", PresetModule.MIX, "T0 MUTE"),
    TRACKS_1_MUTE("tracks.1.mute", PresetModule.MIX, "T1 MUTE"),
    TRACKS_2_MUTE("tracks.2.mute", PresetModule.MIX, "T2 MUTE"),
    TRACKS_3_MUTE("tracks.3.mute", PresetModule.MIX, "T3 MUTE"),
    TRACKS_4_MUTE("tracks.4.mute", PresetModule.MIX, "T4 MUTE"),
    TRACKS_5_MUTE("tracks.5.mute", PresetModule.MIX, "T5 MUTE"),
    TRACKS_6_MUTE("tracks.6.mute", PresetModule.MIX, "T6 MUTE"),
    TRACKS_7_MUTE("tracks.7.mute", PresetModule.MIX, "T7 MUTE"),
    TRACKS_0_SOLO("tracks.0.solo", PresetModule.MIX, "T0 SOLO"),
    TRACKS_1_SOLO("tracks.1.solo", PresetModule.MIX, "T1 SOLO"),
    TRACKS_2_SOLO("tracks.2.solo", PresetModule.MIX, "T2 SOLO"),
    TRACKS_3_SOLO("tracks.3.solo", PresetModule.MIX, "T3 SOLO"),
    TRACKS_4_SOLO("tracks.4.solo", PresetModule.MIX, "T4 SOLO"),
    TRACKS_5_SOLO("tracks.5.solo", PresetModule.MIX, "T5 SOLO"),
    TRACKS_6_SOLO("tracks.6.solo", PresetModule.MIX, "T6 SOLO"),
    TRACKS_7_SOLO("tracks.7.solo", PresetModule.MIX, "T7 SOLO"),

    HORN_SPEED("horn.speed", PresetModule.HORN, "SPEED"),
    HORN_RATIO("horn.ratio", PresetModule.HORN, "RATIO"),
    HORN_DEPTH("horn.depth", PresetModule.HORN, "DEPTH"),
    HORN_MIX("horn.mix", PresetModule.HORN, "MIX"),
    HORN_BRAKE("horn.brake", PresetModule.HORN, "BRAKE"),

    DJ_WET_A("dj.wetA", PresetModule.DJ, "WET A"),
    DJ_WET_B("dj.wetB", PresetModule.DJ, "WET B"),
    DJ_SOURCE_A("dj.sourceA", PresetModule.DJ, "SOURCE A"),
    DJ_SOURCE_B("dj.sourceB", PresetModule.DJ, "SOURCE B"),
    DJ_CROSSFADER("dj.crossfader", PresetModule.DJ, "XFADE"),
    DJ_DELAY_SEND("dj.delaySend", PresetModule.DJ, "DELAY"),
    DJ_REVERB_SEND("dj.reverbSend", PresetModule.DJ, "REVERB"),

    PULSAR_BPM("pulsar.bpm", PresetModule.PULSAR, "BPM"),
    PULSAR_ENERGY("pulsar.energy", PresetModule.PULSAR, "ENERGY"),
    PULSAR_COMPLEXITY("pulsar.complexity", PresetModule.PULSAR, "COMPLEXITY"),
    PULSAR_MOOD("pulsar.mood", PresetModule.PULSAR, "MOOD"),
    PULSAR_SPACE("pulsar.space", PresetModule.PULSAR, "SPACE"),
    PULSAR_DEEP("pulsar.deep", PresetModule.PULSAR, "DEEP"),
    PULSAR_ENVELOPE("pulsar.envelope", PresetModule.PULSAR, "ENV"),
    PULSAR_ROOT("pulsar.root", PresetModule.PULSAR, "ROOT"),
    PULSAR_SCALE("pulsar.scale", PresetModule.PULSAR, "SCALE"),
    ;

    companion object {
        private val byKey = entries.associateBy { it.key }

        fun byKey(key: String): PresetField? = byKey[key]
    }
}
