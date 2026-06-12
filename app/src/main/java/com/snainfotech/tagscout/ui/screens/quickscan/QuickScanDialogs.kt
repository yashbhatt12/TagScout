package com.snainfotech.tagscout.ui.screens.quickscan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snainfotech.tagscout.ui.theme.BorderGray
import com.snainfotech.tagscout.ui.theme.DarkText
import com.snainfotech.tagscout.ui.theme.Disabled
import com.snainfotech.tagscout.ui.theme.DisabledText
import com.snainfotech.tagscout.ui.theme.ErrorRed
import com.snainfotech.tagscout.ui.theme.MediumGray
import com.snainfotech.tagscout.ui.theme.Primary
import com.snainfotech.tagscout.ui.theme.SuccessGreen
import com.snainfotech.tagscout.ui.theme.InfoBlue
import com.snainfotech.tagscout.ui.theme.WarningOrange
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ============================================
// SAVE DIALOG
// ============================================

@Composable
fun SaveScanDialog(
    onDismiss: () -> Unit,
    onSave: (filename: String, format: String) -> Unit
) {
    val defaultFilename = remember {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
        "scan_${sdf.format(Date())}"
    }

    var filename by remember { mutableStateOf(defaultFilename) }
    var formatExpanded by remember { mutableStateOf(false) }
    var selectedFormat by remember { mutableStateOf("Excel (.xlsx)") }
    val formats = listOf("Excel (.xlsx)", "CSV (.csv)")

    // Validation — computed at the dialog level so both text body and confirm button can see it
    val trimmedFilename = filename.trim()
    val isFilenameValid = trimmedFilename.isNotEmpty() &&
            trimmedFilename.matches(Regex("[a-zA-Z0-9_\\-]+"))
    val errorMessage = when {
        trimmedFilename.isEmpty() -> "Filename cannot be empty"
        !isFilenameValid -> "Only letters, numbers, _ and - allowed"
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "💾 Save Scan Results",
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column {
                Text(
                    text = "Filename",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DarkText
                )

                OutlinedTextField(
                    value = filename,
                    onValueChange = { filename = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    singleLine = true,
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let {
                        { Text(it, fontSize = 10.sp, color = ErrorRed) }
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Format",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DarkText
                )

                Box(modifier = Modifier.padding(top = 6.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .border(1.dp, BorderGray, RoundedCornerShape(4.dp))
                            .background(Color.White)
                            .clickable { formatExpanded = true }
                            .padding(horizontal = 12.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = selectedFormat,
                            fontSize = 14.sp,
                            color = DarkText,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "▼",
                            fontSize = 10.sp,
                            color = MediumGray
                        )
                    }

                    DropdownMenu(
                        expanded = formatExpanded,
                        onDismissRequest = { formatExpanded = false }
                    ) {
                        formats.forEach { format ->
                            DropdownMenuItem(
                                text = { Text(format) },
                                onClick = {
                                    selectedFormat = format
                                    formatExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(trimmedFilename, selectedFormat) },
                enabled = isFilenameValid,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary,
                    disabledContainerColor = Disabled,
                    disabledContentColor = DisabledText
                )
            ) {
                Text("Save")
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
// CLEAR CONFIRMATION DIALOG
// ============================================

@Composable
fun ClearConfirmationDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "⚠️ Clear Scan Data?",
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Text(
                text = "This will delete all scan results. This action cannot be undone.",
                fontSize = 13.sp
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = ErrorRed)
            ) {
                Text("Clear All")
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
// TIME WARNING DIALOG
// ============================================

@Composable
fun TimeWarningDialog(
    onStopScan: () -> Unit,
    onExtend: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onStopScan,
        title = {
            Text(
                text = "⏱️ Scan Time Running Out",
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Text(
                text = "Only 30 seconds remaining. Would you like to extend the scan?",
                fontSize = 13.sp
            )
        },
        confirmButton = {
            Button(
                onClick = onExtend,
                colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)
            ) {
                Text("Extend 3 Min")
            }
        },
        dismissButton = {
            TextButton(onClick = onStopScan) {
                Text("Stop Scan", color = MediumGray)
            }
        }
    )
}
// ============================================
// LOW BATTERY WARNING DIALOG
// ============================================

@Composable
fun LowBatteryDialog(
    batteryPercent: Int,
    onContinue: () -> Unit,
    onStop: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onStop,  // Tap outside = stop (safer default)
        title = {
            Text(
                text = "🔋 Low Battery",
                fontWeight = FontWeight.SemiBold,
                color = WarningOrange
            )
        },
        text = {
            Column {
                Text(
                    text = "Device battery is at $batteryPercent%.",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Scanning has been paused. The device may shut down before your scan is complete.",
                    fontSize = 12.sp,
                    color = MediumGray
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "💡 Tip: Save your current data before continuing.",
                    fontSize = 11.sp,
                    color = InfoBlue,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onContinue,
                colors = ButtonDefaults.buttonColors(containerColor = WarningOrange)
            ) {
                Text("Continue Scanning")
            }
        },
        dismissButton = {
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) {
                Text("Stop Scan")
            }
        }
    )
}
// ============================================
// DEVICE DISCONNECTED DIALOG
// ============================================

@Composable
fun DeviceDisconnectedDialog(
    onDismiss: () -> Unit
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
                    text = "Your RFID reader has disconnected during the scan.",
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Scanning has been paused. Reconnect your device to continue.",
                    fontSize = 12.sp,
                    color = MediumGray
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "⚠ Scan data may be incomplete.",
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
// ============================================
// CRITICAL BATTERY DIALOG (forces user to stop)
// ============================================

@Composable
fun CriticalBatteryDialog(
    batteryPercent: Int,
    onAcknowledge: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onAcknowledge,
        title = {
            Text(
                text = "🪫 Critical Battery",
                fontWeight = FontWeight.SemiBold,
                color = ErrorRed
            )
        },
        text = {
            Column {
                Text(
                    text = "Device battery is at $batteryPercent%.",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = ErrorRed
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Scanning has been stopped to protect your data. The device may shut down at any moment.",
                    fontSize = 12.sp,
                    color = MediumGray
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "💾 Please save your scan data immediately.",
                    fontSize = 12.sp,
                    color = Primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onAcknowledge,
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
fun SaveScanDialogPreview() {
    SaveScanDialog(onDismiss = {}, onSave = { _, _ -> })
}

@Preview(showBackground = true)
@Composable
fun ClearConfirmationDialogPreview() {
    ClearConfirmationDialog(onDismiss = {}, onConfirm = {})
}

@Preview(showBackground = true)
@Composable
fun TimeWarningDialogPreview() {
    TimeWarningDialog(onStopScan = {}, onExtend = {})
}
@Preview(showBackground = true)
@Composable
fun LowBatteryDialogPreview() {
    LowBatteryDialog(
        batteryPercent = 12,
        onContinue = {},
        onStop = {}
    )
}
@Preview(showBackground = true)
@Composable
fun DeviceDisconnectedDialogPreview() {
    DeviceDisconnectedDialog(onDismiss = {})
}
@Preview(showBackground = true)
@Composable
fun CriticalBatteryDialogPreview() {
    CriticalBatteryDialog(
        batteryPercent = 4,
        onAcknowledge = {}
    )
}