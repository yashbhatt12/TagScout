package com.snainfotech.tagscout.ui.screens.config

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snainfotech.tagscout.ui.theme.BorderGray
import com.snainfotech.tagscout.ui.theme.DarkText
import com.snainfotech.tagscout.ui.theme.ErrorRed
import com.snainfotech.tagscout.ui.theme.MediumGray
import com.snainfotech.tagscout.ui.theme.Primary
import com.snainfotech.tagscout.ui.theme.SuccessGreen

// ============================================
// RESET CONFIRMATION DIALOG
// ============================================

@Composable
fun ResetConfirmationDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "⚠️ Reset Device?",
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Text(
                text = "This will restore the device to factory defaults. All custom settings will be lost. This action cannot be undone.",
                fontSize = 13.sp
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = ErrorRed)
            ) {
                Text("Reset")
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
// RESET PROGRESS DIALOG (blocking, no dismiss)
// ============================================

@Composable
fun ResetProgressDialog(progress: Int) {
    AlertDialog(
        onDismissRequest = { /* Can't dismiss - blocking */ },
        title = {
            // Rotating spinner
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                val infiniteTransition = rememberInfiniteTransition(label = "reset_spin")
                val rotation by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(animation = tween(1500)),
                    label = "reset_rotation"
                )
                Text(
                    text = "🔄",
                    fontSize = 36.sp,
                    modifier = Modifier.rotate(rotation)
                )
            }
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Resetting Device...",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DarkText
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Please wait while device is being reset.",
                    fontSize = 12.sp,
                    color = MediumGray
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Progress bar
                ProgressBar(progress = progress)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "$progress%",
                    fontSize = 11.sp,
                    color = MediumGray
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "⚠ Do not disconnect device",
                    fontSize = 11.sp,
                    color = ErrorRed,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        confirmButton = { /* No button - blocking */ }
    )
}

// ============================================
// RESET SUCCESS DIALOG
// ============================================

@Composable
fun ResetSuccessDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "✅ Device Reset Complete",
                fontWeight = FontWeight.SemiBold,
                color = SuccessGreen
            )
        },
        text = {
            Text(
                text = "Your device has been reset to factory defaults.",
                fontSize = 13.sp
            )
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
// FIRMWARE UPDATE AVAILABLE DIALOG
// ============================================

@Composable
fun FirmwareUpdateAvailableDialog(
    currentVersion: String,
    newVersion: String,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "📥 Firmware Update Available",
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column {
                Text(
                    text = newVersion,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Primary
                )
                Text(
                    text = "Current: $currentVersion",
                    fontSize = 11.sp,
                    color = MediumGray
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Includes bug fixes and performance improvements.",
                    fontSize = 12.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onUpdate,
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) {
                Text("Update Now")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Skip", color = MediumGray)
            }
        }
    )
}

// ============================================
// FIRMWARE UPDATE PROGRESS DIALOG (blocking)
// ============================================

@Composable
fun FirmwareUpdateProgressDialog(progress: Int) {
    AlertDialog(
        onDismissRequest = { /* Blocking */ },
        title = {
            Text(
                text = "📥 Updating Firmware...",
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column {
                ProgressBar(progress = progress)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "$progress%",
                    fontSize = 12.sp,
                    color = MediumGray,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Estimated 2 minutes remaining",
                    fontSize = 12.sp,
                    color = MediumGray
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "⚠ Do not disconnect device or power off",
                    fontSize = 11.sp,
                    color = ErrorRed,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        confirmButton = { /* Blocking */ }
    )
}

// ============================================
// FIRMWARE UPDATE SUCCESS DIALOG
// ============================================

@Composable
fun FirmwareUpdateSuccessDialog(
    newVersion: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "✅ Firmware Updated!",
                fontWeight = FontWeight.SemiBold,
                color = SuccessGreen
            )
        },
        text = {
            Column {
                Text(
                    text = "Successfully updated to",
                    fontSize = 13.sp
                )
                Text(
                    text = newVersion,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = SuccessGreen
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Device will restart in 5 seconds...",
                    fontSize = 11.sp,
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
// PROGRESS BAR HELPER
// ============================================

@Composable
private fun ProgressBar(progress: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(BorderGray)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress / 100f)
                .height(8.dp)
                .background(Primary)
        )
    }
}

// ============================================
// PREVIEWS
// ============================================

@Preview(showBackground = true)
@Composable
fun ResetConfirmationDialogPreview() {
    ResetConfirmationDialog(onDismiss = {}, onConfirm = {})
}

@Preview(showBackground = true)
@Composable
fun ResetProgressDialogPreview() {
    ResetProgressDialog(progress = 65)
}

@Preview(showBackground = true)
@Composable
fun ResetSuccessDialogPreview() {
    ResetSuccessDialog(onDismiss = {})
}

@Preview(showBackground = true)
@Composable
fun FirmwareUpdateAvailableDialogPreview() {
    FirmwareUpdateAvailableDialog(
        currentVersion = "v5.90.00.02",
        newVersion = "v5.92.00.01",
        onDismiss = {},
        onUpdate = {}
    )
}

@Preview(showBackground = true)
@Composable
fun FirmwareUpdateProgressDialogPreview() {
    FirmwareUpdateProgressDialog(progress = 65)
}

@Preview(showBackground = true)
@Composable
fun FirmwareUpdateSuccessDialogPreview() {
    FirmwareUpdateSuccessDialog(newVersion = "v5.92.00.01", onDismiss = {})
}
// ============================================
// FIRMWARE UP TO DATE DIALOG
// ============================================

@Composable
fun FirmwareUpToDateDialog(
    currentVersion: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "✅ Firmware Up to Date",
                fontWeight = FontWeight.SemiBold,
                color = SuccessGreen
            )
        },
        text = {
            Column {
                Text(
                    text = "You're running the latest version:",
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = currentVersion,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "No updates available at this time.",
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
// FIRMWARE CHECKING DIALOG (brief spinner)
// ============================================

@Composable
fun FirmwareCheckingDialog() {
    AlertDialog(
        onDismissRequest = { /* Blocking */ },
        title = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                val infiniteTransition = rememberInfiniteTransition(label = "check_spin")
                val rotation by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(animation = tween(1500)),
                    label = "check_rotation"
                )
                Text(
                    text = "🔄",
                    fontSize = 36.sp,
                    modifier = Modifier.rotate(rotation)
                )
            }
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Checking for Updates...",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DarkText
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Connecting to update server",
                    fontSize = 12.sp,
                    color = MediumGray
                )
            }
        },
        confirmButton = { /* Blocking */ }
    )
}
@Preview(showBackground = true)
@Composable
fun FirmwareUpToDateDialogPreview() {
    FirmwareUpToDateDialog(
        currentVersion = "v5.90.00.02",
        onDismiss = {}
    )
}

@Preview(showBackground = true)
@Composable
fun FirmwareCheckingDialogPreview() {
    FirmwareCheckingDialog()
}