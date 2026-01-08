package org.balch.orpheus.core

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.flow.StateFlow

/**
 * A feature that exposes state as a StateFlow and stable actions.
 * Child composables should collect state at the leaf level for optimal recomposition.
 */
interface SynthFeature<S, A> {
    val stateFlow: StateFlow<S>
    val actions: A
}

/**
 * A ViewModel that acts as a PanelFeature.
 * Implementers must provide a stable StateFlow and stable Actions.
 */
interface SynthViewModel<S, A> : SynthFeature<S, A> {
    override val stateFlow: StateFlow<S>
    override val actions: A
}

/**
 * Retrieve a PanelViewModel from the Metro DI graph, returning it as a PanelFeature.
 * Works like metroViewModel() but returns the PanelFeature interface directly.
 *
 * Usage: `val feature: PanelFeature<State, Actions> = panelViewModel<MyViewModel, State, Actions>()`
 *
 * The ViewModel must implement [PanelViewModel<S, A>].
 */
@Suppress("UNCHECKED_CAST")
@Composable
inline fun <reified VM : ViewModel, S, A> synthViewModel(): SynthFeature<S, A> =
    metroViewModel<VM>() as SynthFeature<S, A>

