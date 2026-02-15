package org.balch.orpheus.di

import androidx.lifecycle.ViewModel
import kotlin.reflect.KClass

/**
 * WasmJs fallback: no Java reflection available.
 * Returns all classes — non-SynthFeature VMs will fail the `as?` cast at the call site.
 * This is acceptable because the wasmJs target has limited DI support.
 */
internal actual fun filterSynthFeatureClasses(
    classes: Set<KClass<out ViewModel>>
): Set<KClass<out ViewModel>> = classes
