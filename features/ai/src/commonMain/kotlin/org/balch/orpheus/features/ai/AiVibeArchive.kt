package org.balch.orpheus.features.ai

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/**
 * Persists AI-created Pulsar vibes to local storage the instant the agent applies one, so a vibe is
 * never lost to the song auto-advancing (or the app closing) before it can be turned into a catalog
 * preset. Best-effort: an archive failure must never throw or block a vibe apply.
 *
 * The default [NoOpAiVibeArchive] keeps every graph buildable (iOS/WASM, and the Orpheus app);
 * platforms that can write files — the DJ app on Android + JVM desktop — contribute a real
 * implementation that `replaces` the no-op.
 */
interface AiVibeArchive {
    /** Write [vibeJson] (the full applied vibe JSON) to local storage, tagged by [vibeName]. */
    suspend fun archive(vibeName: String, vibeJson: String)
}

/** Default no-op so platforms without a file writer (iOS/WASM/Orpheus) still build. */
@ContributesBinding(AppScope::class)
@Inject
class NoOpAiVibeArchive : AiVibeArchive {
    override suspend fun archive(vibeName: String, vibeJson: String) = Unit
}

/**
 * Sortable, filesystem-safe filename for one archived vibe: `<millis>_<sanitized-name>.json`.
 * Shared by the platform writers so archives from any platform sort chronologically and never
 * collide on name alone. [timestampMillis] is passed in because commonMain has no wall clock.
 */
fun aiVibeFileName(vibeName: String, timestampMillis: Long): String {
    val safe = vibeName.trim()
        .replace(Regex("[^A-Za-z0-9]+"), "_")
        .trim('_')
        .take(48)
        .ifEmpty { "vibe" }
    return "${timestampMillis}_$safe.json"
}
