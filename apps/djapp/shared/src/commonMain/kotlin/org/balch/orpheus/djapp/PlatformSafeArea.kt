package org.balch.orpheus.djapp

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable

/**
 * Safe-area insets the DJ app's foreground chrome (header, bottom nav) should honor, resolved
 * per platform. The animated VizBackground deliberately ignores this and fills edge-to-edge into
 * the display cutout, so only interactive/text content gets pushed clear of the notch.
 *
 * - **iOS** returns the real safe area. The status bar is hidden (see project.yml), but the
 *   Dynamic Island / notch and the home indicator still reserve space, so top content must be
 *   inset or it renders under the Island.
 * - **Android** returns zero on a phone or tablet: it hides the system bars and intentionally
 *   draws the chrome into the cutout (side notches are handled separately via
 *   [WindowInsets.displayCutout]). On a physical television it instead returns the overscan
 *   margin, since there the edge is destructively cropped rather than merely occluded.
 * - **Desktop** always returns zero; there is no notch.
 */
@Composable
expect fun platformSafeAreaInsets(): WindowInsets
