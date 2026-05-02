package org.balch.orpheus.features.pulsar.playback

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Encode an [ImageBitmap] to PNG bytes for shipping to platform-native
 * media controls. Returns null on platforms where MediaSession artwork
 * isn't supported, so the caller can skip the artwork update.
 *
 * - JVM (macOS): Skia-encoded PNG, fed to MPMediaItemArtwork via JNI.
 * - Android: not used (the foreground service renders its own bitmap
 *   locally for setLargeIcon, no encode/decode round-trip needed) — returns null.
 * - iOS / WASM: not yet wired to a now-playing surface — returns null.
 */
expect fun ImageBitmap.toPngBytes(): ByteArray?
