package com.snainfotech.tagscout.ui.screens.connect

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snainfotech.tagscout.data.repository.DeviceRepository
import com.snainfotech.tagscout.ui.theme.DarkText
import com.snainfotech.tagscout.ui.theme.ErrorRed
import com.snainfotech.tagscout.ui.theme.MediumGray
import com.snainfotech.tagscout.ui.theme.Primary

// ============================================
// DELETE DEVICE CONFIRMATION DIALOG
// ============================================

@Composable
fun DeleteDeviceConfirmationDialog(
    deviceName: String,
    isCurrentlyConnected: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "🗑 Forget Device?",
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column {
                Text(
                    text = "Remove \"$deviceName\" from your saved devices?",
                    fontSize = 13.sp,
                    color = DarkText
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (isCurrentlyConnected) {
                    Text(
                        text = "⚠ This device is currently connected. Forgetting will also disconnect it.",
                        fontSize = 12.sp,
                        color = ErrorRed,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }

                Text(
                    text = "You'll need to search for it again to reconnect.",
                    fontSize = 11.sp,
                    color = MediumGray
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = ErrorRed)
            ) {
                Text("Forget")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MediumGray)
            }
        }
    )
}

// ============================================
// DEVICE LIMIT REACHED DIALOG
// ============================================

@Composable
fun DeviceLimitReachedDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "📱 Device Limit Reached",
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column {
                Text(
                    text = "You've reached the maximum of ${DeviceRepository.MAX_SAVED_DEVICES} saved devices.",
                    fontSize = 13.sp,
                    color = DarkText
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "To add a new device, long-press one of your saved devices to forget it first.",
                    fontSize = 12.sp,
                    color = MediumGray
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

// ============================================
// PREVIEWS
// ============================================

@Preview(showBackground = true)
@Composable
fun DeleteDeviceConfirmationDialogPreview() {
    DeleteDeviceConfirmationDialog(
        deviceName = "RFR-901 (ABC123)",
        isCurrentlyConnected = false,
        onDismiss = {},
        onConfirm = {}
    )
}

@Preview(showBackground = true)
@Composable
fun DeleteDeviceConnectedConfirmationDialogPreview() {
    DeleteDeviceConfirmationDialog(
        deviceName = "RFR-901 (XYZ789)",
        isCurrentlyConnected = true,
        onDismiss = {},
        onConfirm = {}
    )
}

@Preview(showBackground = true)
@Composable
fun DeviceLimitReachedDialogPreview() {
    DeviceLimitReachedDialog(onDismiss = {})
}