package org.balch.orpheus.features.speech

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.core.plugin.symbols.TtsSymbol
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.widgets.RotaryKnob

@Composable
fun SpeechPanel(
    feature: SpeechFeature = SpeechViewModel.feature(),
    modifier: Modifier = Modifier,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    onTextFieldFocusChange: (Boolean) -> Unit = {},
) {
    val uiState by feature.stateFlow.collectAsState()
    val actions = feature.actions
    val color = OrpheusColors.speechRose

    CollapsibleColumnPanel(
        title = "TALK",
        expandedTitle = "Speakeasy",
        color = color,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = false,
        modifier = modifier,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Voice selector + text input
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (uiState.availableVoices.isNotEmpty()) {
                    VoiceSelector(
                        selectedVoice = uiState.selectedVoice,
                        voices = uiState.availableVoices,
                        onVoiceSelected = actions.setSelectedVoice,
                        color = color,
                    )
                }

                BasicTextField(
                    value = uiState.textInput,
                    onValueChange = actions.setTextInput,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = color,
                        fontSize = 11.sp,
                    ),
                    cursorBrush = SolidColor(color),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { actions.speak() }),
                    modifier = Modifier
                        .width(160.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(OrpheusColors.blackHoleBackground.copy(alpha = 0.5f))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .onFocusChanged { onTextFieldFocusChange(it.isFocused) }
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown &&
                                (event.key == Key.Enter || event.key == Key.NumPadEnter)
                            ) {
                                if (uiState.isSpeaking || uiState.isGenerating) actions.stop() else actions.speak()
                                true
                            } else false
                        },
                    decorationBox = { innerTextField ->
                        Box {
                            if (uiState.textInput.isEmpty()) {
                                Text(
                                    text = "Type a phrase...",
                                    color = color.copy(alpha = 0.3f),
                                    fontSize = 11.sp,
                                )
                            }
                            innerTextField()
                        }
                    }
                )
            }

            // Controls: play/stop + spacebar toggle + speaking status
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Speak/Stop button
                val isBusy = uiState.isSpeaking || uiState.isGenerating
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(if (isBusy) color.copy(alpha = 0.2f) else color.copy(alpha = 0.1f))
                        .clickable { if (isBusy) actions.stop() else actions.speak() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isBusy) "\u25A0" else "\u25B6",
                        color = if (isBusy) color else color.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                // Spacebar trigger toggle
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(
                            if (uiState.spacebarTrigger) color.copy(alpha = 0.3f)
                            else OrpheusColors.blackHoleBackground.copy(alpha = 0.5f)
                        )
                        .clickable { actions.setSpacebarTrigger(!uiState.spacebarTrigger) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "\u2423",
                        color = if (uiState.spacebarTrigger) color else color.copy(alpha = 0.3f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                // Speaking status indicator
                if (isBusy || uiState.speechText.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (isBusy) {
                            val infiniteTransition = rememberInfiniteTransition()
                            val pulseAlpha by infiniteTransition.animateFloat(
                                initialValue = 0.3f,
                                targetValue = 1f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(if (uiState.isGenerating) 200 else 400),
                                    repeatMode = RepeatMode.Reverse
                                )
                            )
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .alpha(pulseAlpha)
                                    .clip(CircleShape)
                                    .background(color)
                            )
                        }

                        Text(
                            text = if (uiState.isGenerating) "generating..." else uiState.speechText,
                            color = if (isBusy) color.copy(alpha = 0.6f) else color.copy(alpha = 0.3f),
                            fontSize = 9.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // Knobs row 1: RATE, SPD, VOL
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RotaryKnob(
                    value = uiState.rate,
                    onValueChange = actions.setRate,
                    label = "PTCH",
                    controlId = TtsSymbol.RATE.controlId.key,
                    size = 36.dp,
                    progressColor = color
                )
                RotaryKnob(
                    value = uiState.speed,
                    onValueChange = actions.setSpeed,
                    label = "SPD",
                    controlId = TtsSymbol.SPEED.controlId.key,
                    size = 36.dp,
                    progressColor = color
                )
                RotaryKnob(
                    value = uiState.volume,
                    onValueChange = actions.setVolume,
                    label = "VOL",
                    controlId = TtsSymbol.VOLUME.controlId.key,
                    size = 36.dp,
                    progressColor = color
                )
            }

            // Knobs row 2: VERB, PHSR, FDBK
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RotaryKnob(
                    value = uiState.reverb,
                    onValueChange = actions.setReverb,
                    label = "VERB",
                    controlId = TtsSymbol.REVERB.controlId.key,
                    size = 36.dp,
                    progressColor = color
                )
                RotaryKnob(
                    value = uiState.phaser,
                    onValueChange = actions.setPhaser,
                    label = "PHSR",
                    controlId = TtsSymbol.PHASER.controlId.key,
                    size = 36.dp,
                    progressColor = color
                )
                RotaryKnob(
                    value = uiState.feedback,
                    onValueChange = actions.setFeedback,
                    label = "FDBK",
                    controlId = TtsSymbol.FEEDBACK.controlId.key,
                    size = 36.dp,
                    progressColor = color
                )
            }
        }
    }
}

@Composable
private fun VoiceSelector(
    selectedVoice: String,
    voices: List<String>,
    onVoiceSelected: (String) -> Unit,
    color: androidx.compose.ui.graphics.Color,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Box(
            modifier = Modifier
                .clickable { expanded = true }
                .clip(RoundedCornerShape(4.dp))
                .background(OrpheusColors.blackHoleBackground.copy(alpha = 0.5f))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = selectedVoice.ifEmpty { "Voice" },
                    color = color,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Select Voice",
                    tint = color,
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(OrpheusColors.panelSurface)
        ) {
            voices.forEach { voice ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = voice,
                            color = if (voice == selectedVoice) color
                            else androidx.compose.ui.graphics.Color.White,
                            fontSize = 11.sp,
                        )
                    },
                    onClick = {
                        onVoiceSelected(voice)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Preview(widthDp = 400, heightDp = 320)
@Composable
fun SpeechPanelPreview() {
    SpeechPanel(
        isExpanded = true,
        feature = SpeechViewModel.previewFeature(
            SpeechUiState(
                textInput = "one of these days",
                speechText = "one of these days",
                isSpeaking = true,
                availableVoices = listOf("Samantha", "Alex", "Daniel"),
                selectedVoice = "Samantha",
            )
        )
    )
}
