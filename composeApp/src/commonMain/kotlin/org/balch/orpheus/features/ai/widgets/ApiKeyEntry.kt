package org.balch.orpheus.features.ai.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.core.ai.AiProvider
import org.balch.orpheus.core.ai.deriveAiProviderFromKey
import org.balch.orpheus.ui.theme.OrpheusColors

/**
 * Compact API key entry for the AiOptionsPanel.
 */
@Composable
fun ApiKeyEntryCompact(
    onSubmit: (AiProvider, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var key by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current

    val submit = {
        val aiProvider = deriveAiProviderFromKey(key)
        if (aiProvider != null) {
            error = null
            onSubmit(aiProvider, key)
        } else {
            error = "Invalid key format"
        }
    }

    Column(
        modifier = modifier.padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "🔑 Enter API Key",
            style = MaterialTheme.typography.labelMedium,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = OrpheusColors.metallicBlue
        )

        BasicTextField(
            value = key,
            onValueChange = { 
                key = it
                error = null
            },
            textStyle = TextStyle(
                color = OrpheusColors.sterlingSilver,
                fontSize = 12.sp
            ),
            cursorBrush = SolidColor(OrpheusColors.metallicBlue),
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(OrpheusColors.midnightBlue.copy(alpha = 0.4f))
                .padding(10.dp),
            decorationBox = { innerTextField ->
                if (key.isEmpty()) {
                    Text(
                        text = "Paste API Key...",
                        style = TextStyle(
                            color = OrpheusColors.sterlingSilver.copy(alpha = 0.5f),
                            fontSize = 12.sp
                        )
                    )
                }
                innerTextField()
            }
        )

        if (error != null) {
            Text(
                text = error!!,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.error
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Toggle visibility
            IconButton(
                onClick = { showKey = !showKey },
                modifier = Modifier.size(32.dp)
            ) {
                Text(
                    text = if (showKey) "Hide" else "Show",
                    fontSize = 10.sp,
                    color = OrpheusColors.sterlingSilver.copy(alpha = 0.7f)
                )
            }

            Button(
                onClick = submit,
                enabled = key.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = OrpheusColors.metallicBlue,
                    contentColor = OrpheusColors.sterlingSilver
                ),
                modifier = Modifier.height(32.dp)
            ) {
                Text("Save", fontSize = 12.sp)
            }
        }

        Text(
            text = "Get API Key →",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = OrpheusColors.metallicBlue,
            textDecoration = TextDecoration.Underline,
            textAlign = TextAlign.Center,
            modifier = Modifier.clickable {
                uriHandler.openUri("https://aistudio.google.com/apikey")
            }
        )
    }
}

/**
 * Full API key entry screen for ChatDialog.
 */
@Composable
fun ApiKeyEntryScreen(
    onSubmit: (AiProvider, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var key by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current

    val submit = {
        val aiProvider = deriveAiProviderFromKey(key)
        if (aiProvider != null) {
            error = null
            onSubmit(aiProvider, key)
        } else {
            error = "Invalid key format"
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "🎵",
            fontSize = 48.sp
        )
        
        Spacer(Modifier.height(12.dp))

        Text(
            text = "Orpheus Awaits",
            style = MaterialTheme.typography.headlineSmall,
            color = OrpheusColors.metallicBlue
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Enter your Gemini API key to unlock AI features",
            style = MaterialTheme.typography.bodySmall,
            color = OrpheusColors.sterlingSilver.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(20.dp))

        // Key input field
        BasicTextField(
            value = key,
            onValueChange = { 
                key = it
                error = null
            },
            textStyle = TextStyle(
                color = OrpheusColors.sterlingSilver,
                fontSize = 14.sp
            ),
            cursorBrush = SolidColor(OrpheusColors.metallicBlue),
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(OrpheusColors.midnightBlue.copy(alpha = 0.4f))
                .padding(12.dp),
            decorationBox = { innerTextField ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("🔑", fontSize = 16.sp)
                    if (key.isEmpty()) {
                        Text(
                            text = "Paste API Key...",
                            style = TextStyle(
                                color = OrpheusColors.sterlingSilver.copy(alpha = 0.4f),
                                fontSize = 14.sp
                            )
                        )
                    }
                    innerTextField()
                }
            }
        )

        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = error!!,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.height(16.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { showKey = !showKey },
                modifier = Modifier.size(40.dp)
            ) {
                Text(
                    text = if (showKey) "Hide" else "Show",
                    fontSize = 12.sp,
                    color = OrpheusColors.sterlingSilver.copy(alpha = 0.7f)
                )
            }

            Button(
                onClick = submit,
                enabled = key.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = OrpheusColors.metallicBlue,
                    contentColor = OrpheusColors.sterlingSilver
                )
            ) {
                Text("Save Key")
            }
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = "Get a free API key →",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = OrpheusColors.metallicBlue,
            textDecoration = TextDecoration.Underline,
            textAlign = TextAlign.Center,
            modifier = Modifier.clickable {
                uriHandler.openUri("https://aistudio.google.com/apikey")
            }
        )
    }
}

/**
 * Indicator showing a user key is active, with remove option.
 */
@Composable
fun UserKeyIndicator(
    aiProvider: AiProvider,
    onRemove: (AiProvider) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(OrpheusColors.metallicBlue.copy(alpha = 0.2f))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "🔑",
            fontSize = 10.sp
        )
        Text(
            text = "User Key",
            fontSize = 9.sp,
            color = OrpheusColors.metallicBlue
        )
        IconButton(
            onClick = { onRemove(aiProvider) },
            modifier = Modifier.size(16.dp)
        ) {
            Text("✕", fontSize = 10.sp, color = OrpheusColors.sterlingSilver.copy(alpha = 0.7f))
        }
    }
}
