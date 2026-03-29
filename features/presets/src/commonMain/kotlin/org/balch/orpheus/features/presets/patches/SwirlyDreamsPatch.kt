package org.balch.orpheus.features.presets.patches

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.presets.SynthPatch

@Inject
@ContributesIntoSet(AppScope::class, binding = binding<SynthPatch>())
class SwirlyDreamsPatch : JsonSynthPatch(
    id = "swirly_dreams",
    name = "Swirly Dreams",
)
