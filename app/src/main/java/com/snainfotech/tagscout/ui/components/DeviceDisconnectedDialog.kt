package com.snainfotech.tagscout.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snainfotech.tagscout.ui.theme.ErrorRed
import com.snainfotech.tagscout.ui.theme.MediumGray
import com.snainfotech.tagscout.ui.theme.Primary

// Shared dialog shown when device disconnects unexpectedly during any operation
@Composable
fun DeviceDisconnectedDialog(
    onDismiss: () -> Unit,
    customMessage: String? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "📵 Device Disconnected",
                fontWeight = FontWeight.SemiBold,
                color = ErrorRed
            )
        },
        text = {
            Column {
                Text(
                    text = customMessage ?: "Your RFID reader has disconnected during the operation.",
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Reconnect your device to continue.",
                    fontSize = 12.sp,
                    color = MediumGray
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "⚠ Operation may be incomplete.",
                    fontSize = 11.sp,
                    color = ErrorRed,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) {
                Text("OK")
            }
        }
    )
}