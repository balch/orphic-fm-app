package org.balch.orpheus.features.pulsar.models

import kotlin.jvm.JvmInline

/** A built-in vibe's name, as its [VibeProvider] declares it and the catalogs list it. The names are in `VibeNames`. */
@JvmInline
value class VibeName(val value: String)
