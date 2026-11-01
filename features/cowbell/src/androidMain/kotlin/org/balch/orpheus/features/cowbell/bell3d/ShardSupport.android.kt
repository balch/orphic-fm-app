package org.balch.orpheus.features.cowbell.bell3d

import android.os.Build

/** The hardware canvas draws `drawVertices` from Android 10; older devices turn plainly. */
internal actual fun shardsSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
