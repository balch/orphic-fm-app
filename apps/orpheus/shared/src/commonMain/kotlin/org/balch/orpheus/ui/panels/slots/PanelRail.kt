package org.balch.orpheus.ui.panels.slots

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.ui.theme.OrpheusColors

private val RailWidth = 72.dp
private val RailChipHeight = 40.dp
private val RailBackground = Color(0xFF14141F)
private val FallbackChipColor = Color(0xFF8888AA)

internal fun PanelGroup.titleColor(): Color = when (this) {
    PanelGroup.Rhythm -> OrpheusColors.neonOrange
    PanelGroup.Mod -> OrpheusColors.neonCyan
    PanelGroup.Fx -> OrpheusColors.cosmicPurple
    PanelGroup.Tools -> OrpheusColors.steelGrey
}

/** Vertical panel list grouped by kind. Open panels are lit in their own color. */
@Composable
fun PanelRail(
    entries: List<PanelNavEntry>,
    colors: Map<PanelId, Color>,
    openIds: Set<PanelId>,
    onOpen: (PanelId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(RailWidth)
            .background(RailBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        PanelGroup.entries.forEach { group ->
            val inGroup = entries.filter { it.group == group }
            if (inGroup.isEmpty()) return@forEach

            Text(
                text = group.title,
                color = group.titleColor(),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(start = 2.dp, top = 6.dp, bottom = 1.dp),
            )
            inGroup.forEach { entry ->
                RailChip(
                    label = entry.shortLabel,
                    color = colors[entry.id] ?: FallbackChipColor,
                    lit = entry.id in openIds,
                    onClick = { onOpen(entry.id) },
                )
            }
        }
    }
}

@Composable
private fun RailChip(label: String, color: Color, lit: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    // 40dp hit area; the visible pill inside is slimmer.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(RailChipHeight)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(if (lit) color.copy(alpha = 0.32f) else color.copy(alpha = 0.07f))
                .border(BorderStroke(1.dp, if (lit) color else color.copy(alpha = 0.30f)), shape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = if (lit) Color.White else Color.White.copy(alpha = 0.66f),
                fontSize = 11.sp,
                fontWeight = if (lit) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
        }
    }
}
