package org.balch.orpheus.features.cowbell

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import org.balch.orpheus.ui.theme.OrpheusColors

/** A dropdown of [entries] under its chip. */
@Composable
internal fun <T> CowbellChoiceMenu(
    expanded: Boolean,
    entries: List<T>,
    selected: T,
    label: (T) -> String,
    style: TextStyle,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier,
        containerColor = OrpheusColors.deepPurple,
    ) {
        for (entry in entries) {
            DropdownMenuItem(
                text = {
                    Text(
                        text = label(entry),
                        style = style,
                        color = CowbellPanelColor,
                        fontWeight = if (entry == selected) FontWeight.Bold else FontWeight.Normal,
                    )
                },
                onClick = {
                    onPick(entry)
                    onDismiss()
                },
            )
        }
    }
}
