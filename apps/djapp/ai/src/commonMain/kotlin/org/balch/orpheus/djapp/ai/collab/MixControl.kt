package org.balch.orpheus.djapp.ai.collab

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.balch.orpheus.features.pulsar.bench.DelayFx
import org.balch.orpheus.features.pulsar.bench.Macro

/** Who made an edit to the mixer. */
@Serializable
enum class EditOrigin { USER, AGENT, MCP }

/** One mixer control an edit can touch. [track] is null for controls that belong to no track. */
@Serializable
sealed interface MixControl {
    val track: Int?

    @Serializable @SerialName("level") data class Level(override val track: Int) : MixControl
    @Serializable @SerialName("pan") data class Pan(override val track: Int) : MixControl
    @Serializable @SerialName("send") data class Send(override val track: Int, val reverb: Boolean) : MixControl
    @Serializable @SerialName("mute") data class Mute(override val track: Int) : MixControl
    @Serializable @SerialName("solo") data class Solo(override val track: Int) : MixControl
    @Serializable @SerialName("macro") data class MacroKnob(val macro: Macro) : MixControl {
        override val track: Int? = null
    }
    /** The Pulsar delay's time A, time B or feedback; [Send] is a track's delay send. */
    @Serializable @SerialName("delay") data class DelaySetting(val param: DelayFx) : MixControl {
        override val track: Int? = null
    }
    @Serializable @SerialName("section") data object Section : MixControl {
        override val track: Int? = null
    }
    @Serializable @SerialName("loop") data object Loop : MixControl {
        override val track: Int? = null
    }
    @Serializable @SerialName("scope") data object EditScope : MixControl {
        override val track: Int? = null
    }
    /** A reset of the mix edits as a whole; its "after" is always "file". */
    @Serializable @SerialName("clearAll") data object ClearAll : MixControl {
        override val track: Int? = null
    }
}

/** Short name for the feed and tool replies, e.g. "BASS level", "PAD reverb", "ENERGY". */
fun MixControl.label(trackNames: (Int) -> String): String = when (this) {
    is MixControl.Level -> "${trackNames(track)} level"
    is MixControl.Pan -> "${trackNames(track)} pan"
    is MixControl.Send -> "${trackNames(track)} ${if (reverb) "reverb" else "delay"}"
    is MixControl.Mute -> "${trackNames(track)} mute"
    is MixControl.Solo -> "${trackNames(track)} solo"
    is MixControl.MacroKnob -> macro.name
    is MixControl.DelaySetting -> when (param) {
        DelayFx.TIME_A -> "delay time A"
        DelayFx.TIME_B -> "delay time B"
        DelayFx.FEEDBACK -> "delay feedback"
    }
    MixControl.Section -> "section"
    MixControl.Loop -> "loop"
    MixControl.EditScope -> "edit scope"
    MixControl.ClearAll -> "clear all"
}
