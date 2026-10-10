package org.balch.orpheus.djapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import org.balch.orpheus.features.pulsar.PulsarSelectors

/**
 * Whether the DJ app shows the Kraken pad, beside the phone's VIBE row and the dock's vibe picker.
 * Off unless built with `-PshowKraken=true`; tests provide it to render either way.
 */
val LocalShowKraken = staticCompositionLocalOf { DjBuildKonfig.SHOW_KRAKEN }

/** The Pulsar panel's top row wherever the DJ app shows one: VIBE, plus the Kraken when it is on. */
@Composable
@ReadOnlyComposable
internal fun djVibeSelectors(): PulsarSelectors =
    if (LocalShowKraken.current) PulsarSelectors.VibeAndKraken else PulsarSelectors.VibeOnly
