package org.balch.orpheus.di

import androidx.lifecycle.ViewModel
import org.balch.orpheus.core.SynthFeature
import kotlin.reflect.KClass

internal actual fun filterSynthFeatureClasses(
    classes: Set<KClass<out ViewModel>>
): Set<KClass<out ViewModel>> =
    classes.filterTo(mutableSetOf()) {
        SynthFeature::class.java.isAssignableFrom(it.java)
    }
