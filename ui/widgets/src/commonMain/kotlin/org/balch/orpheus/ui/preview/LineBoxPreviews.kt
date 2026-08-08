package org.balch.orpheus.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.theme.proportional

/**
 * Living proof for the two text-metric rules this codebase keeps relearning.
 *
 * These render the failure and the fix side by side rather than describing them, because both
 * bugs are invisible in prose and in a default preview: one needs the line box painted to be
 * seen at all, the other only appears above a font scale previews never reach.
 */

/** Paints the line box behind the glyphs, so wasted leading is visible rather than inferred. */
@Composable
private fun LineBoxSpecimen(caption: String, style: TextStyle) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = caption,
            fontSize = 8.sp,
            color = OrpheusColors.sterlingSilver.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "MIX",
            style = style,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = OrpheusColors.neonCyan,
            modifier = Modifier.background(OrpheusColors.metallicBlue.copy(alpha = 0.35f)),
        )
    }
}

/**
 * Left box is labelSmall's fixed 16.sp line height wrapped around 9.sp glyphs -- a 1.78x box,
 * most of it empty. Right box is the same style through [proportional]. The tinted background
 * is the line box; the difference between the two is what used to push panels apart and clip
 * text in tight containers.
 */
// Deliberately single-scale: this specimen is about the line box, not about scaling.
@Preview
@Composable
private fun ProportionalLineBoxPreview() {
    OrpheusTheme {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            LineBoxSpecimen("labelSmall", MaterialTheme.typography.labelSmall)
            LineBoxSpecimen("+ proportional()", MaterialTheme.typography.labelSmall.proportional())
        }
    }
}

/**
 * A tight `dp` container holding `sp` text, rendered at both font scales.
 *
 * In the 100% frame the two badges are indistinguishable -- which is exactly why this bug
 * shipped. In the 140% frame, `A` (a hard `Modifier.size(12.dp)`) is cut through the middle
 * while `B` (`sizeIn(min...)`) grows instead of clipping. This is the pattern every panel
 * preview now follows: a second `@Preview` carrying `fontScale`, so each scale gets its own
 * correctly-sized frame.
 */
@Preview
@Preview(name = "140%", fontScale = 1.4f)
@Composable
private fun TightContainerAtLargestFontPreview() {
    OrpheusTheme {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(OrpheusColors.metallicBlue.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("A", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = OrpheusColors.neonCyan)
            }
            Box(
                modifier = Modifier
                    .sizeIn(minWidth = 12.dp, minHeight = 12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(OrpheusColors.metallicBlue.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("B", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = OrpheusColors.neonCyan)
            }
        }
    }
}
