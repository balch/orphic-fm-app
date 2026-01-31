package org.balch.orpheus.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.ui.theme.OrpheusColors

/**
 * A compact button that cycles through a list of values when clicked.
 */
@Composable
fun <T> ValueCycleButton(
    value: T,
    values: List<T>,
    onValueChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    labelProvider: (T) -> String = { it.toString() },
    color: Color = OrpheusColors.warmGlow,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .width(36.dp)
            .height(24.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.1f))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
            .clickable(enabled = enabled) {
                val currentIndex = values.indexOf(value)
                val nextIndex = (currentIndex + 1) % values.size
                onValueChange(values[nextIndex])
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = labelProvider(value),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            ),
            color = if (enabled) color else color.copy(alpha = 0.4f),
            maxLines = 1
        )
    }
}
