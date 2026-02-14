package org.balch.orpheus.features.speech

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.balch.orpheus.core.plugin.symbols.TtsSymbol
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.theme.OrpheusAssets
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.widgets.RotaryKnob
import org.jetbrains.compose.resources.painterResource
import kotlin.math.PI
import kotlin.math.sin

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
                modifier = Modifier.widthIn(max = 240.dp),
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
                        .weight(1f)
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

            // Speech readout display — tap to speak/stop
            SpeechReadout(
                speechText = uiState.speechText,
                isSpeaking = uiState.isSpeaking,
                isGenerating = uiState.isGenerating,
                spacebarTrigger = uiState.spacebarTrigger,
                onToggle = {
                    val isBusy = uiState.isSpeaking || uiState.isGenerating
                    if (isBusy) actions.stop() else actions.speak()
                },
                onSpacebarToggle = { actions.setSpacebarTrigger(!uiState.spacebarTrigger) },
                color = color,
            )

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

/** Display phases for the speech readout */
private enum class ReadoutPhase {
    PLAY_BUTTON,
    GENERATING,
    SPEAKING,
    DONE_HOLD,
}

@Composable
private fun SpeechReadout(
    speechText: String,
    isSpeaking: Boolean,
    isGenerating: Boolean,
    spacebarTrigger: Boolean,
    onToggle: () -> Unit,
    onSpacebarToggle: () -> Unit,
    color: Color,
) {
    var phase by remember { mutableStateOf(ReadoutPhase.PLAY_BUTTON) }
    var revealedWordCount by remember { mutableIntStateOf(0) }
    val words = remember(speechText) {
        speechText.split(" ").filter { it.isNotEmpty() }
    }

    // Phase state machine driven by external state changes
    LaunchedEffect(isGenerating, isSpeaking) {
        when {
            isGenerating -> {
                phase = ReadoutPhase.GENERATING
                revealedWordCount = 0
            }
            isSpeaking -> {
                phase = ReadoutPhase.SPEAKING
                revealedWordCount = 0
                // Word-by-word reveal animation
                for (i in 1..words.size) {
                    delay(220L)
                    revealedWordCount = i
                }
                // If we animated through all words before speech ends, just wait
            }
            // Transitioning from active → idle
            phase == ReadoutPhase.SPEAKING -> {
                revealedWordCount = words.size
                phase = ReadoutPhase.DONE_HOLD
                delay(2000L)
                phase = ReadoutPhase.PLAY_BUTTON
            }
            phase == ReadoutPhase.GENERATING -> {
                // Generation was cancelled/stopped
                phase = ReadoutPhase.PLAY_BUTTON
            }
            phase == ReadoutPhase.DONE_HOLD -> {
                // Already holding, let it finish naturally
            }
        }
    }

    // --- Animated values ---

    // Play button visibility (1 = fully visible, 0 = hidden)
    val playAlpha by animateFloatAsState(
        targetValue = if (phase == ReadoutPhase.PLAY_BUTTON) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (phase == ReadoutPhase.PLAY_BUTTON) 800 else 300,
            easing = FastOutSlowInEasing
        ),
        label = "playAlpha"
    )

    // Text visibility
    val textAlpha by animateFloatAsState(
        targetValue = when (phase) {
            ReadoutPhase.SPEAKING -> 1f
            ReadoutPhase.DONE_HOLD -> 1f
            else -> 0f
        },
        animationSpec = tween(400, easing = FastOutSlowInEasing),
        label = "textAlpha"
    )

    // Generating dots visibility
    val dotsAlpha by animateFloatAsState(
        targetValue = if (phase == ReadoutPhase.GENERATING) 1f else 0f,
        animationSpec = tween(300),
        label = "dotsAlpha"
    )

    // Pulsing glow for play button
    val glowTransition = rememberInfiniteTransition(label = "glow")
    val glowPulse by glowTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowPulse"
    )

    // Outer ring breathe for play button
    val ringPulse by glowTransition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ringPulse"
    )

    // Speaking background warmth
    val speakingGlow by animateFloatAsState(
        targetValue = when (phase) {
            ReadoutPhase.SPEAKING -> 0.15f
            ReadoutPhase.DONE_HOLD -> 0.08f
            ReadoutPhase.GENERATING -> 0.06f
            else -> 0f
        },
        animationSpec = tween(600),
        label = "speakingGlow"
    )

    // Generating dot wave
    val waveTransition = rememberInfiniteTransition(label = "wave")
    val wavePhase by waveTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
        ),
        label = "wavePhase"
    )

    // Current-word highlight pulse (subtle size/brightness throb)
    val wordPulse by glowTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "wordPulse"
    )

    // Done-hold fade progress for a gentle dim effect
    val doneHoldDim = remember { Animatable(1f) }
    LaunchedEffect(phase) {
        if (phase == ReadoutPhase.DONE_HOLD) {
            doneHoldDim.snapTo(1f)
            doneHoldDim.animateTo(
                targetValue = 0.5f,
                animationSpec = tween(2000, easing = FastOutSlowInEasing)
            )
        } else {
            doneHoldDim.snapTo(1f)
        }
    }

    Box(
        modifier = Modifier
            .widthIn(max = 240.dp)
            .fillMaxWidth()
            .height(110.dp)
            .clip(RoundedCornerShape(10.dp))
            .drawBehind {
                val bgColor = OrpheusColors.blackHoleBackground

                // Base background
                drawRoundRect(
                    color = bgColor.copy(alpha = 0.7f),
                    cornerRadius = CornerRadius(10.dp.toPx())
                )

                // Warm glow when active
                if (speakingGlow > 0f) {
                    drawRoundRect(
                        color = color.copy(alpha = speakingGlow),
                        cornerRadius = CornerRadius(10.dp.toPx())
                    )
                }

                // Play button radial glow
                if (playAlpha > 0.01f) {
                    // Inner glow
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                color.copy(alpha = glowPulse * playAlpha * 0.7f),
                                color.copy(alpha = glowPulse * playAlpha * 0.2f),
                                Color.Transparent
                            ),
                            center = center,
                            radius = 70.dp.toPx()
                        ),
                        radius = 70.dp.toPx()
                    )
                    // Outer halo ring
                    drawCircle(
                        color = color.copy(alpha = ringPulse * playAlpha),
                        radius = 36.dp.toPx(),
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                    // Inner ring
                    drawCircle(
                        color = color.copy(alpha = ringPulse * playAlpha * 0.5f),
                        radius = 24.dp.toPx(),
                        style = Stroke(width = 0.8.dp.toPx())
                    )
                }

                // Subtle border
                drawRoundRect(
                    color = color.copy(
                        alpha = when (phase) {
                            ReadoutPhase.PLAY_BUTTON -> ringPulse * 0.6f
                            ReadoutPhase.SPEAKING -> 0.2f
                            ReadoutPhase.DONE_HOLD -> 0.1f
                            ReadoutPhase.GENERATING -> 0.15f
                        }
                    ),
                    cornerRadius = CornerRadius(10.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx())
                )
            }
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {

        // Play triangle overlay - only in play button phase
        if (playAlpha > 0.01f) {
            Text(
                modifier = Modifier.align(Alignment.TopEnd),
                text = "\u25B6",
                color = color.copy(alpha = (0.7f + glowPulse * 0.3f) * playAlpha),
                fontSize = 28.sp,
            )
        }

        // Layer 1: Orpheus avatar - always visible, adjusts per phase
        val avatarAlpha by animateFloatAsState(
            targetValue = when (phase) {
                ReadoutPhase.PLAY_BUTTON -> 0.7f + glowPulse * 0.15f
                ReadoutPhase.GENERATING -> 0.2f
                ReadoutPhase.SPEAKING -> 0.15f
                ReadoutPhase.DONE_HOLD -> 0.12f * doneHoldDim.value
            },
            animationSpec = tween(600),
            label = "avatarAlpha"
        )
        val avatarSize by animateFloatAsState(
            targetValue = when (phase) {
                ReadoutPhase.PLAY_BUTTON -> 80f
                else -> 72f
            },
            animationSpec = tween(500, easing = FastOutSlowInEasing),
            label = "avatarSize"
        )
        Image(
            painter = painterResource(OrpheusAssets.avatar),
            contentDescription = null,
            colorFilter = ColorFilter.tint(
                color = color.copy(alpha = 0.35f),
                blendMode = BlendMode.Multiply,
            ),
            modifier = Modifier
                .size(avatarSize.dp)
                .clip(CircleShape)
                .alpha(avatarAlpha),
        )

        // Layer 2: Generating animation
        if (dotsAlpha > 0.01f) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.alpha(dotsAlpha)
            ) {
                // Wave dots
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(32.dp)
                ) {
                    for (i in 0 until 5) {
                        val dotPhase = sin(wavePhase + i * 0.8f)
                        val dotAlpha = 0.4f + 0.6f * ((dotPhase + 1f) / 2f)
                        val dotSize = (4f + 2f * ((dotPhase + 1f) / 2f)).dp
                        Box(
                            modifier = Modifier
                                .graphicsLayer {
                                    translationY = -dotPhase * 8.dp.toPx()
                                }
                                .size(dotSize)
                                .alpha(dotAlpha)
                                .clip(CircleShape)
                                .background(color)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "synthesizing",
                    color = color.copy(alpha = 0.5f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.sp,
                )
            }
        }

        // Layer 3: Speaking text with word-by-word reveal
        if (textAlpha > 0.01f && words.isNotEmpty()) {
            val dimFactor = doneHoldDim.value

            val annotatedText = buildAnnotatedString {
                words.forEachIndexed { index, word ->
                    val isRevealed = index < revealedWordCount
                    val isCurrent = index == revealedWordCount - 1 &&
                        phase == ReadoutPhase.SPEAKING

                    val wordAlpha = when {
                        isCurrent -> wordPulse * dimFactor
                        isRevealed -> 0.55f * dimFactor
                        else -> 0.06f // ghost text - barely visible upcoming words
                    }

                    val weight = when {
                        isCurrent -> FontWeight.ExtraBold
                        isRevealed -> FontWeight.Medium
                        else -> FontWeight.Normal
                    }

                    val size = when {
                        isCurrent -> 14.sp
                        isRevealed -> 12.sp
                        else -> 11.sp
                    }

                    withStyle(
                        SpanStyle(
                            color = color.copy(alpha = wordAlpha * textAlpha),
                            fontWeight = weight,
                            fontSize = size,
                        )
                    ) {
                        append(word)
                    }
                    if (index < words.lastIndex) append(" ")
                }
            }

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = annotatedText,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(textAlpha),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Spacebar trigger toggle in bottom-right corner
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(22.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    if (spacebarTrigger) color.copy(alpha = 0.3f)
                    else OrpheusColors.blackHoleBackground.copy(alpha = 0.6f)
                )
                .clickable(onClick = onSpacebarToggle),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "\u2423",
                color = if (spacebarTrigger) color else color.copy(alpha = 0.3f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun VoiceSelector(
    selectedVoice: String,
    voices: List<String>,
    onVoiceSelected: (String) -> Unit,
    color: Color,
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
                            else Color.White,
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

@Preview(widthDp = 400, heightDp = 380)
@Composable
fun SpeechPanelPreview() {
    SpeechPanel(
        isExpanded = true,
        feature = SpeechViewModel.previewFeature(
            SpeechUiState(
                textInput = "one of these days",
                speechText = "one of these days I'm going to cut you into little pieces",
                isSpeaking = true,
                availableVoices = listOf("Samantha", "Alex", "Daniel"),
                selectedVoice = "Samantha",
            )
        )
    )
}

@Preview(widthDp = 400, heightDp = 380)
@Composable
fun SpeechPanelIdlePreview() {
    SpeechPanel(
        isExpanded = true,
        feature = SpeechViewModel.previewFeature(
            SpeechUiState(
                textInput = "hello world",
                availableVoices = listOf("Samantha", "Alex"),
                selectedVoice = "Samantha",
            )
        )
    )
}

@Preview(widthDp = 400, heightDp = 380)
@Composable
fun SpeechPanelGeneratingPreview() {
    SpeechPanel(
        isExpanded = true,
        feature = SpeechViewModel.previewFeature(
            SpeechUiState(
                textInput = "synthesize me",
                isGenerating = true,
                availableVoices = listOf("Samantha"),
                selectedVoice = "Samantha",
            )
        )
    )
}
