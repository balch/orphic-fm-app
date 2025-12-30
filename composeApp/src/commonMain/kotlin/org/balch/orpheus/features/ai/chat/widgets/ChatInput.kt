package org.balch.orpheus.features.ai.chat.widgets

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.ui.theme.OrpheusColors

/**
 * Chat input field for sending messages to the AI agent.
 * Features a premium glass-like appearance with Sterling Silver accent colors.
 */
@Composable
fun ChatInputField(
    isEnabled: Boolean,
    onSendMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var message by remember { mutableStateOf("") }
    val sendMessage = {
        val trimmed = message.trim()
        if (trimmed.isNotEmpty()) {
            onSendMessage(trimmed)
            message = ""
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = message,
            onValueChange = { message = it },
            enabled = isEnabled,
            modifier = Modifier
                .weight(1f)
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        (event.key == Key.Enter || event.key == Key.NumPadEnter) &&
                        !event.isShiftPressed
                    ) {
                        sendMessage()
                        true
                    } else {
                        false
                    }
                },
            placeholder = {
                Text(
                    if (isEnabled) "Ask Orpheus..." else "Thinking...",
                    style = typography.bodySmall,
                    fontSize = 11.sp,
                    color = OrpheusColors.sterlingSilver.copy(alpha = 0.5f)
                )
            },
            trailingIcon = {
                IconButton(
                    enabled = isEnabled && message.isNotBlank(),
                    onClick = { sendMessage() },
                    modifier = Modifier.size(32.dp)
                ) {
                    Text(
                        text = "→",
                        color = if (isEnabled && message.isNotBlank()) 
                            OrpheusColors.metallicBlue // Blue send arrow
                        else 
                            OrpheusColors.slateSilver.copy(alpha = 0.4f),
                        fontSize = 18.sp
                    )
                }
            },
            textStyle = typography.bodySmall.copy(
                fontSize = 11.sp,
                color = OrpheusColors.sterlingSilver
            ),
            shape = RoundedCornerShape(16.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { sendMessage() }),
            colors = OutlinedTextFieldDefaults.colors(
                // Cursor and selection colors - Silver
                cursorColor = OrpheusColors.sterlingSilver,
                
                // Border colors - Silver with varying alpha
                focusedBorderColor = OrpheusColors.sterlingSilver.copy(alpha = 0.8f),
                unfocusedBorderColor = OrpheusColors.slateSilver.copy(alpha = 0.4f),
                disabledBorderColor = OrpheusColors.slateSilver.copy(alpha = 0.2f),
                
                // Container colors - Midnight Blue glass effect
                focusedContainerColor = OrpheusColors.midnightBlue.copy(alpha = 0.2f),
                unfocusedContainerColor = OrpheusColors.midnightBlue.copy(alpha = 0.15f),
                disabledContainerColor = OrpheusColors.midnightBlue.copy(alpha = 0.05f),
                
                // Text colors
                focusedTextColor = OrpheusColors.sterlingSilver,
                unfocusedTextColor = OrpheusColors.sterlingSilver,
                disabledTextColor = OrpheusColors.slateSilver.copy(alpha = 0.5f),
                
                // Label and placeholder
                focusedPlaceholderColor = OrpheusColors.sterlingSilver.copy(alpha = 0.5f),
                unfocusedPlaceholderColor = OrpheusColors.slateSilver.copy(alpha = 0.4f),
            )
        )
    }
}
