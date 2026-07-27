package org.balch.orpheus.core.features

import androidx.lifecycle.ViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.MetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.ViewModelAssistedFactory
import kotlin.reflect.KClass

/**
 * A [MetroViewModelFactory] that uses an injected map of [KClass] to provider of [ViewModel]
 * to create ViewModels via Metro DI.
 *
 * Lives in `:core:features` rather than in each app, because both apps need exactly this and both
 * apps already depend on this module. `@ContributesBinding(AppScope::class)` means any graph on the
 * AppScope classpath picks it up with no per-app wiring.
 *
 * The three maps come from `MetroViewModelMultibindings`, which every app graph inherits via
 * `ViewModelGraph`. In practice only [SynthFeatureRegistry] is in the plain ViewModel map: real
 * feature ViewModels are NOT ViewModels in the AndroidX sense, they live in the FeatureScope child
 * graph and are reached through [FeatureCollection]. See `docs/di-architecture.md`.
 */
@ContributesBinding(AppScope::class)
@Inject
class InjectedViewModelFactory(
    override val viewModelProviders: Map<KClass<out ViewModel>, () -> ViewModel>,
    override val assistedFactoryProviders: Map<KClass<out ViewModel>, () -> ViewModelAssistedFactory>,
    override val manualAssistedFactoryProviders: Map<KClass<out ManualViewModelAssistedFactory>, () -> ManualViewModelAssistedFactory>,
) : MetroViewModelFactory()
