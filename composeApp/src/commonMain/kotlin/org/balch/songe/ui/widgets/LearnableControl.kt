package org.balch.songe.ui.widgets

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import org.balch.songe.ui.theme.SongeColors

/**
 * State holder for MIDI learn mode functionality.
 * Provides a generic way for any control to participate in learn mode.
 */
data class LearnModeState(
    val isActive: Boolean = false,
    val selectedControlId: String? = null,
    val onSelectControl: (String) -> Unit = {}
) {
    /**
     * Check if a specific control is currently selected for learning.
     */
    fun isLearning(controlId: String): Boolean = isActive && selectedControlId == controlId
}

/**
 * CompositionLocal for providing learn mode state down the composition tree.
 * This allows any Learnable control to access the learn state without prop drilling.
 */
val LocalLearnModeState = compositionLocalOf { LearnModeState() }

/**
 * Provider composable that makes learn mode state available to all children.
 */
@Composable
fun LearnModeProvider(
    isActive: Boolean,
    selectedControlId: String?,
    onSelectControl: (String) -> Unit,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalLearnModeState provides LearnModeState(
            isActive = isActive,
            selectedControlId = selectedControlId,
            onSelectControl = onSelectControl
        )
    ) {
        content()
    }
}

/**
 * Wrapper composable that makes any content learnable.
 * In learn mode, clicking selects this control for MIDI mapping.
 * Shows visual feedback when selected.
 *
 * @param controlId Unique identifier for this control (e.g., "voice_0_tune", "delay_time_1")
 * @param content The actual control to render
 */
@Composable
fun Learnable(
    controlId: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val learnState = LocalLearnModeState.current
    val isLearning = learnState.isLearning(controlId)

    // Animate the learning indicator
    val borderAlpha by animateFloatAsState(
        targetValue = if (isLearning) 1f else 0f,
        animationSpec = tween(durationMillis = 200)
    )

    val glowAlpha by animateFloatAsState(
        targetValue = if (isLearning) 0.3f else 0f,
        animationSpec = tween(durationMillis = 200)
    )

    Box(
        modifier = modifier
            .then(
                if (learnState.isActive) {
                    Modifier
                        .padding(2.dp)  // Minimal padding for border visibility
                        .clip(RoundedCornerShape(4.dp))
                        .background(SongeColors.neonMagenta.copy(alpha = glowAlpha))
                        .border(
                            width = 2.dp,
                            color = SongeColors.neonMagenta.copy(alpha = borderAlpha),
                            shape = RoundedCornerShape(4.dp)
                        )
                        // Use detectTapGestures instead of clickable to not consume drag events
                        .pointerInput(controlId, learnState.isActive, learnState.selectedControlId) {
                            detectTapGestures(
                                onTap = {
                                    println("[Learnable] Tap detected on: $controlId, calling onSelectControl")
                                    learnState.onSelectControl(controlId)
                                }
                            )
                        }
                } else {
                    Modifier
                }
            )
    ) {
        content()
    }
}

/**
 * Modifier extension that applies learnable behavior.
 * Uses detectTapGestures to only capture taps, allowing drags through.
 */
fun Modifier.learnable(
    controlId: String,
    learnState: LearnModeState
): Modifier {
    if (!learnState.isActive) return this

    val isLearning = learnState.isLearning(controlId)

    return this
        .padding(2.dp)  // Minimal padding for border visibility
        .clip(RoundedCornerShape(4.dp))
        .background(
            if (isLearning) SongeColors.neonMagenta.copy(alpha = 0.25f)
            else Color.Transparent
        )
        .border(
            width = if (isLearning) 2.dp else 1.dp,
            color = if (isLearning) SongeColors.neonMagenta else SongeColors.neonMagenta.copy(alpha = 0.2f),
            shape = RoundedCornerShape(4.dp)
        )
        // Use pointerInput with detectTapGestures to not consume drag events
        // Key includes learnState.isActive so handler updates when state changes
        .pointerInput(controlId, learnState.isActive, learnState.selectedControlId) {
            detectTapGestures(
                onTap = {
                    println("[LearnableControl] Tap detected on: $controlId, calling onSelectControl")
                    learnState.onSelectControl(controlId)
                }
            )
        }
}
