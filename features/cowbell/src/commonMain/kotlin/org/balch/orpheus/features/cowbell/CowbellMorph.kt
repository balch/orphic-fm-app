package org.balch.orpheus.features.cowbell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.balch.orpheus.ui.theme.OrpheusColors

/** How the bell changes camera between modes. Saved by name in `AppPreferences.bellMorph`. */
enum class BellMorphStyle {
    /** Every face bursts into a 4x4 grid of shards that fly out and re-form. */
    SHARDS,

    /** The bell melts into a chrome pool and back. */
    LIQUID;

    fun next(): BellMorphStyle = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromName(name: String?): BellMorphStyle = entries.firstOrNull { it.name == name } ?: SHARDS
    }
}

internal const val CowbellMorphTag = "cowbell-morph"

/** Shows the morph style; a tap moves to the next one. */
@Composable
internal fun MorphStylePill(style: BellMorphStyle, onStyle: (BellMorphStyle) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier = modifier
            .height(24.dp)
            .clip(shape)
            .background(OrpheusColors.deepPurple)
            .border(1.dp, CowbellPanelColor.copy(alpha = 0.6f), shape)
            .clickable(role = Role.Button, onClickLabel = "Next morph") { onStyle(style.next()) }
            .semantics { contentDescription = "Mode change morph: ${style.name}" }
            .testTag(CowbellMorphTag)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = style.name,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = CowbellPanelColor,
            maxLines = 1,
        )
    }
}
