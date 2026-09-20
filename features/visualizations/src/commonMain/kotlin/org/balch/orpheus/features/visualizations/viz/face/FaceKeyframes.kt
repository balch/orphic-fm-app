package org.balch.orpheus.features.visualizations.viz.face

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Keeps only the keyframes near the current stage decoded. Seven 1024px frames would be about
 * 29 MB at once; the pair in use plus the next one is about 12 MB of bitmap pixels, not counting
 * the Skia Images the renderer holds for the bound pair or the bias map it draws alongside them.
 *
 * @param exists true for every index a keyframe is present at, false past the last one. Cheap
 *   and non-decoding, so [count] can probe it without paying for a decode per frame.
 * @param decode decodes an index [exists] already confirmed is present; a null return means
 *   that frame failed to decode, not that it is missing.
 */
class FaceKeyframes(
    private val exists: (Int) -> Boolean,
    private val decode: suspend (Int) -> ImageBitmap?,
) {
    private var count = -1

    // clear() wins: it is the only thing that bumps clearEpoch. prepare() decodes off-lock, then
    // publishes only if clearEpoch has not moved since it started, so a clear() that lands
    // mid-decode always wins instead of being overwritten by a stale prepare(). Two overlapping
    // prepare()s do not bump clearEpoch, so both get to publish: each keeps its own window plus
    // whichever window was most recently requested (latestWindow), so a fast multi-stage jump
    // cannot have its second prepare() silently discard the first one's still-decoding window,
    // while an old window that is neither is still evicted on publish.
    private val lock = SynchronizedObject()
    private var clearEpoch = 0
    private var frames: Map<Int, ImageBitmap> = emptyMap()
    private var latestWindow: IntRange = IntRange.EMPTY

    /** Counts contiguous present indices from 0. Cheap: never decodes. */
    fun count(): Int {
        if (count < 0) {
            var n = 0
            while (n < MAX_KEYFRAMES && exists(n)) n++
            count = n
        }
        return count
    }

    suspend fun prepare(stage: Int) {
        val (keep, startEpoch, startFrames) = synchronized(lock) {
            val window = stage..(stage + 2).coerceAtMost(count - 1)
            latestWindow = window
            Triple(window, clearEpoch, frames)
        }
        val next = HashMap<Int, ImageBitmap>()
        for (i in keep) {
            val already = startFrames[i]
            if (already != null) next[i] = already else decode(i)?.let { next[i] = it }
        }
        synchronized(lock) {
            // A clear() that ran while this one was decoding bumped clearEpoch; publishing here
            // would resurrect frames after the caller tore down.
            if (clearEpoch == startEpoch) {
                val preserved = frames.filterKeys { it in keep || it in latestWindow }
                frames = preserved + next
            }
        }
    }

    operator fun get(index: Int): ImageBitmap? = synchronized(lock) { frames[index] }

    fun pair(stage: Int): Pair<ImageBitmap, ImageBitmap>? = synchronized(lock) {
        val a = frames[stage] ?: return@synchronized null
        val b = frames[stage + 1] ?: return@synchronized null
        a to b
    }

    fun clear() = synchronized(lock) {
        clearEpoch++
        frames = emptyMap()
    }

    private companion object {
        const val MAX_KEYFRAMES = 16
    }
}
