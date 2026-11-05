package org.balch.orpheus.features.cowbell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.core.plugin.symbols.CowbellSymbol
import org.balch.orpheus.plugins.cowbell.CowbellBody
import org.balch.orpheus.plugins.cowbell.CowbellFill
import org.balch.orpheus.plugins.cowbell.CowbellMode
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.panels.LocalPanelTopInset
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.widgets.DefaultValueFormatter
import org.balch.orpheus.ui.widgets.Learnable
import org.balch.orpheus.ui.widgets.RotaryKnob

internal val CowbellPanelColor = OrpheusColors.bellBrass
internal const val CowbellMoreTag = "cowbell-more"

/** The bell's share of the panel width; the controls take the rest. */
private const val BELL_WIDTH_FRACTION = 0.45f

/** The bell never gives up more than this much of the row to the controls. */
private const val MIN_BELL_FRACTION = 0.3f

/** Just under the 166dp the controls get in Orpheus's 320dp slot (a 308dp row); the bell yields below this. */
private val CONTROLS_MIN_WIDTH = 163.dp

/** The gap between the bell and the controls, which the two shares do not divide. */
private val BELL_CONTROLS_GAP = 6.dp

/**
 * The bell's share of a [rowWidth] row: [BELL_WIDTH_FRACTION], less when the controls would fall
 * under [CONTROLS_MIN_WIDTH] scaled by [fontScale] (their labels grow with it). A 320dp or wider
 * panel at font scale 1 never gets here, so Orpheus keeps its proportions.
 */
internal fun bellWidthFraction(rowWidth: Dp, fontScale: Float): Float {
    if (rowWidth <= 0.dp) return BELL_WIDTH_FRACTION
    val room = 1f - CONTROLS_MIN_WIDTH.value * fontScale / (rowWidth - BELL_CONTROLS_GAP).value
    return minOf(BELL_WIDTH_FRACTION, room).coerceAtLeast(MIN_BELL_FRACTION)
}

private val FILL_ROW_HEIGHT = 22.dp

/** Four pills share the controls' width, and JINGLES is seven letters. */
private val FILL_LABEL_SIZE = 9.sp

@Composable
fun CowbellPanel(
    feature: CowbellFeature,
    modifier: Modifier = Modifier,
    outLevel: StateFlow<Float> = NoLevel,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    showCollapsedHeader: Boolean = true,
    moreOpenByDefault: Boolean = true,
) {
    CowbellPanelContent(
        feature = feature,
        modifier = modifier,
        outLevel = outLevel,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        showCollapsedHeader = showCollapsedHeader,
        moreOpenByDefault = moreOpenByDefault,
    )
}

/** [CowbellPanel] with the seams previews and tests need. */
@Composable
internal fun CowbellPanelContent(
    feature: CowbellFeature,
    modifier: Modifier = Modifier,
    outLevel: StateFlow<Float> = NoLevel,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    showCollapsedHeader: Boolean = true,
    moreOpenByDefault: Boolean = true,
) {
    val state = feature.stateFlow.collectAsStateWithLifecycle()
    // Everything but the bend, which moves at drag rate: only the bell's draw phase reads that.
    val controls by remember(state) {
        derivedStateOf(structuralEqualityPolicy()) { state.value.copy(bend = 0f) }
    }
    val actions = feature.actions
    val morphStyle by feature.morphStyle.collectAsStateWithLifecycle()
    val navInset = LocalPanelTopInset.current
    val density = LocalDensity.current
    // Zero until the row first measures: one frame at the default share, then it settles.
    var rowWidthPx by remember { mutableIntStateOf(0) }
    val bellFraction = bellWidthFraction(with(density) { rowWidthPx.toDp() }, density.fontScale)
    var moreOpen by rememberSaveable { mutableStateOf(moreOpenByDefault) }
    // The VM's scope outlives the panel, so leaving composition is what lets go of a held note.
    DisposableEffect(actions) {
        onDispose { actions.releaseAll() }
    }

    CollapsibleColumnPanel(
        title = "BELL",
        // The strip already names the panel; with it hidden, the panel names itself here.
        expandedTitle = if (!showCollapsedHeader) "BELL" else null,
        color = CowbellPanelColor,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = false,
        modifier = modifier,
        showCollapsedHeader = showCollapsedHeader,
        fillHeight = false,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(start = 6.dp, end = 6.dp, top = navInset)
                .onSizeChanged { rowWidthPx = it.width },
            horizontalArrangement = Arrangement.spacedBy(BELL_CONTROLS_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier.weight(bellFraction).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CowbellBodySelector(
                    body = controls.body,
                    onBody = actions.onBody,
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    CowbellBell3D(
                        mode = controls.mode,
                        actions = actions,
                        bend = { state.value.bend },
                        fill = controls.fill,
                        motionOn = controls.motion,
                        outLevel = outLevel,
                        morphStyle = morphStyle,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // The look of a mode change is an extra: it shows with MORE, under the bell, where
                    // it costs the controls no height (a 260 dp slot has none to spare).
                    if (moreOpen) {
                        MorphStylePill(morphStyle, feature::setMorphStyle, Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
            CowbellControls(
                state = controls,
                actions = actions,
                moreOpen = moreOpen,
                onMoreToggle = { moreOpen = !moreOpen },
                modifier = Modifier.weight(1f - bellFraction),
            )
        }
    }
}

@Composable
private fun CowbellControls(
    state: CowbellUiState,
    actions: CowbellPanelActions,
    moreOpen: Boolean,
    onMoreToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LocalControlsCompositionProbe.current?.invoke()
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The fill row belongs to the mode row, so the two sit closer than the rest.
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ModeRow(mode = state.mode, onMode = actions.onMode)
            FillRow(fill = state.fill, enabled = state.mode == CowbellMode.SHAKE, onFill = actions.onFill)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CowbellKnob(state.tone, actions.onTone, "TONE", CowbellSymbol.TONE)
            CowbellKnob(state.ring, actions.onRing, "RING", CowbellSymbol.RING)
            CowbellKnob(state.intensity, actions.onIntensity, "INTENSITY", CowbellSymbol.INTENSITY)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GridPill(on = state.grid, onToggle = actions.onGrid)
            MoreToggle(open = moreOpen, onToggle = onMoreToggle)
        }
        AnimatedVisibility(
            visible = moreOpen,
            enter = fadeIn(tween(300)) + expandVertically(tween(300)),
            exit = fadeOut(tween(200)) + shrinkVertically(tween(200)),
        ) {
            MoreRow(state, actions)
        }
    }
}

/** OCTAVE · SNAP · LEVEL. */
@Composable
private fun MoreRow(state: CowbellUiState, actions: CowbellPanelActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CowbellKnob(
            value = state.octave.toFloat(),
            onValueChange = { actions.onOctave(it.roundToInt()) },
            label = "OCTAVE",
            symbol = CowbellSymbol.OCTAVE,
            range = -2f..2f,
            bipolar = true,
            formatter = { v -> v.roundToInt().let { if (it > 0) "+$it" else "$it" } },
        )
        CowbellKnob(state.gridStrength, actions.onGridStrength, "SNAP", CowbellSymbol.GRID_STRENGTH, longLabel = "GRID STRENGTH")
        CowbellKnob(state.level, actions.onLevel, "LEVEL", CowbellSymbol.LEVEL)
    }
}

/** The pills' colours. A selection set by MIDI, AI or a preset still reads while its row is dimmed. */
@Composable
internal fun cowbellPillColors() = SegmentedButtonDefaults.colors(
    activeContainerColor = CowbellPanelColor,
    activeContentColor = OrpheusColors.deepPurple,
    activeBorderColor = CowbellPanelColor,
    inactiveContainerColor = OrpheusColors.deepPurple,
    inactiveContentColor = CowbellPanelColor,
    inactiveBorderColor = CowbellPanelColor.copy(alpha = 0.6f),
    disabledInactiveContainerColor = OrpheusColors.deepPurple.copy(alpha = 0.5f),
    disabledInactiveContentColor = CowbellPanelColor.copy(alpha = 0.35f),
    disabledInactiveBorderColor = CowbellPanelColor.copy(alpha = 0.25f),
    disabledActiveContainerColor = CowbellPanelColor.copy(alpha = 0.35f),
    disabledActiveContentColor = OrpheusColors.deepPurple.copy(alpha = 0.7f),
    disabledActiveBorderColor = CowbellPanelColor.copy(alpha = 0.35f),
)

/** TAP · SHAKE · HOLD (the SYNTH mode). */
@Composable
private fun ModeRow(mode: CowbellMode, onMode: (CowbellMode) -> Unit) {
    PillRow(
        entries = CowbellMode.entries,
        selected = mode,
        onSelect = onMode,
        controlId = CowbellSymbol.MODE.controlId.key,
        height = 28.dp,
        label = { it.label.uppercase() },
    )
}

/** MARBLE · BEADS · SEEDS · JINGLES. Always shown, and live only while SHAKE is the mode. */
@Composable
private fun FillRow(fill: CowbellFill, enabled: Boolean, onFill: (CowbellFill) -> Unit) {
    PillRow(
        entries = CowbellFill.entries,
        selected = fill,
        onSelect = onFill,
        controlId = CowbellSymbol.FILL.controlId.key,
        height = FILL_ROW_HEIGHT,
        enabled = enabled,
        labelStyle = MaterialTheme.typography.labelSmall.copy(fontSize = FILL_LABEL_SIZE),
        sidePadding = 0.dp,
    )
}

/** [label] defaults to the entry's `toString`, an enum's name. [learnable] false keeps the row out of MIDI learn. */
@Composable
internal fun <T> PillRow(
    entries: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    controlId: String,
    height: Dp,
    enabled: Boolean = true,
    labelStyle: TextStyle = MaterialTheme.typography.labelSmall,
    sidePadding: Dp = 2.dp,
    label: (T) -> String = { it.toString() },
    learnable: Boolean = true,
) {
    val colors = cowbellPillColors()
    val row = @Composable {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().height(height)) {
            entries.forEachIndexed { index, entry ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = entries.size),
                    onClick = { onSelect(entry) },
                    selected = selected == entry,
                    enabled = enabled,
                    colors = colors,
                    icon = {},
                    contentPadding = PaddingValues(horizontal = sidePadding),
                ) {
                    Text(text = label(entry), style = labelStyle, maxLines = 1)
                }
            }
        }
    }
    if (learnable) Learnable(controlId = controlId) { row() } else row()
}

/** [size] is the dial's diameter; the DJ panel runs it smaller than Orpheus's 40dp. */
@Composable
internal fun CowbellKnob(
    value: Float,
    onValueChange: (Float) -> Unit,
    label: String,
    symbol: CowbellSymbol,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    bipolar: Boolean = false,
    longLabel: String? = null,
    formatter: ((Float) -> String)? = null,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    labelStyle: TextStyle = MaterialTheme.typography.labelSmall,
    valueStyle: TextStyle = MaterialTheme.typography.labelMedium,
) {
    RotaryKnob(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = label,
        labelStyle = labelStyle,
        valueStyle = valueStyle,
        controlId = symbol.controlId.key,
        range = range,
        size = size,
        trackColor = OrpheusColors.deepPurple,
        progressColor = CowbellPanelColor,
        knobColor = lerp(CowbellPanelColor, OrpheusColors.deepPurple, 0.6f),
        indicatorColor = OrpheusColors.neonCyan,
        labelColor = CowbellPanelColor,
        bipolar = bipolar,
        longLabel = longLabel,
        valueFormatter = formatter ?: DefaultValueFormatter,
    )
}

@Composable
private fun GridPill(on: Boolean, onToggle: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(percent = 50)
    Learnable(controlId = CowbellSymbol.GRID.controlId.key) {
        Box(
            modifier = Modifier
                .height(28.dp)
                .clip(shape)
                .background(if (on) CowbellPanelColor else OrpheusColors.deepPurple)
                .border(1.dp, CowbellPanelColor.copy(alpha = if (on) 1f else 0.6f), shape)
                .toggleable(value = on, role = Role.Switch, onValueChange = onToggle)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "GRID",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (on) OrpheusColors.deepPurple else CowbellPanelColor,
                maxLines = 1,
            )
        }
    }
}

/** A 28dp chip with a 40dp hit area. */
@Composable
private fun MoreToggle(open: Boolean, onToggle: () -> Unit) {
    val label = if (open) "Fewer controls" else "More controls"
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onToggle)
            .testTag(CowbellMoreTag),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(OrpheusColors.deepPurple)
                .border(1.dp, CowbellPanelColor.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = label,
                tint = CowbellPanelColor,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Counts compositions of the controls column, for the bend recomposition test. */
internal val LocalControlsCompositionProbe = staticCompositionLocalOf<(() -> Unit)?> { null }

@Preview(widthDp = 320, heightDp = 330)
@Preview(widthDp = 320, heightDp = 330, name = "140%", fontScale = 1.4f)
@Composable
fun CowbellPanelPreview() {
    OrpheusTheme {
        CowbellPanel(
            feature = CowbellViewModel.previewFeature(CowbellUiState(grid = true)),
            isExpanded = true,
            showCollapsedHeader = false,
        )
    }
}

@Preview(widthDp = 320, heightDp = 330, name = "SYNTH, more closed")
@Composable
private fun CowbellPanelSynthPreview() {
    OrpheusTheme {
        CowbellPanelContent(
            feature = CowbellViewModel.previewFeature(CowbellUiState(mode = CowbellMode.SYNTH)),
            isExpanded = true,
            showCollapsedHeader = false,
            moreOpenByDefault = false,
        )
    }
}

@Preview(widthDp = 320, heightDp = 330, name = "Sitar")
@Composable
private fun CowbellPanelSitarPreview() {
    OrpheusTheme {
        CowbellPanel(
            feature = CowbellViewModel.previewFeature(CowbellUiState(body = CowbellBody.SITAR)),
            isExpanded = true,
            showCollapsedHeader = false,
        )
    }
}

@Preview(widthDp = 320, heightDp = 260, name = "320x260")
@Composable
private fun CowbellPanelShortPreview() {
    OrpheusTheme {
        CowbellPanel(
            feature = CowbellViewModel.previewFeature(),
            isExpanded = true,
            showCollapsedHeader = false,
        )
    }
}
