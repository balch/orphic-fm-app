package org.balch.orpheus.features.ai.generative

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.Flow
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme

/**
 * A rolling carousel for displaying Drone Agent status messages.
 * 
 * Features:
 * - Up/down navigation arrows to browse message history
 * - Rolling animation when new messages arrive
 * - Animated loading indicator when drone is processing
 * - Subtle gradient background
 */
@Composable
fun AiStatusCarousel(
    statusMessages: Flow<AiStatusMessage>,
    isActive: Boolean,
    sessionId: Int, // Add session ID to trigger clears
    modifier: Modifier = Modifier
) {
    // Collect messages into a list
    val messages = remember(sessionId) { mutableStateListOf<AiStatusMessage>() }
    var currentIndex by remember(sessionId) { mutableStateOf(0) }
    
    // Collect new messages
    LaunchedEffect(statusMessages, sessionId) {
        messages.clear()
        statusMessages.collect { message ->
            // If new message is not loading, remove previous loading message
            if (!message.isLoading && messages.isNotEmpty() && messages[0].isLoading) {
                messages.removeAt(0)
            }
            messages.add(0, message) // Add to front
            currentIndex = 0 // Reset to newest
        }
    }
    
    // Snapshot the batch outside AnimatedContent so we don't read mutableStateListOf inside the transition
    val batch = messages.asSequence().drop(currentIndex).take(4).toList()

    if (messages.isEmpty()) {
        return
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        OrpheusColors.midnightBlue.copy(alpha = 0.15f),
                        OrpheusColors.midnightBlue.copy(alpha = 0.05f),
                        OrpheusColors.midnightBlue.copy(alpha = 0.15f),
                    )
                )
            )
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .defaultMinSize(minHeight = 64.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Message text with rolling animation
            AnimatedContent(
                targetState = batch,
                transitionSpec = {
                    slideInVertically { height -> height } togetherWith
                            slideOutVertically { height -> -height }
                },
                modifier = Modifier.weight(1f),
                label = "drone_message"
            ) { animatedBatch ->
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (animatedBatch.isEmpty()) {
                         Text("AI ready", color = OrpheusColors.sterlingSilver.copy(alpha=0.5f), fontSize=12.sp)
                    } else {
                        animatedBatch.forEachIndexed { i, msg ->
                            val isPrimary = i == 0
                            Text(
                                text = if (msg.isReasoning) "\u2728 ${msg.text}" else msg.text,
                                style = MaterialTheme.typography.bodyMedium,
                                fontSize = if (isPrimary) 13.sp else 12.sp,
                                lineHeight = if (isPrimary) 18.sp else 14.sp,
                                fontStyle = FontStyle.Italic,
                                color = when {
                                    msg.isError -> MaterialTheme.colorScheme.error
                                    msg.isReasoning -> OrpheusColors.softPurple.copy(alpha = if (isPrimary) 0.85f else 0.5f)
                                    msg.isLoading -> OrpheusColors.sterlingSilver.copy(alpha = 0.9f)
                                    else -> if (isPrimary) OrpheusColors.metallicBlue else OrpheusColors.sterlingSilver.copy(alpha = 0.5f)
                                },
                                maxLines = if (isPrimary) 2 else 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            
            // Navigation arrows (only if we have history)
            if (messages.size > 1) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    // Up arrow (newer)
                    Text(
                        text = "▲",
                        fontSize = 12.sp,
                        color = if (currentIndex > 0) 
                            OrpheusColors.metallicBlue
                        else 
                            OrpheusColors.metallicBlue.copy(alpha = 0.3f),
                        modifier = Modifier
                            .clickable(enabled = currentIndex > 0) { 
                                if (currentIndex > 0) currentIndex-- 
                            }
                            .padding(horizontal = 4.dp)
                    )
                    
                    // Down arrow (older)
                    Text(
                        text = "▼",
                        fontSize = 12.sp,
                        color = if (currentIndex < messages.lastIndex) 
                            OrpheusColors.metallicBlue
                        else 
                            OrpheusColors.metallicBlue.copy(alpha = 0.3f),
                        modifier = Modifier
                            .clickable(enabled = currentIndex < messages.lastIndex) { 
                                if (currentIndex < messages.lastIndex) currentIndex++ 
                            }
                            .padding(horizontal = 4.dp)
                    )
                }
            }
        }
    }
}

private fun previewFlow(vararg messages: AiStatusMessage) = previewStatusFlow(*messages)

// === Previews ===

@Preview(widthDp = 400, heightDp = 120)
@Composable
private fun CarouselStatusPreview() {
    OrpheusTheme {
        Surface(color = OrpheusColors.darkVoid) {
            AiStatusCarousel(
                statusMessages = previewFlow(
                    AiStatusMessage("Weaving a tapestry of overtones across the harmonic series"),
                    AiStatusMessage("Shifting to Dorian mode with subtle detuning"),
                    AiStatusMessage("Introducing shimmer delay at 40% wet"),
                    AiStatusMessage("Initializing...", isLoading = true),
                ),
                isActive = true,
                sessionId = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
            )
        }
    }
}

@Preview(widthDp = 400, heightDp = 120)
@Composable
private fun CarouselReasoningPreview() {
    OrpheusTheme {
        Surface(color = OrpheusColors.darkVoid) {
            AiStatusCarousel(
                statusMessages = previewFlow(
                    AiStatusMessage("Considering whether Lydian mode would complement the current drone texture.", isReasoning = true),
                    AiStatusMessage("The user wants more movement, so I should increase flux spread.", isReasoning = true),
                    AiStatusMessage("Building tension with rising fifths"),
                ),
                isActive = true,
                sessionId = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
            )
        }
    }
}

@Preview(widthDp = 400, heightDp = 120)
@Composable
private fun CarouselErrorPreview() {
    OrpheusTheme {
        Surface(color = OrpheusColors.darkVoid) {
            AiStatusCarousel(
                statusMessages = previewFlow(
                    AiStatusMessage("Stream Error", isError = true),
                    AiStatusMessage("Attempting drone in C minor"),
                ),
                isActive = true,
                sessionId = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
            )
        }
    }
}

