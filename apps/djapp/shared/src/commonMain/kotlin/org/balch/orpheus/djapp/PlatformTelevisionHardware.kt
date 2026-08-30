package org.balch.orpheus.djapp

import androidx.compose.runtime.Composable

/**
 * True only on real television hardware (Chromecast with Google TV, Android TV boxes) — never on
 * a phone, tablet, or a desktop/tablet window that merely grows wide or fullscreen enough to enter
 * the TV/LargeScreen layout. See [org.balch.orpheus.ui.infrastructure.LocalTelevisionHardware] for
 * why that distinction matters: this hardware signal, not the layout signal, is what should gate
 * anything a television's weaker GPU genuinely cannot afford (e.g. panel glass).
 */
@Composable
expect fun isTelevisionHardware(): Boolean
