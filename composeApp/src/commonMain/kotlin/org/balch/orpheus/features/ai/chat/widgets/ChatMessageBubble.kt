package org.balch.orpheus.features.ai.chat.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.DefaultMarkdownColors
import com.mikepenz.markdown.model.DefaultMarkdownTypography
import org.balch.orpheus.ui.theme.OrpheusColors

/**
 * Chat message bubble for AI conversations.
 * Renders agent responses as markdown with proper formatting.
 * Features a premium glass-like appearance with subtle gradient shine.
 */
@Composable
fun ChatMessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
) {
    val alignment = if (message.type == ChatMessageType.User) {
        Alignment.CenterEnd
    } else {
        Alignment.CenterStart
    }

    val bubbleShape = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomStart = if (message.type == ChatMessageType.User) 16.dp else 4.dp,
        bottomEnd = if (message.type == ChatMessageType.User) 4.dp else 16.dp
    )

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        // Outer container with gradient border for shine effect
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .padding(vertical = 2.dp)
                .clip(bubbleShape)
                .border(
                    width = 1.dp,
                    brush = if (message.type == ChatMessageType.User) {
                        Brush.verticalGradient(
                            colors = listOf(
                                OrpheusColors.sterlingSilver.copy(alpha = 0.8f),
                                OrpheusColors.sterlingSilver.copy(alpha = 0.3f),
                            )
                        )
                    } else {
                        Brush.verticalGradient(
                            colors = listOf(
                                OrpheusColors.slateSilver.copy(alpha = 0.3f),
                                OrpheusColors.slateSilver.copy(alpha = 0.1f),
                            )
                        )
                    },
                    shape = bubbleShape
                )
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            message.type.containerColor(),
                            message.type.containerColor().copy(alpha = message.type.containerColor().alpha * 0.8f),
                        )
                    )
                )
        ) {
            Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                when (message.type) {
                    ChatMessageType.Loading -> LoadingIndicator()
                    else -> {
                        Column {
                            if (message.type != ChatMessageType.User) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(bottom = 2.dp)
                                ) {
                                    when (message.type) {
                                        ChatMessageType.Agent -> {
                                            Text(
                                                text = "🎵 Orpheus",
                                                style = typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = OrpheusColors.metallicBlue // Vibrant Blue label
                                            )
                                        }
                                        ChatMessageType.Error -> {
                                            Text(
                                                text = "⚠️ Error",
                                                style = typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = message.type.textColor().copy(alpha = 0.8f)
                                            )
                                        }
                                        else -> {}
                                    }
                                }
                            }

                            // Use Markdown rendering for agent responses, plain text for user messages
                            if (message.type == ChatMessageType.Agent) {
                                Markdown(
                                    content = message.text,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = message.markdownColors(),
                                    typography = message.markdownTypography()
                                )
                            } else {
                                Text(
                                    text = message.text,
                                    style = typography.bodyMedium,
                                    fontSize = 13.sp,
                                    color = message.type.textColor()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Configure markdown colors based on message type.
 */
@Composable
private fun ChatMessage.markdownColors(): DefaultMarkdownColors {
    val textColor = type.textColor()
    val containerColor = type.containerColor()
    return remember(textColor, containerColor) {
        DefaultMarkdownColors(
            text = textColor,
            codeBackground = containerColor.copy(alpha = 0.5f),
            inlineCodeBackground = containerColor.copy(alpha = 0.5f),
            dividerColor = textColor.copy(alpha = 0.2f),
            tableBackground = containerColor,
        )
    }
}

/**
 * Configure markdown typography for consistent styling.
 */
@Composable
private fun ChatMessage.markdownTypography(): DefaultMarkdownTypography {
    val currentTypography = typography
    val containerColor = type.containerColor()
    return remember(currentTypography, containerColor) {
        DefaultMarkdownTypography(
            h1 = currentTypography.headlineLarge.copy(fontSize = 18.sp),
            h2 = currentTypography.headlineMedium.copy(fontSize = 16.sp),
            h3 = currentTypography.headlineSmall.copy(fontSize = 14.sp),
            h4 = currentTypography.titleLarge.copy(fontSize = 13.sp),
            h5 = currentTypography.titleMedium.copy(fontSize = 12.sp),
            h6 = currentTypography.titleSmall.copy(fontSize = 11.sp),
            text = currentTypography.bodyMedium.copy(fontSize = 13.sp),
            code = currentTypography.bodyMedium.copy(
                fontSize = 12.sp,
                background = containerColor.copy(alpha = 0.5f),
            ),
            inlineCode = currentTypography.bodySmall,
            paragraph = currentTypography.bodyMedium.copy(fontSize = 13.sp),
            ordered = currentTypography.bodyMedium.copy(fontSize = 13.sp),
            bullet = currentTypography.bodyMedium.copy(fontSize = 13.sp),
            list = currentTypography.bodyMedium.copy(fontSize = 13.sp),
            quote = currentTypography.bodyMedium.copy(fontSize = 13.sp),
            table = currentTypography.bodyMedium.copy(fontSize = 13.sp),
            textLink = TextLinkStyles()
        )
    }
}

/**
 * Simple loading indicator for thinking state.
 */
@Composable
private fun LoadingIndicator() {
    Text(
        text = "🎵 ∿ ∿ ∿",
        style = typography.bodyMedium,
        color = OrpheusColors.metallicBlue
    )
}
