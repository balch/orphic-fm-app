package org.balch.orpheus.features.pulsar.playback

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Deliberately null, not an unfinished port.
 *
 * WASM is a skiko target, so the `Image.encodeToData(PNG)` body the JVM and iOS actuals share
 * compiles here unchanged. It just has no consumer: the browser `MediaSessionManager` builds its
 * `MediaMetadata` from title and artist only and never reads artwork, so encoding would burn a
 * full PNG pass per vibe change and throw the bytes away.
 *
 * Returning null here is what keeps that cost off the web build. If the WASM media session ever
 * grows an `artwork` entry, delete this file and fold the target into a shared skiko source set
 * with JVM and iOS, which are byte-identical to each other today.
 */
actual fun ImageBitmap.toPngBytes(): ByteArray? = null
