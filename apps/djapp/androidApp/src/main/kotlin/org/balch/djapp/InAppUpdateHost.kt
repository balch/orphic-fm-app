package org.balch.djapp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.balch.orpheus.core.update.InAppUpdateManager
import org.balch.orpheus.core.update.InAppUpdateUiState
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.widgets.InAppUpdateBanner

/**
 * Bridges [InAppUpdateManager] to the Compose UI: registers the modern StartIntentSenderForResult
 * launcher, collects the manager's update state under the Activity lifecycle, resumes an
 * interrupted Immediate update on resume, and renders the themed banner for the Flexible flow.
 */
@Composable
fun InAppUpdateHost(manager: InAppUpdateManager, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        // A cancelled/failed IMMEDIATE consent must re-prompt: the manager clears its
        // one-shot guard so the next foreground re-launches. Flexible needs no action.
        manager.onUpdateFlowResult(result.resultCode)
    }

    val state by remember(launcher, manager) { manager.uiStateFlow(launcher) }
        .collectAsStateWithLifecycle(initialValue = InAppUpdateUiState.Hidden)

    // Reset dismissal when the banner phase changes (e.g. a freshly Ready update),
    // but not on every download-progress tick. A real return from the background
    // (ON_START, below) also resets it, so a banner the user tapped "Later" on is
    // re-offered on the next foreground rather than suppressed for the whole process.
    var dismissed by remember { mutableStateOf(false) }
    val stateKind = when (state) {
        InAppUpdateUiState.Hidden -> 0
        is InAppUpdateUiState.Downloading -> 1
        InAppUpdateUiState.Ready -> 2
    }
    LaunchedEffect(stateKind) { dismissed = false }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, launcher) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // A genuine background -> foreground crosses STOP. Re-offer a banner
                // the user previously dismissed. Gated on START rather than RESUME so
                // transient pauses (notification shade, permission dialogs) don't
                // instantly undo a "Later" tap.
                Lifecycle.Event.ON_START -> dismissed = false
                // Resume an interrupted Immediate update each time the app resumes.
                Lifecycle.Event.ON_RESUME ->
                    scope.launch { manager.resumeImmediateIfInProgress(launcher) }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!dismissed) {
        InAppUpdateBanner(
            state = state,
            onRestart = { scope.launch { manager.completeUpdate() } },
            onDismiss = { dismissed = true },
            modifier = modifier,
        )
    }
}

// ---------------------------------------------------------------------------
// Previews
//
// [InAppUpdateHost] itself can't be previewed — it needs a live [InAppUpdateManager]
// (Play services) and an Activity-scoped result launcher. These previews instead
// reproduce the host's *position in its container*: a full-screen Box with the banner
// pinned via the SAME Modifier.align(Alignment.BottomCenter) that MainActivity applies
// through DjApp's BoxScope overlay slot. Use them to verify bottom-center placement
// (the live app is edge-to-edge with system bars hidden, so the banner floats just
// above the gesture-nav region — showSystemUi renders that area for comparison).
// ---------------------------------------------------------------------------

@Composable
private fun UpdateHostContainerPreview(state: InAppUpdateUiState) {
    OrpheusTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(OrpheusColors.darkVoid),
        ) {
            // Stand-in for the live app content the banner overlays.
            InAppUpdateBanner(
                state = state,
                onRestart = {},
                onDismiss = {},
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Preview(name = "Host container — Ready", showSystemUi = true)
@Composable
private fun UpdateHostReadyContainerPreview() =
    UpdateHostContainerPreview(InAppUpdateUiState.Ready)

@Preview(name = "Host container — Downloading 62%", showSystemUi = true)
@Composable
private fun UpdateHostDownloadingContainerPreview() =
    UpdateHostContainerPreview(InAppUpdateUiState.Downloading(progress = 0.62f))

@Preview(name = "Host container — Indeterminate", showSystemUi = true)
@Composable
private fun UpdateHostIndeterminateContainerPreview() =
    UpdateHostContainerPreview(InAppUpdateUiState.Downloading(progress = null))
