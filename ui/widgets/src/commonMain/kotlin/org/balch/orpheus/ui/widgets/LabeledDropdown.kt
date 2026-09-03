package org.balch.orpheus.ui.widgets

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.theme.lighten
import org.balch.orpheus.ui.theme.proportional

/** Insets of the value, and so the dropdown's height: nothing here has an explicit height. */
private val DropdownHorizontalPadding: Dp = 12.dp
private val DropdownVerticalPadding: Dp = 8.dp

/**
 * Width floor for a dropdown whose value changes under the user, so tapping through the options
 * doesn't resize it and shove its neighbours around.
 *
 * Only for those. Everything else sizes to its own value: a shared width wastes space on the short
 * ones and pushes a row into wrapping when it would otherwise fit on one line.
 */
val DropdownCycleMinWidth: Dp = 64.dp

/**
 * Size of the menu arrow, and so the floor on every value row's height.
 *
 * The arrow is taller than the value text, so it alone sets the height of a menu-backed dropdown.
 * Applying it as a minimum here keeps the menu-less ones (Pulsar's ENV, ENDING) from sitting a few
 * dp shorter than the neighbours they sit beside.
 */
val DropdownIconSize: Dp = 16.dp

private val DropdownCornerRadius: Dp = 6.dp
private val DropdownLabelGap: Dp = 2.dp
private val DropdownLabelFontSize: TextUnit = 9.sp
private val DropdownValueFontSize: TextUnit = 11.sp

private val DropdownBackground: Color get() = OrpheusColors.darkVoid.copy(alpha = 0.6f)

/**
 * A caption over a tappable rounded surface, the shape every selector in the app wears.
 *
 * [EnumDropdown] is the common case, but some of these open no menu at all: Pulsar's ENV cycles
 * envelope modes in place and its ENDING opens a settings sheet. Those were hand-rolled copies,
 * which is how ENDING drifted to a 2dp radius and 6/2 insets while the menu-backed ones wore 6dp
 * and 12/8. Routing all three through here keeps that from happening again.
 *
 * Sizes to its own value, never to a shared width. [DropdownValueText] keeps that value on one
 * line, which is the part that matters: a `Row` measures each child against what the earlier ones
 * left over, and without it the last one wraps and grows taller instead of staying put. Cap a
 * value whose length is open-ended so its row's width stays predictable.
 *
 * [content] goes in the [Box] rather than a pre-built row so a caller can anchor a popup to the
 * surface itself. [EnumDropdown] puts its menu here, lining it up with the outer bounds rather
 * than the padded interior.
 *
 * @param background fill behind the value. Pulsar's ENDING tints it while the outro is armed, and
 *   [EnumDropdown] animates it for its highlight.
 * @param minWidth width floor including padding. Unset for the usual case; see
 *   [DropdownCycleMinWidth].
 * @param onLongClick optional long press. Pulsar's VIBE triggers the manual anomaly with it and
 *   ENDING arms the outro.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LabeledDropdown(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    labelColor: Color = OrpheusColors.cosmicPurple.lighten(),
    background: Color = DropdownBackground,
    minWidth: Dp = Dp.Unspecified,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.proportional(),
            color = labelColor,
            fontSize = DropdownLabelFontSize,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )

        Spacer(Modifier.height(DropdownLabelGap))

        Box(
            // Order matters. Clip bounds the press ripple to the rounded corners, and the width
            // floor and padding sit below the click handler so the whole surface stays tappable.
            // The height floor goes below the padding so it sizes the value, not the whole
            // surface, and lands on the same total height as a dropdown carrying an arrow.
            modifier = Modifier
                .clip(RoundedCornerShape(DropdownCornerRadius))
                .background(background)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .defaultMinSize(minWidth = minWidth)
                .padding(
                    horizontal = DropdownHorizontalPadding,
                    vertical = DropdownVerticalPadding,
                )
                .defaultMinSize(minHeight = DropdownIconSize),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

/**
 * The value inside a [LabeledDropdown], single-line by construction.
 *
 * `maxLines = 1` is the point. Without it, one measured against leftover width in a `Row` wraps
 * and grows taller, which is how Pulsar's ENV used to double in height on a long vibe name.
 */
@Composable
fun DropdownValueText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        modifier = modifier,
        text = text,
        color = color,
        fontWeight = FontWeight.Bold,
        fontSize = DropdownValueFontSize,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
    )
}

@Preview
@Composable
private fun LabeledDropdownPreview() {
    OrpheusTheme {
        Box(Modifier.background(OrpheusColors.blackHoleBackground).padding(16.dp)) {
            LabeledDropdown(
                label = "ENV",
                onClick = {},
                minWidth = DropdownCycleMinWidth,
            ) {
                DropdownValueText(text = "WAVES", color = OrpheusColors.cosmicPurple)
            }
        }
    }
}
