package org.balch.orpheus.features.ai.generative

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.SharedFlow
import org.balch.orpheus.features.ai.chat.widgets.ChatInputField
import org.balch.orpheus.ui.theme.OrpheusColors

@Composable
fun AiDashboard(
    inputLog: SharedFlow<AiStatusMessage>,
    controlLog: SharedFlow<AiStatusMessage>,
    statusMessages: SharedFlow<AiStatusMessage>,
    isActive: Boolean,
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
            modifier = Modifier.fillMaxWidth().weight(1f)
        )

        // Middle: Synth Controls
        LogPanel(
            title = "SYNTH CONTROLS",
            flow = controlLog,
            modifier = Modifier.fillMaxWidth().weight(1f),
            showVisuals = true
        )

        // Bottom: Status Carousel
        AiStatusCarousel(
            statusMessages = statusMessages,
            isActive = isActive,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    OrpheusColors.midnightBlue.copy(alpha = 0.2f),
                    shape = MaterialTheme.shapes.medium
                )
                .clip(MaterialTheme.shapes.medium)
        )

        ChatInputField(
            isEnabled = true,
            onSendMessage = onSendInfluence,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        )
    }
}

/**
 * Input field for user to influence the Solo AI composition.
 */
@Composable
fun SoloInfluenceInput(
    onSendInfluence: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf("") }
    
    val submit = {
        if (text.isNotBlank()) {
            onSendInfluence(text.trim())
            text = ""
        }
    }
    
    Row(
        modifier = modifier
            .background(
                Brush.verticalGradient(
                    listOf(
                        OrpheusColors.midnightBlue.copy(alpha = 0.4f),
                        OrpheusColors.midnightBlue.copy(alpha = 0.2f)
                    )
                ),
                shape = MaterialTheme.shapes.medium
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Text input
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = TextStyle(
                color = OrpheusColors.sterlingSilver,
                fontSize = 12.sp,
                letterSpacing = 0.5.sp
            ),
            cursorBrush = SolidColor(OrpheusColors.metallicBlue),
            singleLine = true,
            modifier = Modifier
                .weight(1f)
                .background(
                    OrpheusColors.midnightBlue.copy(alpha = 0.3f),
                    shape = CircleShape
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            decorationBox = { innerTextField ->
                if (text.isEmpty()) {
                    Text(
                        text = "Influence: \"darker\", \"more rhythm\"...",
                        style = TextStyle(
                            color = OrpheusColors.sterlingSilver.copy(alpha = 0.4f),
                            fontSize = 12.sp
                        )
                    )
                }
                innerTextField()
            }
        )
        
        // Send button
        IconButton(
            onClick = submit,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(
                            OrpheusColors.metallicBlue.copy(alpha = 0.8f),
                            OrpheusColors.metallicBlue.copy(alpha = 0.4f)
                        )
                    )
                )
        ) {
            Text(
                text = "→",
                color = OrpheusColors.sterlingSilver,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun LogPanel(
    title: String,
    flow: SharedFlow<AiStatusMessage>,
    modifier: Modifier = Modifier,
    showVisuals: Boolean = false
) {
    val messages = remember { mutableStateListOf<AiStatusMessage>() }

    LaunchedEffect(flow) {
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
                OrpheusColors.midnightBlue.copy(alpha = 0.15f),
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
                style = MaterialTheme.typography.labelMedium,
                color = OrpheusColors.metallicBlue,
                fontWeight = FontWeight.Black,
                fontSize = 11.sp,
                letterSpacing = 1.2.sp
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
            items(messages) { msg ->
                if (showVisuals && msg.text.startsWith("Set ")) {
                    ControlItem(msg.text)
                } else {
                    Text(
                        text = msg.text,
                        style = MaterialTheme.typography.bodySmall,
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
fun ControlItem(text: String) {
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
