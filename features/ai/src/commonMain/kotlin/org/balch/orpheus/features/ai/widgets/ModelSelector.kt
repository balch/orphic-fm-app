package org.balch.orpheus.features.ai.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.core.ai.AiModel
import org.balch.orpheus.ui.theme.OrpheusColors

/**
 * Compact model-picker dropdown shared by the Orpheus AI Options panel and the DJ AI panel.
 *
 * Emits a tappable pill (current model name, optional drop-down chevron) plus the anchored
 * [DropdownMenu] of [availableModels]. The two call sites differ only cosmetically, so the
 * chevron and horizontal padding are parameterized:
 * - Orpheus AiOptionsPanel: [showDropdownArrow] = true, 12.dp padding (chevron pushed right).
 * - DJ AI ConfigStrip: [showDropdownArrow] = false, 10.dp padding (bare text pill).
 */
@Composable
fun ModelSelector(
    selectedModel: AiModel,
    availableModels: List<AiModel>,
    onSelectModel: (AiModel) -> Unit,
    modifier: Modifier = Modifier,
    showDropdownArrow: Boolean = true,
    horizontalPadding: Dp = 12.dp,
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .background(
                OrpheusColors.midnightBlue.copy(alpha = 0.4f),
                shape = MaterialTheme.shapes.small,
            )
            .clickable { expanded = true }
            .padding(horizontal = horizontalPadding, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        // With the chevron, split text-left / arrow-right; otherwise the bare pill hugs its text.
        horizontalArrangement = if (showDropdownArrow) Arrangement.SpaceBetween else Arrangement.Start,
    ) {
        Text(
            text = selectedModel.displayName,
            color = OrpheusColors.sterlingSilver,
            fontSize = 11.sp,
            textAlign = TextAlign.Start,
        )

        if (showDropdownArrow) {
            Icon(
                imageVector = Icons.Rounded.ArrowDropDown,
                contentDescription = "Select",
                tint = OrpheusColors.sterlingSilver.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp),
            )
        }
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
        modifier = Modifier.background(OrpheusColors.panelSurface),
    ) {
        availableModels.forEach { model ->
            DropdownMenuItem(
                text = {
                    Text(
                        text = model.displayName,
                        fontSize = 12.sp,
                        color = OrpheusColors.pureWhite,
                    )
                },
                onClick = {
                    onSelectModel(model)
                    expanded = false
                },
            )
        }
    }
}
