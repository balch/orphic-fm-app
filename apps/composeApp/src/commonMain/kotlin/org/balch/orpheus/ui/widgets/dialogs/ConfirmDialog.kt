package org.balch.orpheus.ui.widgets.dialogs

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.ui.theme.OrpheusColors

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = "YES",
    dismissLabel: String = "NO",
    isDestructive: Boolean = false
) {
    val borderColor = if (isDestructive) Color.Red.copy(alpha = 0.5f) else OrpheusColors.neonCyan.copy(alpha = 0.5f)
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title.uppercase(),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = if (isDestructive) Color.Red else OrpheusColors.neonCyan
            )
        },
        text = {
            Text(
                text = message,
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.9f)
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmLabel,
                    color = if (isDestructive) Color.Red else OrpheusColors.neonCyan,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = dismissLabel,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
        },
        containerColor = OrpheusColors.darkVoid,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.border(1.dp, borderColor, RoundedCornerShape(8.dp))
    )
}
