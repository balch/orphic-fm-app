package org.balch.orpheus.ui.panels.slots

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.ui.theme.OrpheusColors

private val CardBackground = Color(0xFF1E1E2E)
private val FallbackDotColor = Color(0xFF8888AA)

/**
 * Panel search over a dimmed scrim. The card is opaque (a reading surface) and built in-layout,
 * not as a Dialog. Enter picks the top row, Escape or a tap outside dismisses. Keys are handled
 * on the root, so they work wherever focus is.
 */
@Composable
fun PanelSearchOverlay(
    candidates: List<SearchCandidate>,
    colors: Map<PanelId, Color>,
    autoFocus: Boolean,
    onPick: (PanelId) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialQuery: String = "",
) {
    var query by remember { mutableStateOf(initialQuery) }
    val focusRequester = remember { FocusRequester() }
    val labels = remember(candidates) { candidates.associate { it.entry.id to it } }

    // Blank shows every panel grouped like the rail; typing shows one ranked list.
    val grouped = query.isBlank()
    val results = remember(query, candidates) { searchPanels(query, candidates) }
    val sections: List<Pair<PanelGroup?, List<PanelNavEntry>>> = if (grouped) {
        PanelGroup.entries.mapNotNull { group ->
            results.filter { it.group == group }.takeIf { it.isNotEmpty() }?.let { group to it }
        }
    } else {
        listOf(null to results)
    }
    val top = sections.firstOrNull()?.second?.firstOrNull()

    // autoFocus puts the caret in the field. Otherwise the root takes focus so Escape and Enter
    // still work, without raising a soft keyboard.
    val rootFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(autoFocus) {
        if (autoFocus) focusRequester.requestFocus() else rootFocus.requestFocus()
    }
    fun pickTop() {
        top?.let { onPick(it.id) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(onDismiss) { detectTapGestures { onDismiss() } }
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Enter, Key.NumPadEnter -> {
                        pickTop()
                        true
                    }
                    Key.Escape -> {
                        onDismiss()
                        true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 32.dp)
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(CardBackground)
                .border(BorderStroke(1.dp, OrpheusColors.outlineDark), RoundedCornerShape(14.dp))
                // Swallow taps so only the scrim outside the card dismisses.
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            TextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Search panels", color = OrpheusColors.greyText) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, tint = OrpheusColors.steelGrey)
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = CardBackground,
                    unfocusedContainerColor = CardBackground,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedIndicatorColor = OrpheusColors.neonCyan,
                    unfocusedIndicatorColor = OrpheusColors.outlineDark,
                    cursorColor = OrpheusColors.neonCyan,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { pickTop() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false).padding(vertical = 4.dp),
            ) {
                sections.forEach { (group, entries) ->
                    if (group != null) {
                        item(key = "header-${group.name}") { GroupHeader(group) }
                    }
                    entries.forEach { entry ->
                        item(key = entry.id.id) {
                            ResultRow(
                                entry = entry,
                                label = labels[entry.id]?.label ?: entry.shortLabel,
                                description = labels[entry.id]?.description.orEmpty(),
                                dot = colors[entry.id] ?: FallbackDotColor,
                                highlighted = entry == top,
                                onClick = {
                                    focusManager.clearFocus()
                                    onPick(entry.id)
                                },
                            )
                        }
                    }
                }
                if (top == null) {
                    item(key = "empty") {
                        Text(
                            text = "No panels match",
                            color = OrpheusColors.greyText,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(group: PanelGroup) {
    Text(
        text = group.title,
        color = group.titleColor(),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun ResultRow(
    entry: PanelNavEntry,
    label: String,
    description: String,
    dot: Color,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(if (highlighted) dot.copy(alpha = 0.20f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Text(
            text = label,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Text(
            text = entry.shortLabel,
            color = OrpheusColors.greyText,
            fontSize = 10.sp,
            maxLines = 1,
        )
        Text(
            text = description,
            color = OrpheusColors.steelGrey,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
