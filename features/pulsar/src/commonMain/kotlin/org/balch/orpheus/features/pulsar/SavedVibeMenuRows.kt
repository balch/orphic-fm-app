package org.balch.orpheus.features.pulsar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.balch.orpheus.features.pulsar.playback.SavedVibe
import org.balch.orpheus.features.pulsar.playback.savedVibeDate
import org.balch.orpheus.ui.theme.OrpheusColors

private val MutedText = Color(0xFF8F86B8)

/**
 * The VIBE menus' saved AI vibes under the catalog (this panel's and the DJ top bar's), in EnumDropdown's row
 * metrics (48dp rows, 12dp inset, labelLarge). A tap plays one and closes the menu; the trash asks inside the row.
 */
fun LazyListScope.savedVibeMenuRows(
    saved: List<SavedVibe>,
    playing: String,
    nowMillis: Long,
    color: Color,
    onPick: (SavedVibe) -> Unit,
    onDelete: (String) -> Unit,
    dismiss: () -> Unit,
) {
    if (saved.isEmpty()) return
    item(key = "§ai") {
        Text(
            "AI VIBES · ${saved.size}", style = MaterialTheme.typography.labelSmall, color = MutedText,
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
        )
    }
    items(saved, key = { "§ai:" + it.name.lowercase() }) { vibe ->
        // Row-local, so closing the menu drops an unanswered ask.
        var confirming by remember { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(enabled = !confirming) { onPick(vibe); dismiss() }
                .padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val label = MaterialTheme.typography.labelLarge
            if (confirming) {
                Text("Delete ${vibe.name}?", style = label, color = Color.White, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("Delete", style = label, color = OrpheusColors.recordRed,
                    modifier = Modifier.clickable { onDelete(vibe.name) }.padding(horizontal = 8.dp, vertical = 14.dp))
                Text("Keep", style = label, color = MutedText,
                    modifier = Modifier.clickable { confirming = false }.padding(start = 8.dp, end = 12.dp, top = 14.dp, bottom = 14.dp))
            } else {
                Text(vibe.name, style = label, color = if (vibe.name.equals(playing, ignoreCase = true)) color else Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(savedVibeDate(vibe.savedAtMillis, nowMillis), style = MaterialTheme.typography.labelSmall,
                    color = MutedText, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
                IconButton(onClick = { confirming = true }) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete ${vibe.name}", tint = MutedText,
                        modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
