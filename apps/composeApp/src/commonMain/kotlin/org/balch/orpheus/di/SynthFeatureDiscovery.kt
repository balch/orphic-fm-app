package org.balch.orpheus.di

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import org.balch.orpheus.core.SynthFeature
import kotlin.reflect.KClass

/**
 * Returns the subset of [classes] whose ViewModel class implements [SynthFeature].
 * Uses platform-specific reflection to check the type hierarchy without instantiation.
 */
internal expect fun filterSynthFeatureClasses(
    classes: Set<KClass<out ViewModel>>
): Set<KClass<out ViewModel>>

/**
 * Discovers all [SynthFeature] ViewModels registered in the Metro DI graph and
 * retrieves them through the Compose [ViewModelProvider] — the same lifecycle-scoped
 * store that [metroViewModel] uses.
 *
 * No hardcoded list: features are discovered from the factory's provider map
 * by checking which ViewModel classes implement [SynthFeature].
 */
@Composable
fun rememberSynthFeatures(): Set<SynthFeature<*, *>> {
    val factory = LocalMetroViewModelFactory.current as InjectedViewModelFactory
    val storeOwner = checkNotNull(LocalViewModelStoreOwner.current) {
        "No ViewModelStoreOwner was provided via LocalViewModelStoreOwner"
    }
    return remember(factory) {
        val extras = if (storeOwner is HasDefaultViewModelProviderFactory) {
            storeOwner.defaultViewModelCreationExtras
        } else {
            CreationExtras.Empty
        }
        val provider = ViewModelProvider.create(storeOwner.viewModelStore, factory, extras)
        filterSynthFeatureClasses(factory.viewModelKeys)
            .mapNotNull { provider[it] as? SynthFeature<*, *> }
            .toSet()
    }
}
