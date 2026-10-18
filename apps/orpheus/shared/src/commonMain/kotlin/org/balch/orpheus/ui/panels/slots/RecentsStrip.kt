package org.balch.orpheus.ui.panels.slots

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.ui.theme.OrpheusColors

private val StripHeight = 40.dp
private val HandlePillHeight = 20.dp
private val TouchTarget = 40.dp
private val HandleTouchTarget = 36.dp
private val StripBackground = Color(0xFF14141F)
private val FallbackChipColor = Color(0xFF8888AA)

/**
 * Top navigation strip: search and rail toggles, the open panels (bright, pin marked), a divider,
 * the recents (dim) and a collapse chevron. Chips scroll sideways rather than wrapping.
 */
@Composable
fun RecentsStrip(
    entries: Map<PanelId, PanelNavEntry>,
    colors: Map<PanelId, Color>,
    slots: PanelSlots,
    railVisible: Boolean,
    onOpen: (PanelId) -> Unit,
    onSearch: () -> Unit,
    onToggleRail: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val openEntries = slots.openIds.mapNotNull { entries[it] }
    val recentEntries = slots.recents.mapNotNull { entries[it] }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(StripHeight)
            .background(StripBackground)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StripIconButton(Icons.Default.Search, "Search panels", onClick = onSearch)
        StripIconButton(
            Icons.Default.Menu,
            "Toggle panel rail",
            onClick = onToggleRail,
            active = railVisible,
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            openEntries.forEach { entry ->
                PanelChip(
                    label = entry.shortLabel,
                    color = colors[entry.id] ?: FallbackChipColor,
                    lit = true,
                    pinned = entry.id in slots.pinned,
                    onClick = { onOpen(entry.id) },
                )
            }
            if (openEntries.isNotEmpty() && recentEntries.isNotEmpty()) {
                Box(
                    Modifier
                        .padding(horizontal = 2.dp)
                        .width(1.dp)
                        .height(16.dp)
                        .background(OrpheusColors.outlineDark),
                )
            }
            recentEntries.forEach { entry ->
                PanelChip(
                    label = entry.shortLabel,
                    color = colors[entry.id] ?: FallbackChipColor,
                    lit = false,
                    pinned = false,
                    onClick = { onOpen(entry.id) },
                )
            }
        }
        StripIconButton(Icons.Default.KeyboardArrowUp, "Collapse strip", onClick = onCollapse)
    }
}

/**
 * The collapsed strip: a slim pill behind three touch-sized buttons (rail, search, expand).
 * Only the pill is drawn small; each button's hit area is [HandleTouchTarget] square.
 */
@Composable
fun RecentsHandle(
    onExpand: () -> Unit,
    onSearch: () -> Unit,
    onToggleRail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.height(HandleTouchTarget), contentAlignment = Alignment.Center) {
        val pill = RoundedCornerShape(HandlePillHeight / 2)
        Box(
            Modifier
                .matchParentSize()
                .wrapContentHeight(Alignment.CenterVertically)
                .height(HandlePillHeight)
                .clip(pill)
                .background(StripBackground)
                .border(BorderStroke(1.dp, OrpheusColors.outlineDark), pill),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            HandleButton(Icons.Default.Menu, "Toggle panel rail", onToggleRail)
            HandleButton(Icons.Default.Search, "Search panels", onSearch)
            HandleButton(Icons.Default.KeyboardArrowDown, "Expand strip", onExpand)
        }
    }
}

@Composable
private fun PanelChip(
    label: String,
    color: Color,
    lit: Boolean,
    pinned: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val fill = if (lit) color.copy(alpha = 0.30f) else color.copy(alpha = 0.08f)
    val stroke = if (lit) color else color.copy(alpha = 0.35f)
    val text = if (lit) Color.White else Color.White.copy(alpha = 0.62f)

    // The clickable box is the full strip height; the visible pill inside stays slim.
    Box(
        modifier = Modifier
            .height(TouchTarget)
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(22.dp)
                .clip(shape)
                .background(fill)
                .border(BorderStroke(1.dp, stroke), shape)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = label,
                color = text,
                fontSize = 11.sp,
                fontWeight = if (lit) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
            if (pinned) {
                Icon(
                    imageVector = Icons.Default.PushPin,
                    contentDescription = "Pinned",
                    tint = color,
                    modifier = Modifier.size(11.dp),
                )
            }
        }
    }
}

@Composable
private fun StripIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    Box(
        modifier = Modifier
            .size(TouchTarget)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (active) {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(OrpheusColors.neonCyan.copy(alpha = 0.18f)),
            )
        }
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (active) OrpheusColors.neonCyan else OrpheusColors.brightSilver,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun HandleButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(HandleTouchTarget).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = OrpheusColors.brightSilver,
            modifier = Modifier.size(14.dp),
        )
    }
}
