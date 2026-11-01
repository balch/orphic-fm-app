package org.balch.orpheus.features.cowbell

import org.balch.orpheus.plugins.cowbell.CowbellMode

/** What a person reads for a mode. SYNTH reads "Hold": touch and hold to sustain. Keep in step with the MODE port options. */
val CowbellMode.label: String
    get() = when (this) {
        CowbellMode.TAP -> "Tap"
        CowbellMode.SHAKE -> "Shake"
        CowbellMode.SYNTH -> "Hold"
    }
