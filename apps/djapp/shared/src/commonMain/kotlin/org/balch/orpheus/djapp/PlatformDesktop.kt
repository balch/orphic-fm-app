package org.balch.orpheus.djapp

/**
 * True in the JVM desktop app, whatever its window size; false on every phone, tablet, foldable
 * and television. Gates what only a desktop screen has room for, such as the dock's big dome.
 */
expect fun isDesktopPlatform(): Boolean
