package org.balch.orpheus.ui.widgets.engine

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.core.audio.EngineCatalog
import org.balch.orpheus.core.audio.EngineCategory
import org.balch.orpheus.core.audio.EngineChoice
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.ui.infrastructure.LocalTelevisionHardware
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.widgets.popover.AnchoredPopoverOrSheet

private val PopoverWidth = 460.dp
private val PopoverHeight = 420.dp
private const val DEFAULT_TAG = "engine-picker"

/** Opens an [EnginePicker] from outside its pill, e.g. a locked DX knob opening DX Patches. */
@Stable
class EnginePickerController {
    var isOpen: Boolean by mutableStateOf(false)
        private set
    internal var requestedCategory: EngineCategory? by mutableStateOf(null)
        private set
    // Bumped on each open so an open while already open starts a fresh picker on the new chip.
    internal var session: Int by mutableIntStateOf(0)
        private set

    /** Opens on [category] when the slot offers it, else on the slot's usual chip. */
    fun open(category: EngineCategory? = null) {
        requestedCategory = category
        session++
        isOpen = true
    }

    fun close() {
        isOpen = false
    }
}

@Composable
fun rememberEnginePickerController(): EnginePickerController = remember { EnginePickerController() }

private data class EnginePickerTag(val tag: String) : Modifier.Element

/** Tags the pill [tag]; the open picker tags `"$tag-search"`, `"$tag-chip-<NAME>"`, `"$tag-tile-<key>"`, `"$tag-row-<key>"`. */
fun Modifier.enginePickerTestTag(tag: String): Modifier = this then EnginePickerTag(tag) then Modifier.testTag(tag)

/**
 * A pill naming [current] that opens a searchable picker over [choices]: a tile popover on wide
 * windows, a list sheet on narrow ones. A pick is recorded in [LocalEngineRecents], passed to
 * [onPick], and closes the picker. [showExternalSelection] (an AI highlight) lights the pill
 * without opening it. [compact] drops the dot and caret for tight spots. [pillLabel] replaces the
 * pill's text, for a spot that names the patch elsewhere (the Duo panel shows "DX2" over its knob).
 */
@Composable
fun EnginePicker(
    current: EngineChoice,
    choices: List<EngineChoice>,
    onPick: (EngineChoice) -> Unit,
    color: Color,
    modifier: Modifier = Modifier,
    startCategory: EngineCategory? = null,
    compact: Boolean = false,
    pillHeight: Dp = 28.dp,
    labelStyle: TextStyle? = null,
    glowEnergy: Float = 0f,
    showExternalSelection: Boolean = false,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    controller: EnginePickerController = rememberEnginePickerController(),
    pillLabel: String? = null,
) {
    val tag = modifier.foldIn<String?>(null) { found, element -> (element as? EnginePickerTag)?.tag ?: found }
        ?: DEFAULT_TAG

    val open = controller.isOpen
    val latestOnExpanded by rememberUpdatedState(onExpandedChange)
    var reportedOpen by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (open != reportedOpen) {
            reportedOpen = open
            latestOnExpanded?.invoke(open)
        }
    }
    // Leaving composition while open closes, so a hoisted controller does not reopen it later.
    DisposableEffect(controller) {
        onDispose {
            if (controller.isOpen) {
                controller.close()
                latestOnExpanded?.invoke(false)
            }
        }
    }

    Box(modifier = modifier, propagateMinConstraints = true) {
        EnginePill(
            current = current,
            label = pillLabel ?: EngineCatalog.labelFor(current),
            color = color,
            compact = compact,
            pillHeight = pillHeight,
            labelStyle = labelStyle,
            glowEnergy = glowEnergy,
            highlighted = showExternalSelection || open,
            onClick = { controller.open() },
        )
        if (open) {
            key(controller.session) {
                EnginePickerOverlay(
                    current = current,
                    choices = choices,
                    onPick = onPick,
                    startCategory = startCategory,
                    tag = tag,
                    controller = controller,
                )
            }
        }
    }
}

/**
 * The open picker. Window size, input mode, TV and recents are read here, outside the popup, so a
 * closed pill does not recompose on a resize or another pill's pick. Wide windows get tiles in a
 * popover, narrow ones a list sheet ([AnchoredPopoverOrSheet]).
 */
@Composable
private fun EnginePickerOverlay(
    current: EngineChoice,
    choices: List<EngineChoice>,
    onPick: (EngineChoice) -> Unit,
    startCategory: EngineCategory?,
    tag: String,
    controller: EnginePickerController,
) {
    val recents = LocalEngineRecents.current
    val recentKeys by recents.keys.collectAsState()

    val initialFocus = when {
        LocalTelevisionHardware.current -> PickerFocus.Current
        LocalInputModeManager.current.inputMode == InputMode.Touch -> PickerFocus.None
        else -> PickerFocus.Search
    }

    val pick: (EngineChoice) -> Unit = { choice ->
        recents.record(choice)
        onPick(choice)
        controller.close()
    }
    val state = remember {
        EnginePickerState(choices, current, startCategory).apply {
            controller.requestedCategory?.takeIf { it in categories }?.let { selectCategory(it) }
            this.recentKeys = recentKeys
        }
    }
    LaunchedEffect(recentKeys) { state.recentKeys = recentKeys }

    AnchoredPopoverOrSheet(
        onDismiss = controller::close,
        popoverWidth = PopoverWidth,
        popoverHeight = PopoverHeight,
    ) { sheet, sizeModifier ->
        EnginePickerPanel(
            state = state,
            layout = if (sheet) PickerLayout.Rows else PickerLayout.Tiles,
            tag = tag,
            initialFocus = initialFocus,
            onPick = pick,
            onDismiss = controller::close,
            modifier = sizeModifier,
        )
    }
}

@Composable
private fun EnginePill(
    current: EngineChoice,
    label: String,
    color: Color,
    compact: Boolean,
    pillHeight: Dp,
    labelStyle: TextStyle?,
    glowEnergy: Float,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    val glow by animateFloatAsState(glowEnergy.coerceIn(0f, 1f), tween(120))
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier = Modifier
            .height(pillHeight)
            .drawBehind {
                if (glow > 0.01f) {
                    val spread = 6.dp.toPx() * glow
                    drawRoundRect(
                        color = color.copy(alpha = 0.35f * glow),
                        topLeft = Offset(-spread, -spread),
                        size = Size(size.width + spread * 2, size.height + spread * 2),
                        cornerRadius = CornerRadius(size.height / 2 + spread),
                    )
                }
            }
            .clip(shape)
            .background(color.copy(alpha = if (highlighted) 0.32f else 0.15f))
            .border(if (highlighted) 1.5.dp else 1.dp, color.copy(alpha = if (highlighted) 0.9f else 0.45f), shape)
            .clickable(role = Role.Button, onClickLabel = "Pick engine", onClick = onClick)
            .padding(horizontal = if (compact) 6.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
    ) {
        if (!compact) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(engineCategoryColor(EngineCatalog.category(current.engine))),
            )
        }
        Text(
            text = label,
            style = labelStyle ?: TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (!compact) {
            Text("▾", color = color.copy(alpha = 0.8f), fontSize = 10.sp)
        }
    }
}

@Preview(name = "Engine picker pills")
@Composable
private fun EnginePickerPillPreview() {
    OrpheusTheme {
        Column(
            modifier = Modifier.background(OrpheusColors.pickerSurface).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val dx = EngineCatalog.choiceFor(OrpheusEngineId.DX2, EngineCatalog.patches[32].harmonics)
            EnginePicker(EngineChoice.Engine(OrpheusEngineId.VA), emptyList(), {}, OrpheusColors.neonMagenta)
            EnginePicker(dx, emptyList(), {}, OrpheusColors.warmGlow, glowEnergy = 0.6f)
            EnginePicker(dx, emptyList(), {}, OrpheusColors.neonCyan, showExternalSelection = true)
            EnginePicker(dx, emptyList(), {}, OrpheusColors.neonCyan, compact = true, modifier = Modifier.width(72.dp))
        }
    }
}
