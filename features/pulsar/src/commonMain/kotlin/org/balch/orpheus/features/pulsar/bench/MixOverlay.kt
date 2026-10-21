package org.balch.orpheus.features.pulsar.bench

/**
 * Live pan and send edits layered over the playing vibe. Consulted wherever the ViewModel
 * computes pans and sends, so edits survive section changes. Level is not part of it.
 */
interface MixOverlay {
    /** Final pan for track [t] given the vibe's pan. */
    fun pan(t: Int, vibePan: Float): Float

    /** Final reverb send for track [t] in [section] (-1 if none) given the base, before space x deep. */
    fun reverbSend(t: Int, section: Int, base: Float): Float

    /** Final delay send for track [t] in [section] (-1 if none) given the base, before space x deep. */
    fun delaySend(t: Int, section: Int, base: Float): Float
}
