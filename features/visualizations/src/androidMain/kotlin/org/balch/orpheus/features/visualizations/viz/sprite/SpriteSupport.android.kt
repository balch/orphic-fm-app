package org.balch.orpheus.features.visualizations.viz.sprite

import android.os.Build

/** The hardware canvas draws `drawVertices` from Android 10; older devices keep the Canvas draws. */
internal actual fun spriteBatchSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
