package com.snainfotech.tagscout.ui.components

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
import com.snainfotech.tagscout.ui.theme.ErrorRed
import com.snainfotech.tagscout.ui.theme.MediumGray
import com.snainfotech.tagscout.ui.theme.Primary

// ============================================
// PERMISSION RATIONALE DIALOG
// Shown BEFORE asking Android — explains why we need it
// ============================================

@Composable
fun BluetoothPermissionRationaleDialog(
    onDismiss: () -> Unit,
    onAllow: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "📡 Bluetooth Permission Needed",
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column {
                Text(
                    text = "TagScout needs Bluetooth permission to discover and connect to your RFID reader.",
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Your location is never tracked.",
                    fontSize = 12.sp,
                    color = MediumGray
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onAllow,
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) {
                Text("Allow")
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
// PERMISSION DENIED DIALOG
// Shown AFTER user denied the Android system permission
// ============================================

@Composable
fun BluetoothPermissionDeniedDialog(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "❌ Permission Required",
                fontWeight = FontWeight.SemiBold,
                color = ErrorRed
            )
        },
        text = {
            Column {
                Text(
                    text = "Without Bluetooth permission, TagScout cannot connect to your RFID device.",
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Please enable Bluetooth permission in app settings.",
                    fontSize = 12.sp,
                    color = MediumGray
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onOpenSettings,
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) {
                Text("Open Settings")
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
// PREVIEWS
// ============================================

@Preview(showBackground = true)
@Composable
fun BluetoothPermissionRationaleDialogPreview() {
    BluetoothPermissionRationaleDialog(onDismiss = {}, onAllow = {})
}

@Preview(showBackground = true)
@Composable
fun BluetoothPermissionDeniedDialogPreview() {
    BluetoothPermissionDeniedDialog(onDismiss = {}, onOpenSettings = {})
}