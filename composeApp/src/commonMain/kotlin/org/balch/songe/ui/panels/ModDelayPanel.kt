package org.balch.songe.ui.panels

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.songe.ui.components.RotaryKnob
import org.balch.songe.ui.theme.SongeColors

@Composable
fun ModDelayPanel(
    time1: Float, onTime1Change: (Float) -> Unit,
    mod1: Float, onMod1Change: (Float) -> Unit,
    time2: Float, onTime2Change: (Float) -> Unit,
    mod2: Float, onMod2Change: (Float) -> Unit,
    feedback: Float, onFeedbackChange: (Float) -> Unit,
    mix: Float, onMixChange: (Float) -> Unit,
    isLfoSource: Boolean, onSourceChange: (Boolean) -> Unit,
    isTriangleWave: Boolean, onWaveformChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    CollapsibleColumnPanel(
        title = "DELAY",
        color = SongeColors.warmGlow,
        initialExpanded = true,
        expandedWidth = 200.dp,
        modifier = modifier
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                RotaryKnob(value = mod1, onValueChange = onMod1Change, label = "MOD 1", controlId = null, size = 32.dp, progressColor = SongeColors.warmGlow)
                Spacer(Modifier.height(4.dp))
                RotaryKnob(value = time1, onValueChange = onTime1Change, label = "TIME 1", controlId = null, size = 32.dp, progressColor = SongeColors.warmGlow)
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                RotaryKnob(value = mod2, onValueChange = onMod2Change, label = "MOD 2", controlId = null, size = 32.dp, progressColor = SongeColors.warmGlow)
                Spacer(Modifier.height(4.dp))
                RotaryKnob(value = time2, onValueChange = onTime2Change, label = "TIME 2", controlId = null, size = 32.dp, progressColor = SongeColors.warmGlow)
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
                // Simplified toggles for space
                Box(modifier = Modifier.height(30.dp).fillMaxWidth().background(Color.White.copy(0.1f)).clickable{ onSourceChange(!isLfoSource) }) {
                   Text(if(isLfoSource) "LFO" else "SELF", fontSize=8.sp, modifier = Modifier.align(Alignment.Center), color=SongeColors.warmGlow)
                }
                 RotaryKnob(value = feedback, onValueChange = onFeedbackChange, label = "FB", controlId = null, size = 32.dp, progressColor = SongeColors.warmGlow)
            }
             Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
                  Box(modifier = Modifier.height(30.dp).fillMaxWidth().background(Color.White.copy(0.1f)).clickable{ onWaveformChange(!isTriangleWave) }) {
                       Text(if(isTriangleWave) "TRI" else "SQR", fontSize=8.sp, modifier = Modifier.align(Alignment.Center), color=SongeColors.warmGlow)
                }
                RotaryKnob(value = mix, onValueChange = onMixChange, label = "MIX", controlId = null, size = 32.dp, progressColor = SongeColors.warmGlow)
            }
        }
    }
}
