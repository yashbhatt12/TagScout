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
import com.snainfotech.tagscout.ui.theme.DarkText
import com.snainfotech.tagscout.ui.theme.ErrorRed
import com.snainfotech.tagscout.ui.theme.MediumGray
import com.snainfotech.tagscout.ui.theme.Primary
import com.snainfotech.tagscout.ui.theme.WarningOrange

// Warning shown BEFORE the save dialog — confirms user wants to end the session
@Composable
fun PreSaveWarningDialog(
    onDismiss: () -> Unit,
    onContinue: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "⚠️ End Scan Session?",
                fontWeight = FontWeight.SemiBold,
                color = WarningOrange
            )
        },
        text = {
            Column {
                Text(
                    text = "Saving will end this scan session.",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = DarkText
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "All scanned tags will be cleared after saving. You won't be able to add more tags to this file.",
                    fontSize = 12.sp,
                    color = MediumGray
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "💡 If you need more time, tap Cancel and resume scan when you're ready",
                    fontSize = 11.sp,
                    color = Primary,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onContinue,
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) {
                Text("Continue to Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MediumGray)
            }
        }
    )
}

@Preview(showBackground = true)
@Composable
fun PreSaveWarningDialogPreview() {
    PreSaveWarningDialog(onDismiss = {}, onContinue = {})
}