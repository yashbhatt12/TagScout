package com.snainfotech.tagscout.ui.screens.orderpicking

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snainfotech.tagscout.ui.theme.ErrorRed
import com.snainfotech.tagscout.ui.theme.MediumGray
import com.snainfotech.tagscout.ui.theme.SuccessGreen

@Composable
fun ConfirmPickDialog(
    candidate: ProximityCandidate,
    onConfirmPick: () -> Unit,
    onNotNow: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { /* require an explicit choice */ },
        title = { Text("Product Detected Nearby", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(text = candidate.item.productName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Text("Product ID: ${candidate.item.productId}", fontSize = 13.sp, color = MediumGray)
                Text("Bin: ${candidate.item.binNumber}", fontSize = 13.sp, color = MediumGray)
                Text("Order: ${candidate.item.orderId}", fontSize = 13.sp, color = MediumGray)
                Text("EPC: ${candidate.epc}", fontSize = 11.sp, color = MediumGray)
                Spacer(modifier = Modifier.height(6.dp))
                Text("Signal: ${candidate.rssi} dBm", fontSize = 12.sp, color = MediumGray)
                Spacer(modifier = Modifier.height(10.dp))
                Text("Confirm you've picked this item before continuing to scan.", fontSize = 13.sp)
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmPick,
                colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)
            ) {
                Text("Confirm Pick")
            }
        },
        dismissButton = {
            TextButton(onClick = onNotNow) {
                Text("Not Now")
            }
        }
    )
}

@Composable
fun ClearOrderWarningDialog(
    onDismiss: () -> Unit,
    onConfirmClear: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cancel This Order?", fontWeight = FontWeight.Bold) },
        text = {
            Text(
                "Clearing will cancel the entire pick process for this order. " +
                        "Any progress will be lost and you'll need to re-upload the file to start over. " +
                        "This cannot be undone."
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirmClear,
                colors = ButtonDefaults.buttonColors(containerColor = ErrorRed)
            ) {
                Text("Clear & Cancel Order")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Keep Picking")
            }
        }
    )
}

@Composable
fun OrderFileErrorDialog(
    message: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Couldn't Load File", fontWeight = FontWeight.Bold) },
        text = { Text(message) },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("OK")
            }
        }
    )
}

@Composable
fun OrderSaveErrorDialog(
    message: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Couldn't Save File", fontWeight = FontWeight.Bold) },
        text = { Text(message) },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("OK")
            }
        }
    )
}

@Composable
fun OrderSaveSuccessDialog(
    fileName: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Saved", fontWeight = FontWeight.Bold) },
        text = { Text("Pick results saved to \"$fileName\".") },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("OK")
            }
        }
    )
}