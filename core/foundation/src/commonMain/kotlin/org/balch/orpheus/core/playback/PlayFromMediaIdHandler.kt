package org.balch.orpheus.core.playback

/**
 * Optional handler for Android Auto's "play this browseable item" command.
 * Only DJApp wires this (its browseable tree exposes Pulsar vibes).
 */
fun interface PlayFromMediaIdHandler {
    fun onPlay(mediaId: String)
}
