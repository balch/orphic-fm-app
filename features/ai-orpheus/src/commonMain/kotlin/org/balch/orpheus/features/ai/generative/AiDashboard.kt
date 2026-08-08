package org.balch.orpheus.features.ai.generative

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.Flow
import org.balch.orpheus.features.ai.chat.widgets.ChatInputField
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.theme.proportional

@Composable
fun AiDashboard(
    inputLog: Flow<AiStatusMessage>,
    controlLog: Flow<AiStatusMessage>,
    statusMessages: Flow<AiStatusMessage>,
    isActive: Boolean,
    sessionId: Int, // Add session ID to trigger clears
    isSoloMode: Boolean = false,
    onSendInfluence: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Top: Sensory Inputs
        LogPanel(
            title = "SENSORY INPUTS",
            flow = inputLog,
            sessionId = sessionId,
            modifier = Modifier.fillMaxWidth().weight(1f)
        )

        // Middle: Synth Controls
        LogPanel(
            title = "SYNTH CONTROLS",
            flow = controlLog,
            sessionId = sessionId,
            modifier = Modifier.fillMaxWidth().weight(1f),
            showVisuals = true
        )

        // Bottom: Status Carousel
        AiStatusCarousel(
            statusMessages = statusMessages,
            isActive = isActive,
            sessionId = sessionId,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    OrpheusColors.midnightBlue.copy(alpha = 0.1f),
                    shape = MaterialTheme.shapes.medium
                )
                .clip(MaterialTheme.shapes.medium)
        )

        ChatInputField(
            isEnabled = true,
            onSendMessage = onSendInfluence,
            placeholder = if (isSoloMode) "Influence Solo..." else "Influence Drone...",
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        )
    }
}

@Composable
internal fun LogPanel(
    title: String,
    flow: Flow<AiStatusMessage>,
    sessionId: Int, // Key to clear logs
    modifier: Modifier = Modifier,
    showVisuals: Boolean = false
) {
    val messages = remember(sessionId) { mutableStateListOf<AiStatusMessage>() }

    // Logic: 
    // 1. When sessionId changes, CLEAR valid messages.
    // 2. Start collecting from flow (which should be fresh due to flatMapLatest in VM).
    // Note: flow object itself might be stable but its content changes.
    // Since we removed shareIn, collecting it connects to the current agent's flow.
    LaunchedEffect(flow, sessionId) {
        messages.clear()
        flow.collect { msg ->
            messages.add(0, msg)
            if (messages.size > 100) {
                messages.removeRange(100, messages.size)
            }
        }
    }

    Column(
        modifier = modifier
            .background(
                OrpheusColors.midnightBlue.copy(alpha = 0.1f),
                shape = MaterialTheme.shapes.medium
            )
            .padding(4.dp)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium.proportional(),
                color = OrpheusColors.metallicBlue,
                fontWeight = FontWeight.Black,
                fontSize = 12.sp,
                letterSpacing = 1.5.sp
            )
        }

        // Content
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                if (showVisuals && msg.text.startsWith("Set ")) {
                    ControlItem(msg.text)
                } else {
                    Text(
                        text = msg.text,
                        style = MaterialTheme.typography.bodySmall.proportional(),
                        color = if (msg.isError) MaterialTheme.colorScheme.error 
                               else OrpheusColors.sterlingSilver.copy(alpha = 0.85f),
                        fontSize = 11.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * A visually interesting way to show a synth control change.
 * parses "Set NAME: 0.XX"
 */
@Composable
private fun ControlItem(text: String) {
    val parts = text.substringAfter("Set ").split(": ")
    if (parts.size < 2) {
        Text(text, fontSize = 11.sp, color = OrpheusColors.sterlingSilver)
        return
    }
    
    val name = parts[0]
    val valueStr = parts[1]
    val value = valueStr.toFloatOrNull() ?: 0f
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name.replace("_", " "),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = OrpheusColors.sterlingSilver.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Value text (now outside and clear)
            Text(
                text = valueStr,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = OrpheusColors.metallicBlue,
                modifier = Modifier.defaultMinSize(minWidth = 28.dp),
                textAlign = TextAlign.End
            )

            // Segmented LED Bar
            Row(
                modifier = Modifier
                    .size(width = 50.dp, height = 8.dp)
                    .background(OrpheusColors.midnightBlue.copy(alpha = 0.4f), MaterialTheme.shapes.extraSmall)
                    .padding(1.dp),
                horizontalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                val segmentCount = 10
                for (i in 0 until segmentCount) {
                    val threshold = i.toFloat() / segmentCount
                    val isOn = value > threshold
                    
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(
                                if (isOn) OrpheusColors.metallicBlue
                                else OrpheusColors.metallicBlue.copy(alpha = 0.1f)
                            )
                    )
                }
            }
        }
    }
}

// === Previews ===

private fun previewFlow(vararg messages: AiStatusMessage) = previewStatusFlow(*messages)

@Preview(widthDp = 400, heightDp = 500)
@Composable
private fun DashboardDronePreview() {
    OrpheusTheme {
        Surface(color = OrpheusColors.darkVoid) {
            AiDashboard(
                inputLog = previewFlow(
                    AiStatusMessage("Initializing Drone..."),
                    AiStatusMessage("User adjusted: Drive: 0.45"),
                    AiStatusMessage("Mood: Ethereal Drift"),
                ),
                controlLog = previewFlow(
                    AiStatusMessage("Set voice_tune_0: 0.56"),
                    AiStatusMessage("Set distortion_drive: 0.45"),
                    AiStatusMessage("Set delay_feedback: 0.70"),
                    AiStatusMessage("Pattern: d1 $ slow 2 note \"c3 e3 g3\""),
                    AiStatusMessage("Failed: Unknown sound: glitch", isError = true),
                ),
                statusMessages = previewFlow(
                    AiStatusMessage("Weaving harmonic textures in C Dorian"),
                    AiStatusMessage("Considering a shift to minor pentatonic.", isReasoning = true),
                    AiStatusMessage("Adding shimmer delay at 40% wet"),
                ),
                isActive = true,
                sessionId = 1,
                modifier = Modifier.height(500.dp)
            )
        }
    }
}

@Preview(widthDp = 400, heightDp = 500)
@Composable
private fun DashboardSoloPreview() {
    OrpheusTheme {
        Surface(color = OrpheusColors.darkVoid) {
            AiDashboard(
                inputLog = previewFlow(
                    AiStatusMessage("User direction: Play something jazzy"),
                    AiStatusMessage("Mood: Midnight Jazz"),
                ),
                controlLog = previewFlow(
                    AiStatusMessage("Set voice_duo_engine_0: 10"),
                    AiStatusMessage("Set beats_bpm: 95"),
                    AiStatusMessage("Set beats_run: 1.0"),
                    AiStatusMessage("Pattern: d1 $ note \"c3 eb3 g3 bb3\""),
                ),
                statusMessages = previewFlow(
                    AiStatusMessage("Laying down a walking bass line"),
                    AiStatusMessage("The jazz voicings need more tension — adding a b9.", isReasoning = true),
                ),
                isActive = true,
                sessionId = 1,
                isSoloMode = true,
                modifier = Modifier.height(500.dp)
            )
        }
    }
}

@Preview(widthDp = 400, heightDp = 160)
@Composable
private fun LogPanelControlsPreview() {
    OrpheusTheme {
        Surface(color = OrpheusColors.darkVoid) {
            LogPanel(
                title = "SYNTH CONTROLS",
                flow = previewFlow(
                    AiStatusMessage("Set distortion_drive: 0.45"),
                    AiStatusMessage("Set delay_feedback: 0.70"),
                    AiStatusMessage("Set voice_tune_0: 0.56"),
                    AiStatusMessage("Set resonator_mix: 0.35"),
                    AiStatusMessage("Pattern: d2 $ s \"bd sn hh\""),
                    AiStatusMessage("Failed: Unknown control", isError = true),
                ),
                sessionId = 1,
                showVisuals = true,
                modifier = Modifier.fillMaxWidth().height(160.dp)
            )
        }
    }
}
