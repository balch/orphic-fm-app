package org.balch.orpheus.features.ai.chat.widgets

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable
import org.balch.orpheus.ui.theme.OrpheusColors
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Types of chat messages in the AI conversation.
 */
enum class ChatMessageType {
    User,
    Agent,
    Loading,
    Error,
}

/**
 * Represents a chat message in the AI conversation.
 */
@OptIn(ExperimentalTime::class)
@Serializable
data class ChatMessage(
    val id: String = randomId(),
    val text: String,
    val type: ChatMessageType,
    val timestamp: Long = currentTimeMillis(),
)

/**
 * Platform-independent UUID generation.
 */
private fun randomId(): String = 
    (0..7).map { "0123456789abcdef"[Random.nextInt(16)] }.joinToString("")

/**
 * Platform-independent timestamp.
 */
private fun currentTimeMillis(): Long =
    Clock.System.now().toEpochMilliseconds()

@Composable
fun ChatMessageType.textColor(): Color = when (this) {
    ChatMessageType.User -> OrpheusColors.sterlingSilver
    ChatMessageType.Agent -> OrpheusColors.sterlingSilver
    ChatMessageType.Loading -> OrpheusColors.sterlingSilver.copy(alpha = 0.7f)
    ChatMessageType.Error -> OrpheusColors.sterlingSilver
}

@Composable
fun ChatMessageType.containerColor(): Color = when (this) {
    // User bubbles: Midnight Blue
    ChatMessageType.User -> OrpheusColors.midnightBlue.copy(alpha = 0.85f)
    // Agent bubbles: Deeper Space Blue for glass effect
    ChatMessageType.Agent -> OrpheusColors.deepSpaceBlue.copy(alpha = 0.75f)
    ChatMessageType.Loading -> OrpheusColors.deepSpaceBlue.copy(alpha = 0.5f)
    ChatMessageType.Error -> MaterialTheme.colorScheme.errorContainer
}

/**
 * Secondary accent color for gradient/highlight effects.
 */
@Composable
fun ChatMessageType.accentColor(): Color = when (this) {
    ChatMessageType.User -> OrpheusColors.sterlingSilver.copy(alpha = 0.3f)
    ChatMessageType.Agent -> OrpheusColors.metallicBlue.copy(alpha = 0.3f)
    ChatMessageType.Loading -> OrpheusColors.slateSilver.copy(alpha = 0.3f)
    ChatMessageType.Error -> MaterialTheme.colorScheme.error.copy(alpha = 0.3f)
}
