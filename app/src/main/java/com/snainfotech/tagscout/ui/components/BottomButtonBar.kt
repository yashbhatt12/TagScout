package com.snainfotech.tagscout.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snainfotech.tagscout.ui.theme.Disabled
import com.snainfotech.tagscout.ui.theme.DisabledText
import com.snainfotech.tagscout.ui.theme.ErrorRed
import com.snainfotech.tagscout.ui.theme.Primary
import com.snainfotech.tagscout.ui.theme.SuccessGreen
import com.snainfotech.tagscout.ui.theme.WarningOrange

// Possible button states
enum class ScanButtonState {
    INITIAL,    // Save: disabled, Middle: Play (green), Clear: disabled
    SCANNING,   // Save: disabled, Middle: Pause (orange), Clear: disabled
    PAUSED      // Save: enabled (blue), Middle: Resume (green), Clear: enabled (red)
}

@Composable
fun BottomButtonBar(
    state: ScanButtonState,
    onSaveClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onClearClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFFF8F9FA))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Save button
        ActionButton(
            text = "💾 Save",
            enabled = state == ScanButtonState.PAUSED,
            enabledColor = Primary,
            onClick = onSaveClick,
            modifier = Modifier.weight(1f)
        )

        // Middle button (Play / Pause / Resume)
        when (state) {
            ScanButtonState.INITIAL -> ActionButton(
                text = "▶ Play",
                enabled = true,
                enabledColor = SuccessGreen,
                onClick = onPlayPauseClick,
                modifier = Modifier.weight(1f)
            )
            ScanButtonState.SCANNING -> ActionButton(
                text = "⏸ Pause",
                enabled = true,
                enabledColor = WarningOrange,
                onClick = onPlayPauseClick,
                modifier = Modifier.weight(1f)
            )
            ScanButtonState.PAUSED -> ActionButton(
                text = "▶ Resume",
                enabled = true,
                enabledColor = SuccessGreen,
                onClick = onPlayPauseClick,
                modifier = Modifier.weight(1f)
            )
        }

        // Clear button
        ActionButton(
            text = "🗑 Clear",
            enabled = state == ScanButtonState.PAUSED,
            enabledColor = ErrorRed,
            onClick = onClearClick,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ActionButton(
    text: String,
    enabled: Boolean,
    enabledColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = enabledColor,
            contentColor = Color.White,
            disabledContainerColor = Disabled,
            disabledContentColor = DisabledText
        )
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Preview(showBackground = true)
@Composable
fun BottomButtonBarInitialPreview() {
    BottomButtonBar(
        state = ScanButtonState.INITIAL,
        onSaveClick = {}, onPlayPauseClick = {}, onClearClick = {}
    )
}

@Preview(showBackground = true)
@Composable
fun BottomButtonBarScanningPreview() {
    BottomButtonBar(
        state = ScanButtonState.SCANNING,
        onSaveClick = {}, onPlayPauseClick = {}, onClearClick = {}
    )
}

@Preview(showBackground = true)
@Composable
fun BottomButtonBarPausedPreview() {
    BottomButtonBar(
        state = ScanButtonState.PAUSED,
        onSaveClick = {}, onPlayPauseClick = {}, onClearClick = {}
    )
}