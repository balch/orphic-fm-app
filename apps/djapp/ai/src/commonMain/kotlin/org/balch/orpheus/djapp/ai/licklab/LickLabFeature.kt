package org.balch.orpheus.djapp.ai.licklab

import androidx.compose.ui.input.key.Key
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.balch.orpheus.core.features.SynthFeature

/**
 * What the Lick Lab surface can do. Pointer and key MIDI are in the vibe's own key; the view model applies
 * any Kraken shift. Shift is a key like any other: held while overlapping, it makes the overlap a slide.
 */
interface LickLabActions {
    fun toggleShown() {}
    fun arm(track: Int) {}
    fun setCaller(track: Int?) {}
    fun setSlot(slot: SlotRef) {}
    fun setKeyMode(mode: KeyMode) {}
    fun toggleRecording() {}
    fun setHintMode(mode: HintMode) {}

    /** +4 dB on the armed track, live only. */
    fun toggleBoost() {}
    fun keyDown(key: Key) {}
    fun keyUp(key: Key) {}
    fun pointerDown(id: Long, midi: Int, velocity: Float) {}
    fun pointerSlide(id: Long, midi: Int, glideRate: Float) {}
    fun pointerBend(id: Long, semis: Float) {}
    fun pointerUp(id: Long) {}

    /** Lets go of every key and pointer and silences the track, which stays armed (focus loss). */
    fun releaseAll() {}

    /** Hands the armed track back to its sequencer; Lick Lab stays shown (the mixer went compact). */
    fun disarm() {}
    fun star(takeId: Long) {}
    fun delete(takeId: Long) {}
    fun copy(takeId: Long) {}
    fun playTake(takeId: Long) {}

    companion object {
        val EMPTY: LickLabActions = object : LickLabActions {}
    }
}

interface LickLabFeature : SynthFeature<LickLabUiState, LickLabActions> {
    /** Paste-ready Kotlin from [LickLabActions.copy]; the panel puts it on the clipboard with `copyPlainText`. */
    val copied: Flow<String> get() = emptyFlow()

    /** The Lick Lab clock now, in beats, for a canvas that scrolls each frame. */
    fun currentBeat(): Double = 0.0

    /** The live beat length in ms, so an animation can run on frame time between clock nudges. */
    fun msPerBeat(): Double = 500.0

    /** The take as Kotlin, exported against the vibe and slot it was played in. */
    fun kotlinFor(takeId: Long): String? = null

    /** The take's export, for the marks the takes list shows (over the cap, merged, chromatic, clamped). */
    fun exportFor(takeId: Long): ExportedLick? = null

    override val synthControl: SynthFeature.SynthControl
        get() = SynthFeature.SynthControl.Empty
}
