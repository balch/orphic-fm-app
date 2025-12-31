package org.balch.orpheus.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.StateFlow

data class ViewModelStateActionMapper<S, A>(
    val state: S,
    val actions: A,
)

@Composable
fun <VM, S, A> rememberViewModelStateActionMapper(
    viewModel: VM,
    stateFlowProvider: (VM) -> StateFlow<S>,
    actionsProvider: (VM) -> A
): ViewModelStateActionMapper<S, A> {
    val state by stateFlowProvider(viewModel).collectAsState()
    val actions = remember(viewModel) { actionsProvider(viewModel) }
    return ViewModelStateActionMapper(state, actions)
}

interface PanelViewModel<S, A> {
    val uiState: StateFlow<S>
    val panelActions: A
}

@Composable
fun <S, A> rememberPanelState(
    viewModel: PanelViewModel<S, A>
): ViewModelStateActionMapper<S, A> {
    val state by viewModel.uiState.collectAsState()
    return ViewModelStateActionMapper(state, viewModel.panelActions)
}
