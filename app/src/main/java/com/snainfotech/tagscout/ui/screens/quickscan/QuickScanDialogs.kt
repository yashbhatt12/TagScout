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
import com.snainfotech.tagscout.ui.theme.ErrorRed
import com.snainfotech.tagscout.ui.theme.MediumGray
import com.snainfotech.tagscout.ui.theme.Primary
import com.snainfotech.tagscout.ui.theme.SuccessGreen
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
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Format",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DarkText
                )

                // Custom dropdown — simpler than ExposedDropdownMenu
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
                onClick = { onSave(filename, selectedFormat) },
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
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