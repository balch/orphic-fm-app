package org.balch.orpheus.djapp.ai.ideas

import androidx.compose.runtime.Immutable

/** One idea card: [text] is shown, [prompt] is sent on Try, and a [mix] idea needs the Mixer open. */
@Immutable
data class Idea(val text: String, val prompt: String, val mix: Boolean)
