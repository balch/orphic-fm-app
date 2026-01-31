package org.balch.orpheus.features.ai.chat.widgets

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import org.jetbrains.compose.resources.painterResource
import orpheus.composeapp.generated.resources.Res
import orpheus.composeapp.generated.resources.orpheus_avatar

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
                    ChatMessageType.Loading -> LoadingIndicator(message.text)
                    else -> {
                        Column {
                            if (message.type != ChatMessageType.User) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(bottom = 2.dp)
                                ) {
                                    when (message.type) {
                                        ChatMessageType.Agent -> {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Image(
                                                    painter = painterResource(Res.drawable.orpheus_avatar),
                                                    contentDescription = "Orpheus",
                                                    modifier = Modifier
                                                        .size(24.dp)
                                                        .clip(CircleShape)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = "Orpheus",
                                                    style = typography.labelMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = OrpheusColors.metallicBlue // Vibrant Blue label
                                                )
                                            }
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
 * Animated loading indicator for thinking/switching state.
 * Shows pulsing dots with optional message text.
 */
@Composable
private fun LoadingIndicator(text: String = "Thinking...") {
    val infiniteTransition = rememberInfiniteTransition(label = "loading")
    
    // Staggered pulsing animation for each dot
    val dot1Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot1"
    )
    val dot2Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot2"
    )
    val dot3Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot3"
    )
    
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Orpheus avatar
        Image(
            painter = painterResource(Res.drawable.orpheus_avatar),
            contentDescription = "Orpheus",
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .padding(end = 2.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        
        // Message text
        Text(
            text = text,
            style = typography.bodyMedium,
            fontSize = 13.sp,
            color = OrpheusColors.sterlingSilver.copy(alpha = 0.9f)
        )
        
        Spacer(modifier = Modifier.width(8.dp))
        
        // Animated dots
        Box(
            modifier = Modifier
                .size(6.dp)
                .alpha(dot1Alpha)
                .clip(CircleShape)
                .background(OrpheusColors.metallicBlue)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .size(6.dp)
                .alpha(dot2Alpha)
                .clip(CircleShape)
                .background(OrpheusColors.metallicBlue)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .size(6.dp)
                .alpha(dot3Alpha)
                .clip(CircleShape)
                .background(OrpheusColors.metallicBlue)
        )
    }
}
