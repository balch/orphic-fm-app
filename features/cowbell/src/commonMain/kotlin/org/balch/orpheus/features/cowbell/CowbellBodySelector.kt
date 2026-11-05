package org.balch.orpheus.features.cowbell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.core.plugin.symbols.CowbellSymbol
import org.balch.orpheus.plugins.cowbell.CowbellBody
import org.balch.orpheus.ui.widgets.Learnable

internal const val CowbellBodyTag = "cowbell-body"

/** The selector sits over the bell in every panel, big enough to hit while playing. */
internal val CowbellSelectorHeight = 34.dp

/** A wide bell column doesn't stretch it into a bar, and its menu opens the same width. */
internal val CowbellSelectorMaxWidth = 120.dp

private val LABEL_SIZE = 13.sp

/** The body's name on a chip, with all four bodies in its menu. */
@Composable
internal fun CowbellBodySelector(
    body: CowbellBody,
    onBody: (CowbellBody) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(percent = 50)
    val style = MaterialTheme.typography.labelSmall.copy(fontSize = LABEL_SIZE)
    Learnable(CowbellSymbol.BODY.controlId.key, modifier.widthIn(max = CowbellSelectorMaxWidth).fillMaxWidth()) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CowbellSelectorHeight)
                    .clip(shape)
                    .background(CowbellPanelColor.copy(alpha = 0.15f))
                    .border(1.dp, CowbellPanelColor.copy(alpha = 0.45f), shape)
                    .clickable(role = Role.DropdownList) { expanded = true }
                    .testTag(CowbellBodyTag)
                    .padding(start = 12.dp, end = 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = body.fullName(), style = style, color = CowbellPanelColor, maxLines = 1)
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Select body",
                    tint = CowbellPanelColor,
                    modifier = Modifier.size(20.dp),
                )
            }
            CowbellChoiceMenu(
                expanded = expanded,
                entries = CowbellBody.entries,
                selected = body,
                label = { it.fullName() },
                style = style,
                onPick = onBody,
                onDismiss = { expanded = false },
                modifier = Modifier.width(CowbellSelectorMaxWidth),
            )
        }
    }
}

private fun CowbellBody.fullName(): String = when (this) {
    CowbellBody.STRING -> "String"
    CowbellBody.SITAR -> "Sitar"
    CowbellBody.METAL -> "Metal"
    CowbellBody.SYNTH -> "Synth"
}
