package org.balch.orpheus.features.pulsar.models

/**
 * Mirrors VibeProvider: cheap to construct, with the asset loaded lazily on first use.
 * [score] is `suspend` rather than a `by lazy` val -- unlike a hardcoded [Vibe], the asset
 * comes from a bundled JSON resource, and reading one (`Res.readBytes`) is suspend on every
 * target this app ships (including WASM, where nothing can block a thread to fake sync
 * access). Implementations should still cache after the first successful load.
 */
interface NotatedScoreProvider {
    val name: String
    suspend fun score(): NotatedScore
}
