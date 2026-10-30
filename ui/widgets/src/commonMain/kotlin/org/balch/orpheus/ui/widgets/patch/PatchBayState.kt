package org.balch.orpheus.ui.widgets.patch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.audio.patch.DuoPatch
import org.balch.orpheus.core.audio.patch.PatchCable
import org.balch.orpheus.core.audio.patch.PatchDest
import org.balch.orpheus.core.audio.patch.PatchFamily
import org.balch.orpheus.core.audio.patch.PatchSource
import org.balch.orpheus.core.audio.patch.VoiceSlot
import org.balch.orpheus.core.audio.patch.reachesEngine

/** What the bay shows bright: every cable, one family's, or a single cable. */
sealed interface PatchFocus {
    data object All : PatchFocus
    data class Family(val family: PatchFamily) : PatchFocus
    data class Cable(val source: PatchSource, val dest: PatchDest, val slot: VoiceSlot) : PatchFocus
}

/** How a cable is drawn: bright, a faint thread outside the focus, or parked (dashed, silent). */
enum class CableEmphasis { BRIGHT, DIM, PARKED }

/** Exposed on each cable's node so tests and accessibility can read how it is drawn. */
val CableEmphasisKey = SemanticsPropertyKey<CableEmphasis>("CableEmphasis")
var SemanticsPropertyReceiver.cableEmphasis by CableEmphasisKey

/** A cable being dragged: from an output ([plug] null), or a plug pulled off its input. */
data class PatchDrag(val from: JackId.Out, val plug: PatchCable?, val pointer: Offset)

/** Focus, drag and jack centres for one open bay. Centres are in the bay's own coordinates. */
@Stable
class PatchBayState {
    var focus: PatchFocus by mutableStateOf(PatchFocus.All)
    var drag: PatchDrag? by mutableStateOf(null)

    // Plain fields: layout callbacks write them, and only a changed centre reaches snapshot state.
    private var bay: LayoutCoordinates? = null
    private val jackCoordinates = HashMap<JackId, LayoutCoordinates>()
    private val centres = mutableStateMapOf<JackId, Offset>()

    /** Jack centres relative to the bay, so moving the whole bay (a sheet sliding in) changes nothing. */
    val jacks: Map<JackId, Offset> get() = centres

    internal fun onBayPositioned(coordinates: LayoutCoordinates) {
        bay = coordinates
        jackCoordinates.forEach { (id, c) -> measure(id, c) }
    }

    internal fun onJackPositioned(id: JackId, coordinates: LayoutCoordinates) {
        jackCoordinates[id] = coordinates
        measure(id, coordinates)
    }

    internal fun removeJack(id: JackId) {
        jackCoordinates.remove(id)
        centres.remove(id)
    }

    private fun measure(id: JackId, jack: LayoutCoordinates) {
        val bay = bay?.takeIf { it.isAttached } ?: return
        if (!jack.isAttached) return
        val centre = bay.localPositionOf(jack, Offset(jack.size.width / 2f, jack.size.height / 2f))
        if (centres[id] != centre) centres[id] = centre
    }

    fun isFocused(cable: PatchCable): Boolean = when (val f = focus) {
        PatchFocus.All -> false
        is PatchFocus.Family -> cable.source.family == f.family
        is PatchFocus.Cable -> cable.source == f.source && cable.dest == f.dest && cable.slot == f.slot
    }

    fun emphasis(cable: PatchCable, engine: OrpheusEngineId): CableEmphasis = when {
        cable.parked -> CableEmphasis.PARKED
        !cable.dest.reachesEngine(engine) -> CableEmphasis.DIM
        focus == PatchFocus.All || isFocused(cable) -> CableEmphasis.BRIGHT
        else -> CableEmphasis.DIM
    }

    fun focusOn(cable: PatchCable) {
        focus = PatchFocus.Cable(cable.source, cable.dest, cable.slot)
    }
}

@Composable
fun rememberPatchBayState(): PatchBayState = remember { PatchBayState() }

/** `<SOURCE>-<DEST>-<SLOT>`, the suffix of every per-cable test tag. */
internal val PatchCable.tagKey: String get() = "${source.name}-${dest.name}-${slot.name}"

/** The input jack a cable plugs into: bridged rows plug into the A jack, which is tagged BOTH. */
internal val PatchCable.inJack: JackId.In get() = JackId.In(dest, slot)

/** Whether [dest]'s A and B jacks act as one. */
internal fun DuoPatch.isBridged(dest: PatchDest): Boolean = when (dest) {
    PatchDest.PITCH -> pitchBridged
    PatchDest.ENV -> envBridged
    else -> true
}

/** The cable a drop of [source] onto [jack] would make or find, normalised for bridged rows. */
internal fun DuoPatch.slotFor(jack: JackId.In): VoiceSlot =
    if (!jack.dest.perVoice || isBridged(jack.dest)) VoiceSlot.BOTH else jack.slot
