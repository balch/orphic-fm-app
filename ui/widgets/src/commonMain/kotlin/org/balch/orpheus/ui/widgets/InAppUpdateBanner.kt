package org.balch.orpheus.ui.widgets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.core.update.InAppUpdateUiState
import org.balch.orpheus.ui.infrastructure.LocalDialogLiquidState
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidScope
import org.balch.orpheus.ui.infrastructure.liquidVizEffects
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme

/**
 * Themed liquid-glass banner for the Flexible in-app update flow. Renders nothing for
 * [InAppUpdateUiState.Hidden]. Uses the dialog liquid lens when available (null in previews,
 * which falls back to a solid translucent surface).
 */
@Composable
fun InAppUpdateBanner(
    state: InAppUpdateUiState,
    onRestart: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        InAppUpdateUiState.Hidden -> Unit

        is InAppUpdateUiState.Downloading -> UpdateBannerSurface(modifier) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    text = "Downloading update…",
                    color = OrpheusColors.sterlingSilver,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
                val progress = state.progress
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        color = OrpheusColors.neonCyan,
                    )
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        color = OrpheusColors.metallicBlue,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        color = OrpheusColors.neonCyan,
                    )
                }
            }
        }

        InAppUpdateUiState.Ready -> UpdateBannerSurface(modifier) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Update ready",
                    color = OrpheusColors.sterlingSilver,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) {
                        Text("Later", color = OrpheusColors.metallicBlue)
                    }
                    TextButton(onClick = onRestart) {
                        Text("Restart now", color = OrpheusColors.neonCyan, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateBannerSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .liquidVizEffects(
                liquidState = LocalDialogLiquidState.current,
                scope = VisualizationLiquidScope(contrast = 1.2f, saturation = 0.9f),
                frostAmount = 10.dp,
                color = OrpheusColors.deepPurple,
                tintAlpha = 0.4f,
                shape = shape,
            ),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        shape = shape,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
    ) {
        content()
    }
}

@Composable
private fun BannerPreviewScaffold(state: InAppUpdateUiState) {
    OrpheusTheme {
        Box(modifier = Modifier.background(OrpheusColors.darkVoid).padding(16.dp)) {
            InAppUpdateBanner(state = state, onRestart = {}, onDismiss = {})
        }
    }
}

@Preview
@Composable
private fun InAppUpdateBannerDownloading8Preview() =
    BannerPreviewScaffold(InAppUpdateUiState.Downloading(progress = 0.08f))

@Preview
@Composable
private fun InAppUpdateBannerDownloading62Preview() =
    BannerPreviewScaffold(InAppUpdateUiState.Downloading(progress = 0.62f))

@Preview
@Composable
private fun InAppUpdateBannerDownloading100Preview() =
    BannerPreviewScaffold(InAppUpdateUiState.Downloading(progress = 1.0f))

@Preview
@Composable
private fun InAppUpdateBannerIndeterminatePreview() =
    BannerPreviewScaffold(InAppUpdateUiState.Downloading(progress = null))

@Preview
@Composable
private fun InAppUpdateBannerReadyPreview() =
    BannerPreviewScaffold(InAppUpdateUiState.Ready)
