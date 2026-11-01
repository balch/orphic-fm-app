package org.balch.orpheus.ui.widgets.patch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.balch.orpheus.core.audio.patch.PatchDest

/** Wraps a knob and, when [modulated], pins a small dot on its corner tagged `patch-dot-<dest><tagSuffix>`. */
@Composable
fun ModDotBox(
    dest: PatchDest,
    modulated: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    tagSuffix: String = "",
    content: @Composable () -> Unit,
) {
    Box(modifier) {
        content()
        if (modulated) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(color)
                    .testTag("patch-dot-${dest.name}$tagSuffix")
            )
        }
    }
}
