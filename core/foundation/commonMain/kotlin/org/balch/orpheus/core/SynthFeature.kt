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
 * A ViewModel that acts as a SynthFeature.
 * Implementers must provide a stable StateFlow and stable Actions.
 */
interface SynthViewModel<S, A> : SynthFeature<S, A> {
    override val stateFlow: StateFlow<S>
    override val actions: A
}

/**
 * Retrieve a ViewModel from the Metro DI graph, returning it as a feature.
 * Works like metroViewModel() but allows casting to a specific feature interface (e.g. BossFeature).
 *
 * Usage: `val feature: MyFeature = synthViewModel<MyViewModel, MyFeature>()`
 */
@Suppress("UNCHECKED_CAST")
@Composable
inline fun <reified VM : ViewModel, S, A> synthViewModel(): SynthFeature<S, A> =
    metroViewModel<VM>() as SynthFeature<S, A>


@Suppress("UNCHECKED_CAST")
@Composable
inline fun <reified VM : ViewModel, R> synthViewModel(): R =
    metroViewModel<VM>() as R
