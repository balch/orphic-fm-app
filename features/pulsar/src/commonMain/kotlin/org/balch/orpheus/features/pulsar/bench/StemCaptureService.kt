package org.balch.orpheus.features.pulsar.bench

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.serialization.Serializable

/** Records the next `seconds` of the chosen stems (indices into StemLayout.NAMES) to stereo WAV files. */
interface StemCaptureService {
    suspend fun capture(seconds: Float, stems: List<Int>, progress: suspend (Float) -> Unit = {}): StemCaptureOutcome
}

/** [dir] is the capture folder's absolute path, empty when nothing was kept; [message] is meant for the user. */
data class StemCaptureOutcome(
    val ok: Boolean,
    val message: String,
    val dir: String = "",
    val files: List<StemFile> = emptyList(),
    val frames: Int = 0,
    val droppedFrames: Long = 0,
)

/** One written stem: [label] names the track's engine for track0-7, else repeats [stem]. */
@Serializable
data class StemFile(val stem: String, val label: String, val path: String, val rmsDb: Float, val peakDb: Float)

/** Default where no stem writer exists: iOS, and the og/core edition (the writer ships with desktop and Android ai). */
@ContributesBinding(AppScope::class)
@Inject
class UnavailableStemCaptureService : StemCaptureService {
    override suspend fun capture(seconds: Float, stems: List<Int>, progress: suspend (Float) -> Unit) =
        StemCaptureOutcome(ok = false, message = "raw audio isn't available on this platform yet")
}
