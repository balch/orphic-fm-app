package org.balch.songe.ui.widgets

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.songe.ui.theme.SongeColors

/**
 * Small popup dialog for naming a new preset.
 */
@Composable
fun PresetNameDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var presetName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "NEW DRONE",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = SongeColors.neonCyan
            )
        },
        text = {
            TextField(
                value = presetName,
                onValueChange = { presetName = it },
                placeholder = { Text("Enter name...", fontSize = 14.sp, color = Color.Gray) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Black.copy(alpha = 0.5f),
                    unfocusedContainerColor = Color.Black.copy(alpha = 0.3f),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = SongeColors.neonCyan,
                    focusedIndicatorColor = SongeColors.neonCyan,
                    unfocusedIndicatorColor = Color.Gray
                ),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp)
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(presetName.trim()) },
                enabled = presetName.isNotBlank()
            ) {
                Text(
                    text = "OK",
                    color = if (presetName.isNotBlank()) SongeColors.neonCyan else Color.Gray,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "Cancel",
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
        },
        containerColor = SongeColors.darkVoid,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.border(1.dp, SongeColors.neonCyan.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
    )
}
