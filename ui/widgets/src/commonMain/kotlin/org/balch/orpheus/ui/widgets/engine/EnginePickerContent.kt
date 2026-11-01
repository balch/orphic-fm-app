package org.balch.orpheus.ui.widgets.engine

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.balch.orpheus.core.audio.EngineCatalog
import org.balch.orpheus.core.audio.EngineCategory
import org.balch.orpheus.core.audio.EngineChoice
import org.balch.orpheus.core.audio.EngineSlotKind
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme

/** Tiles in a popover on wide windows, tall rows in a bottom sheet on narrow ones. */
internal enum class PickerLayout { Tiles, Rows }

/** Where focus lands on open: the search field (keyboard), the current entry (D-pad), or nowhere (touch). */
internal enum class PickerFocus { Search, Current, None }

/** The dot colour on the pill, tiles and rows. Null (FM_DRUM) borrows Drums. */
fun engineCategoryColor(category: EngineCategory?): Color = when (category) {
    EngineCategory.ANALOG -> OrpheusColors.engineRed
    EngineCategory.HARMONIC -> OrpheusColors.engineBlue
    EngineCategory.TEXTURE -> OrpheusColors.engineGreen
    EngineCategory.PHYSICAL -> OrpheusColors.engineYellow
    EngineCategory.VOICE -> OrpheusColors.speechRose
    EngineCategory.BRAIDS -> OrpheusColors.presetOrange
    EngineCategory.CHAOS -> OrpheusColors.neonMagenta
    EngineCategory.DRUMS, null -> OrpheusColors.electricBlue
    EngineCategory.DX_PATCHES -> OrpheusColors.warmGlow
}

internal fun entryTitle(choice: EngineChoice): String = when (choice) {
    is EngineChoice.Patch -> choice.patch.name
    is EngineChoice.Engine -> EngineCatalog.labelFor(choice)
}

internal fun entrySubtitle(choice: EngineChoice): String = when (choice) {
    is EngineChoice.Patch -> "${choice.patch.engine.name} · ${choice.patch.index + 1}"
    is EngineChoice.Engine -> choice.engine.displayName
}

private sealed interface PickerItem {
    data class Header(val text: String) : PickerItem
    data class Entry(val choice: EngineChoice) : PickerItem
}

/** Asks the entry for [choice] to take focus; [serial] makes a repeat request for the same entry new. */
private data class FocusRequest(val choice: EngineChoice, val serial: Int)

/** Focuses this entry each time [request] changes to a non-null value, once it is composed. */
@Composable
private fun Modifier.focusWhenAsked(request: Int?): Modifier {
    val requester = remember { FocusRequester() }
    LaunchedEffect(request) {
        if (request != null) runCatching { requester.requestFocus() }
    }
    return focusRequester(requester)
}

private fun itemsOf(visible: List<Pair<String, List<EngineChoice>>>): List<PickerItem> =
    visible.flatMap { (header, entries) -> listOf(PickerItem.Header(header)) + entries.map { PickerItem.Entry(it) } }

private val PanelShape = RoundedCornerShape(10.dp)
private val FieldShape = RoundedCornerShape(8.dp)
private val TileShape = RoundedCornerShape(7.dp)

/**
 * Search, chip row and entries for one open picker. Keys are handled here: Esc closes, Enter in
 * the search picks the first match of a non-blank query, Down moves into the entries, where arrows
 * travel and Enter picks. Every arrow is consumed so none leaves the picker.
 */
@Composable
internal fun EnginePickerPanel(
    state: EnginePickerState,
    layout: PickerLayout,
    tag: String,
    initialFocus: PickerFocus,
    onPick: (EngineChoice) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = state.visible
    val items = remember(visible) { itemsOf(visible) }
    val entries = remember(visible) { visible.flatMap { it.second } }
    val enterTarget = entries.firstOrNull()
    // Enter in the search picks the first match only while searching; a blank query picks nothing.
    val searchPick = enterTarget?.takeIf { state.query.isNotBlank() }

    // Open on the current pick's section, header included, so its neighbours show too.
    val startIndex = remember {
        val at = items.indexOf(PickerItem.Entry(state.current))
        if (at < 0) 0 else items.subList(0, at).indexOfLast { it is PickerItem.Header }.coerceAtLeast(0)
    }
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = startIndex)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startIndex)

    val searchFocus = remember { FocusRequester() }
    val rootFocus = remember { FocusRequester() }
    var focusRequest by remember { mutableStateOf<FocusRequest?>(null) }
    var searchFocused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // Scrolls [choice] into view; the entry focuses itself once composed (see focusOn).
    fun focusEntry(choice: EngineChoice) {
        val index = items.indexOf(PickerItem.Entry(choice)).takeIf { it >= 0 } ?: return
        focusRequest = FocusRequest(choice, (focusRequest?.serial ?: 0) + 1)
        scope.launch {
            when (layout) {
                PickerLayout.Tiles -> if (gridState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                    gridState.scrollToItem(index)
                }
                PickerLayout.Rows -> if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                    listState.scrollToItem(index)
                }
            }
        }
    }
    fun focusOn(choice: EngineChoice): Int? = focusRequest?.takeIf { it.choice == choice }?.serial

    LaunchedEffect(Unit) {
        withFrameNanos { }
        when (initialFocus) {
            PickerFocus.Search -> runCatching { searchFocus.requestFocus() }
            PickerFocus.Current -> (entries.firstOrNull { it == state.current } ?: enterTarget)
                ?.let(::focusEntry) ?: runCatching { rootFocus.requestFocus() }
            PickerFocus.None -> runCatching { rootFocus.requestFocus() }
        }
    }
    // A new search or chip starts the entries from the top.
    LaunchedEffect(state) {
        snapshotFlow { Triple(state.query, state.category, state.recent) }.drop(1).collect {
            when (layout) {
                PickerLayout.Tiles -> gridState.scrollToItem(0)
                PickerLayout.Rows -> listState.scrollToItem(0)
            }
        }
    }

    Column(
        modifier = modifier
            .clip(PanelShape)
            .background(OrpheusColors.pickerSurface)
            .border(1.dp, OrpheusColors.pickerOutline, PanelShape)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Escape -> {
                        onDismiss()
                        true
                    }
                    Key.Enter, Key.NumPadEnter -> if (searchFocused) {
                        searchPick?.let(onPick)
                        true
                    } else {
                        false
                    }
                    Key.DirectionDown -> if (searchFocused) {
                        (entries.firstOrNull { it == state.current } ?: enterTarget)?.let(::focusEntry)
                        true
                    } else {
                        false
                    }
                    else -> false
                }
            }
            // Arrows nobody used move between chips and entries; desktop has no arrow traversal of its own.
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val direction = when (event.key) {
                    Key.DirectionLeft -> FocusDirection.Left
                    Key.DirectionRight -> FocusDirection.Right
                    Key.DirectionUp -> FocusDirection.Up
                    Key.DirectionDown -> FocusDirection.Down
                    else -> {
                        // A printable key on a tile or chip lands in the search as typed text.
                        val code = event.utf16CodePoint
                        if (searchFocused || event.isCtrlPressed || event.isMetaPressed || event.isAltPressed ||
                            code <= 0x20 || code == 0x7f
                        ) {
                            return@onKeyEvent false
                        }
                        state.query += code.toChar().toString()
                        runCatching { searchFocus.requestFocus() }
                        return@onKeyEvent true
                    }
                }
                // Consumed even at an edge: an unused arrow would reach window hooks (the DJ app skips vibes).
                focusManager.moveFocus(direction)
                true
            }
            .focusRequester(rootFocus)
            .focusable()
            .padding(10.dp),
    ) {
        SearchField(
            query = state.query,
            onQueryChange = { state.query = it },
            onSearch = { searchPick?.let(onPick) },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(searchFocus)
                .onFocusChanged { searchFocused = it.isFocused }
                .testTag("$tag-search"),
        )
        Spacer(Modifier.height(8.dp))
        ChipRow(state = state, tag = tag)
        Spacer(Modifier.height(4.dp))

        val emptyText = when {
            state.query.isNotBlank() -> "No engines match"
            state.recent -> "Nothing picked yet"
            else -> "No engines here"
        }
        when (layout) {
            PickerLayout.Tiles -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 92.dp),
                state = gridState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                items.forEach { item ->
                    when (item) {
                        is PickerItem.Header -> item(key = "h:${item.text}", span = { GridItemSpan(maxLineSpan) }) {
                            SectionHeader(item.text)
                        }
                        is PickerItem.Entry -> item(key = EngineCatalog.key(item.choice)) {
                            EngineTile(
                                choice = item.choice,
                                selected = item.choice == state.current,
                                enterTarget = searchFocused && item.choice == searchPick,
                                focusRequest = focusOn(item.choice),
                                onClick = { onPick(item.choice) },
                                modifier = Modifier.testTag("$tag-tile-${EngineCatalog.key(item.choice)}"),
                            )
                        }
                    }
                }
                if (items.isEmpty()) {
                    item(key = "empty", span = { GridItemSpan(maxLineSpan) }) { EmptyState(emptyText) }
                }
            }
            PickerLayout.Rows -> LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                items.forEach { item ->
                    when (item) {
                        is PickerItem.Header -> item(key = "h:${item.text}") { SectionHeader(item.text) }
                        is PickerItem.Entry -> item(key = EngineCatalog.key(item.choice)) {
                            EngineRow(
                                choice = item.choice,
                                selected = item.choice == state.current,
                                focusRequest = focusOn(item.choice),
                                onClick = { onPick(item.choice) },
                                modifier = Modifier.testTag("$tag-row-${EngineCatalog.key(item.choice)}"),
                            )
                        }
                    }
                }
                if (items.isEmpty()) item(key = "empty") { EmptyState(emptyText) }
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = TextStyle(color = OrpheusColors.pickerText, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
        cursorBrush = SolidColor(OrpheusColors.pickerMint),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = modifier,
        decorationBox = { inner ->
            Row(
                modifier = Modifier
                    .clip(FieldShape)
                    .background(OrpheusColors.pickerField)
                    .border(1.dp, OrpheusColors.pickerOutline, FieldShape)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Default.Search, contentDescription = null, tint = OrpheusColors.pickerMuted, modifier = Modifier.size(14.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            text = "search engines & DX patches",
                            color = OrpheusColors.pickerMuted,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
            }
        },
    )
}

/** One sideways row that never wraps, fading at the right edge while more chips lie past it. */
@Composable
private fun ChipRow(state: EnginePickerState, tag: String) {
    val rowState = rememberLazyListState()
    val selectedIndex = when {
        state.recent -> 0
        state.category == null -> 1
        else -> 2 + state.categories.indexOf(state.category)
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        val last = rowState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@LaunchedEffect
        if (selectedIndex >= last) rowState.scrollToItem((selectedIndex - 1).coerceAtLeast(0))
    }
    LazyRow(
        state = rowState,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            // Desktop lazy rows ignore mouse drags; touch drags are taken by the row's own scroll first.
            .draggable(
                state = rememberDraggableState { delta -> rowState.dispatchRawDelta(-delta) },
                orientation = Orientation.Horizontal,
            )
            .drawWithContent {
                drawContent()
                if (rowState.canScrollForward) {
                    val fade = 28.dp.toPx()
                    drawRect(
                        brush = Brush.horizontalGradient(
                            listOf(Color.Transparent, OrpheusColors.pickerSurface),
                            startX = size.width - fade,
                            endX = size.width,
                        ),
                        topLeft = Offset(size.width - fade, 0f),
                        size = Size(fade, size.height),
                    )
                }
            },
    ) {
        item(key = "RECENT") {
            Chip("★ Recent", on = state.recent, tag = "$tag-chip-RECENT") {
                state.query = ""
                state.selectRecent()
            }
        }
        item(key = "ALL") {
            Chip("All", on = !state.recent && state.category == null, tag = "$tag-chip-ALL") {
                state.query = ""
                state.selectCategory(null)
            }
        }
        state.categories.forEach { category ->
            item(key = category.name) {
                Chip(category.label, on = !state.recent && state.category == category, tag = "$tag-chip-${category.name}") {
                    state.query = ""
                    state.selectCategory(category)
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, on: Boolean, tag: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        text = label,
        color = if (on) OrpheusColors.pickerSurface else OrpheusColors.pickerChipText,
        fontSize = 11.sp,
        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (on) OrpheusColors.pickerMint else Color.Transparent)
            .border(
                1.dp,
                when {
                    focused -> OrpheusColors.pickerText
                    on -> OrpheusColors.pickerMint
                    else -> OrpheusColors.pickerOutline
                },
                CircleShape,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .semantics { selected = on }
            .testTag(tag)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        color = OrpheusColors.pickerSection,
        fontSize = 10.sp,
        letterSpacing = 1.sp,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 3.dp),
    )
}

@Composable
private fun EmptyState(text: String) {
    Text(
        text = text,
        color = OrpheusColors.pickerMuted,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 8.dp),
    )
}

@Composable
private fun CategoryDot(choice: EngineChoice, size: Int = 6) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(engineCategoryColor(EngineCatalog.category(choice.engine))),
    )
}

@Composable
private fun EngineTile(
    choice: EngineChoice,
    selected: Boolean,
    enterTarget: Boolean,
    focusRequest: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val outline = when {
        focused -> OrpheusColors.pickerText
        selected -> OrpheusColors.pickerGold
        enterTarget -> OrpheusColors.pickerMint
        else -> OrpheusColors.pickerTileOutline
    }
    Column(
        modifier = modifier
            .padding(3.dp)
            .height(44.dp)
            .fillMaxWidth()
            .drawBehind {
                if (selected) {
                    val r = 7.dp.toPx()
                    drawRoundRect(
                        color = OrpheusColors.pickerGold.copy(alpha = 0.25f),
                        topLeft = Offset(-2.dp.toPx(), -2.dp.toPx()),
                        size = Size(size.width + 4.dp.toPx(), size.height + 4.dp.toPx()),
                        cornerRadius = CornerRadius(r + 2.dp.toPx()),
                    )
                }
            }
            .clip(TileShape)
            .background(OrpheusColors.pickerField)
            .border(if (selected || focused) 1.5.dp else 1.dp, outline, TileShape)
            .focusWhenAsked(focusRequest)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CategoryDot(choice, size = 5)
            Text(
                text = entryTitle(choice),
                color = OrpheusColors.pickerText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = entrySubtitle(choice),
            color = OrpheusColors.pickerMuted,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EngineRow(
    choice: EngineChoice,
    selected: Boolean,
    focusRequest: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(
                when {
                    selected -> OrpheusColors.pickerSelectedRow
                    focused -> OrpheusColors.pickerField
                    else -> Color.Transparent
                },
            )
            .drawBehind {
                val rule = 1.dp.toPx()
                drawRect(OrpheusColors.pickerRule, Offset(0f, size.height - rule), Size(size.width, rule))
                if (selected) drawRect(OrpheusColors.pickerGold, Offset.Zero, Size(3.dp.toPx(), size.height))
            }
            .focusWhenAsked(focusRequest)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CategoryDot(choice, size = 8)
        Column(Modifier.weight(1f)) {
            Text(
                text = entryTitle(choice),
                color = OrpheusColors.pickerText,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entrySubtitle(choice),
                color = OrpheusColors.pickerMuted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Text("✓", color = OrpheusColors.pickerGold, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun previewState(): EnginePickerState {
    val choices = EngineCatalog.choices(EngineSlotKind.PULSAR, hasNative = true)
    val current = EngineCatalog.choiceFor(OrpheusEngineId.DX2, EngineCatalog.patches[32].harmonics)
    return EnginePickerState(choices, current, startCategory = null)
}

@Preview(name = "Engine picker popover, DX Patches")
@Composable
private fun EnginePickerPopoverPreview() {
    OrpheusTheme {
        EnginePickerPanel(
            state = remember { previewState() },
            layout = PickerLayout.Tiles,
            tag = "preview",
            initialFocus = PickerFocus.None,
            onPick = {},
            onDismiss = {},
            modifier = Modifier.width(460.dp).height(420.dp),
        )
    }
}

@Preview(name = "Engine picker sheet, phone")
@Composable
private fun EnginePickerSheetPreview() {
    OrpheusTheme {
        EnginePickerPanel(
            state = remember { previewState() },
            layout = PickerLayout.Rows,
            tag = "preview",
            initialFocus = PickerFocus.None,
            onPick = {},
            onDismiss = {},
            modifier = Modifier.width(320.dp).height(560.dp),
        )
    }
}

@Preview(name = "Engine picker, no matches")
@Composable
private fun EnginePickerEmptyPreview() {
    OrpheusTheme {
        EnginePickerPanel(
            state = remember { previewState().apply { query = "zzz" } },
            layout = PickerLayout.Tiles,
            tag = "preview",
            initialFocus = PickerFocus.None,
            onPick = {},
            onDismiss = {},
            modifier = Modifier.width(460.dp).height(200.dp),
        )
    }
}
