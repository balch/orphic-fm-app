package org.balch.orpheus.djapp.playlist

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.balch.orpheus.djapp.MarqueeLabel
import org.balch.orpheus.djapp.StageSheet
import org.balch.orpheus.djapp.phoneBarOverhang
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.models.Album
import org.balch.orpheus.features.pulsar.playback.PlaylistEdit
import org.balch.orpheus.features.pulsar.playback.PlaylistView
import org.balch.orpheus.ui.theme.OrpheusColors
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

internal const val PlaylistTitle = "Magic ∞"

/** How much bigger the title's ∞ draws than its letters: at text size the glyph is only letter-high. */
private const val InfinityScale = 1.3f

/** Lowers the enlarged ∞, in its own ascents, so its middle sits on the lowercase letters' middle. */
private val InfinityDrop = BaselineShift(-0.1f)

/** The phrase in the header, and over the stage as it flies there. */
internal val PhraseBlue = Color(0xFF9FB3FF)
private val MutedText = Color(0xFF8F86B8)
private val BadgeBorder = Color(0xFF4A4A6A)
private val RowDivider = Color(0xFF231A40)

/**
 * The dome's long-press sheet, over the feature's playlist view and the playing vibe: the side sheet
 * with the rail and the dock, and in portrait a panel in the stage that stops under the phone bar's
 * raised ring and caption. Kept composed while closed so it can slide out.
 */
@Composable
internal fun PlaylistSheet(pulsar: PulsarFeature, isLandscape: Boolean, open: Boolean, onDismiss: () -> Unit) {
    StageSheet(isLandscape, open, onDismiss) {
        val view by pulsar.playlistFlow.collectAsStateWithLifecycle()
        val nav by pulsar.vibeNavFlow.collectAsStateWithLifecycle()
        val eightBall = LocalEightBall.current
        // The header's phrase line tells the reveal where it sits, for its own phrase to fly to, and
        // stays clear until that is landing. Built once per ball; read only in layout and draw.
        val phraseLine = remember(eightBall) {
            if (eightBall == null) Modifier
            else Modifier
                .onGloballyPositioned { line ->
                    val at = line.positionInRoot()
                    eightBall.anchorPhrase(at.x, at.y + line.size.height / 2f)
                }
                .graphicsLayer { alpha = eightBall.headerPhraseAlpha }
        }
        PlaylistContent(
            view = view,
            current = nav.currentName,
            phrase = eightBall?.phrase ?: EightBallPhrases.first(),
            phraseLine = phraseLine,
            onEdit = pulsar::editPlaylist,
            onShuffle = {
                pulsar.editPlaylist(PlaylistEdit.Shuffle)
                eightBall?.roll()
            },
            onPlay = pulsar::pickVibeByName,
            overhang = if (isLandscape) 0.dp else phoneBarOverhang(),
        )
    }
}

/**
 * NOW, Up next (≡ reorders, − sets aside) and Set aside (+ brings back), under the mini 8-ball, Shuffle
 * and the album chips (a tap queues that album). Stateless apart from a drag and a chip's lift. [overhang]
 * is how far chrome draws over the bottom edge: the list, Reset order with it, scrolls clear of it.
 * [phraseLine] rides the header's phrase, for the reveal to find and to hold back.
 */
@Composable
internal fun PlaylistContent(
    view: PlaylistView,
    current: String,
    phrase: String,
    onEdit: (PlaylistEdit) -> Unit,
    onShuffle: () -> Unit,
    onPlay: (String) -> Unit,
    modifier: Modifier = Modifier,
    overhang: Dp = 0.dp,
    phraseLine: Modifier = Modifier,
) {
    val sections = remember(view, current) { playlistSections(view, current) }
    val albumOf = remember(view.albums) { view.albums.flatMap { (album, names) -> names.map { it to album } }.toMap() }
    // The gesture callbacks read these, never the plain values, so a recomposition cannot leave them stale.
    val latest by rememberUpdatedState(sections)
    val latestOrder by rememberUpdatedState(view.rotation.order)
    // Up next is the rotation's, except under a drag: the rows then follow the finger, frozen against a
    // song change, and after the drop they hold until the edit lands so they never flash the old order.
    var drag by remember { mutableStateOf<UpNextDrag?>(null) }
    fun shown() = drag?.takeIf { it.holds(latest.upNext) }?.rows ?: latest.upNext
    fun beginDrag() = UpNextDrag(rows = shown(), from = latest.upNext)
    val rows = shown()
    // Rows held under a drag may include a vibe set aside meanwhile; it stays up there until the drop, keyed once.
    val setAside = sections.setAside.filterNot { it in rows }
    // Once the hold is over it is dropped for good, so the rotation returning to its old order shows as itself.
    LaunchedEffect(drag, sections.upNext) { if (drag?.holds(sections.upNext) == false) drag = null }
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        val held = drag?.takeIf { !it.dropped } ?: beginDrag()
        val a = held.rows.indexOf(from.key)
        val b = held.rows.indexOf(to.key)
        if (a >= 0 && b >= 0) drag = held.copy(rows = held.rows.toMutableList().apply { add(b, removeAt(a)) })
    }
    val canSetAside = rows.size > 1
    val wobble = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val lift = remember(scope) { AlbumLift(scope) }
    // Closing the sheet mid-lift still queues the album that was tapped.
    DisposableEffect(lift) { onDispose { lift.flush() } }
    val queueAlbum: (Album) -> Unit = { album ->
        lift.start(album, view.albums[album].orEmpty().toSet()) { onEdit(PlaylistEdit.QueueAlbum(album, latest.now)) }
        // The album lands at the top, so that is where to watch it land.
        if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0) {
            scope.launch { listState.animateScrollToItem(0) }
        }
    }

    Column(modifier.fillMaxSize()) {
        PlaylistHeader(phrase = phrase, phraseLine = phraseLine, wobble = { wobble.value }, onShuffle = {
            onShuffle()
            scope.launch {
                wobble.animateTo(0f, keyframes {
                    durationMillis = 500
                    -14f at 80
                    12f at 180
                    -8f at 280
                    5f at 380
                })
            }
        })
        AlbumChips(view.albums, confirming = lift.album, onQueue = queueAlbum)
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = overhang),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            item(key = NowKey) {
                NowRow(sections.now, albumOf[sections.now], Modifier.albumLift(sections.now in lift.names, lift::value))
            }
            item(key = NextHeaderKey) { SectionHeader("Up next · ${rows.size}", Modifier.animateItem(placementSpec = PlaceSpring)) }
            // A vibe keeps its name as its key in both sections, so a row travels between them.
            items(rows, key = { it }) { name ->
                ReorderableItem(
                    reorder, key = name, modifier = Modifier.albumLift(name in lift.names, lift::value),
                    animateItemModifier = Modifier.animateItem(placementSpec = PlaceSpring),
                ) { lifted ->
                    UpNextRow(
                        name = name,
                        album = albumOf[name],
                        lifted = lifted,
                        canSetAside = canSetAside,
                        handle = Modifier.draggableHandle(
                            onDragStarted = { drag = beginDrag() },
                            onDragStopped = {
                                val held = drag?.takeIf { !it.dropped } ?: beginDrag()
                                // Dropped where it started: even a same-slot edit could hop hidden set-aside slots.
                                val edit = if (held.rows == held.from) null else {
                                    dropEdit(held.rows, held.rows.indexOf(name), latest.now, latestOrder)
                                }
                                // Nothing to wait for unless an edit is coming that moves the rotation.
                                val awaiting = edit != null && latest.upNext == held.from
                                drag = if (awaiting) held.copy(dropped = true) else null
                                edit?.let(onEdit)
                            },
                        ),
                        onSetAside = { onEdit(PlaylistEdit.SetIncluded(name, false)) },
                        onPlay = { onPlay(name) },
                    )
                }
            }
            // The header and Reset order move with the rows, so a row never slides over one that jumped.
            item(key = AsideHeaderKey) {
                SectionHeader("Set aside · ${setAside.size}", Modifier.animateItem(placementSpec = PlaceSpring))
            }
            items(setAside, key = { it }) { name ->
                // Not a drop target: the library keeps a key it saw in Up next unless told otherwise.
                ReorderableItem(
                    reorder, key = name, enabled = false, modifier = Modifier.albumLift(name in lift.names, lift::value),
                    animateItemModifier = Modifier.animateItem(placementSpec = PlaceSpring),
                ) {
                    SetAsideRow(
                        name = name,
                        album = albumOf[name],
                        onRestore = { onEdit(PlaylistEdit.SetIncluded(name, true)) },
                        onPlay = { onPlay(name) },
                    )
                }
            }
            if (overhang > 0.dp) item(key = ResetKey) { ResetRow(onEdit, Modifier.animateItem(placementSpec = PlaceSpring)) }
        }
        // Pinned under the list, unless chrome overhangs the edge it would sit on.
        if (overhang <= 0.dp) ResetRow(onEdit)
    }
}

@Composable
private fun ResetRow(onEdit: (PlaylistEdit) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = { onEdit(PlaylistEdit.Reset) }) { Text("Reset order", color = MutedText) }
    }
}

// Vibe rows are keyed by name; these keys stay clear of any vibe name.
private const val NowKey = "§now"
private const val NextHeaderKey = "§next"
private const val AsideHeaderKey = "§aside"
private const val ResetKey = "§reset"

/** Up next under a drag: [rows] as dragged from [from]. Once [dropped] they hold only while the rotation is still [from]. */
private data class UpNextDrag(val rows: List<String>, val from: List<String>, val dropped: Boolean = false) {
    fun holds(upNext: List<String>) = !dropped || upNext == from
}

private const val LiftMillis = 200
private const val LiftScale = 1.04f
private val LiftElevation = 10.dp
private val LiftShape = RoundedCornerShape(10.dp)
private val LiftFill = Color(0xFF241344)
private val PlaceSpring = spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow, IntOffset.VisibilityThreshold)
// Long enough for PlaceSpring to carry a row across the sheet before the rows settle back down.
private const val PlaceMillis = 550L

/**
 * A chip tap's choreography: the album's rows lift, the edit goes out and they travel to their new
 * slots, then they settle. [names] changes only at the ends; [value] is read at draw time.
 */
@Stable
private class AlbumLift(private val scope: CoroutineScope) {
    private val progress = Animatable(0f)
    val value: Float get() = progress.value
    var names by mutableStateOf(emptySet<String>())
        private set
    var album by mutableStateOf<Album?>(null)
        private set
    private var job: Job? = null
    private var unsent: (() -> Unit)? = null

    fun start(album: Album, rows: Set<String>, send: () -> Unit) {
        flush()
        job?.cancel()
        // Rows still up from a tap just before rise on with these and settle together.
        names = names + rows
        this.album = album
        unsent = send
        job = scope.launch {
            progress.animateTo(1f, tween(LiftMillis))
            flush()
            delay(PlaceMillis)
            progress.animateTo(0f, tween(LiftMillis))
            names = emptySet()
            this@AlbumLift.album = null
        }
    }

    /** Sends a tapped album that has not gone out yet: a newer tap or the sheet closing must not lose it. */
    fun flush() {
        unsent?.also { unsent = null }?.invoke()
    }
}

/** Lifts a row while [on]: scaled up, shadowed and ringed in cyan by [progress], which is read only when drawing. */
private fun Modifier.albumLift(on: Boolean, progress: () -> Float): Modifier = if (!on) this else this
    .zIndex(1f)
    .graphicsLayer {
        val p = progress()
        scaleX = 1f + (LiftScale - 1f) * p
        scaleY = scaleX
        shadowElevation = LiftElevation.toPx() * p
        shape = LiftShape
        ambientShadowColor = OrpheusColors.neonCyan
        spotShadowColor = OrpheusColors.neonCyan
    }
    .drawBehind {
        val p = progress()
        if (p <= 0f) return@drawBehind
        val corner = CornerRadius(10.dp.toPx())
        drawRoundRect(LiftFill.copy(alpha = p), cornerRadius = corner)
        drawRoundRect(OrpheusColors.neonCyan.copy(alpha = 0.8f * p), cornerRadius = corner, style = Stroke(1.dp.toPx()))
    }

@Composable
private fun PlaylistHeader(phrase: String, phraseLine: Modifier, wobble: () -> Float, onShuffle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EightBall(EightBallFace.Die, 40.dp, Modifier.graphicsLayer { rotationZ = wobble() })
        Column(Modifier.weight(1f)) {
            val titleStyle = MaterialTheme.typography.titleMedium
            val title = remember(titleStyle.fontSize) {
                buildAnnotatedString {
                    PlaylistTitle.forEach { c ->
                        if (c != '∞') append(c) else withStyle(
                            SpanStyle(fontSize = titleStyle.fontSize * InfinityScale, fontWeight = FontWeight.Normal, baselineShift = InfinityDrop),
                        ) { append(c) }
                    }
                }
            }
            Text(title, style = titleStyle, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // The column's weight bounds the slot, so a phrase wider than it scrolls as the dome's name does.
            MarqueeLabel(
                "“$phrase”", MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic), phraseLine, color = PhraseBlue,
            )
        }
        OutlinedButton(onClick = onShuffle) {
            Icon(Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Shuffle")
        }
    }
}

/** One chip per album in the listing, in its order: a tap queues that album, and the chip glows cyan while its rows move. */
@Composable
private fun AlbumChips(listing: Map<Album, List<String>>, confirming: Album?, onQueue: (Album) -> Unit) {
    // One line that never wraps; it scrolls only if a large font scale overruns it.
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listing.keys.forEach { album ->
            val lit = album == confirming
            val glow by animateColorAsState(if (lit) OrpheusColors.neonCyan.copy(alpha = 0.22f) else Color.Transparent)
            SuggestionChip(
                onClick = { onQueue(album) },
                label = { Text(album.title) },
                colors = if (!lit) SuggestionChipDefaults.suggestionChipColors(containerColor = glow) else {
                    SuggestionChipDefaults.suggestionChipColors(containerColor = glow, labelColor = OrpheusColors.neonCyan)
                },
                border = if (!lit) SuggestionChipDefaults.suggestionChipBorder(enabled = true) else {
                    SuggestionChipDefaults.suggestionChipBorder(enabled = true, borderColor = OrpheusColors.neonCyan)
                },
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        title.uppercase(), color = MutedText, style = MaterialTheme.typography.labelSmall,
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun NowRow(name: String, album: Album?, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth()
            .background(Brush.horizontalGradient(listOf(OrpheusColors.neonCyan.copy(alpha = 0.14f), Color.Transparent)))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("♪ $name", color = OrpheusColors.neonCyan, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        AlbumBadge(album)
        Text("NOW", color = OrpheusColors.neonCyan, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun UpNextRow(
    name: String,
    album: Album?,
    lifted: Boolean,
    canSetAside: Boolean,
    handle: Modifier,
    onSetAside: () -> Unit,
    onPlay: () -> Unit,
) {
    // One column: ReorderableItem's Box would otherwise stack the divider over the row's top.
    Column {
        Row(
            Modifier.fillMaxWidth()
                .background(if (lifted) OrpheusColors.cosmicPurple.copy(alpha = 0.25f) else Color.Transparent)
                .padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.DragHandle, contentDescription = "Reorder $name", tint = MutedText,
                modifier = handle.padding(8.dp).size(20.dp))
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).clickable(onClick = onPlay).padding(vertical = 10.dp))
            AlbumBadge(album)
            IconButton(onClick = onSetAside, enabled = canSetAside) {
                Icon(Icons.Rounded.Remove, contentDescription = "Set aside $name")
            }
        }
        RowDividerLine()
    }
}

@Composable
private fun SetAsideRow(name: String, album: Album?, onRestore: () -> Unit, onPlay: () -> Unit) {
    // One column for the same reason as UpNextRow's.
    Column {
        Row(
            Modifier.fillMaxWidth().padding(start = 44.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                name, maxLines = 1, overflow = TextOverflow.Ellipsis, textDecoration = TextDecoration.LineThrough,
                modifier = Modifier.weight(1f).alpha(0.5f).clickable(onClick = onPlay).padding(vertical = 10.dp),
            )
            AlbumBadge(album)
            IconButton(onClick = onRestore) { Icon(Icons.Rounded.Add, contentDescription = "Bring back $name") }
        }
        RowDividerLine()
    }
}

@Composable
private fun AlbumBadge(album: Album?) {
    if (album == null) return
    Text(
        album.title, style = MaterialTheme.typography.labelSmall, color = MutedText, maxLines = 1,
        modifier = Modifier.padding(horizontal = 6.dp).border(1.dp, BadgeBorder, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
private fun RowDividerLine() {
    Spacer(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(RowDivider))
}
