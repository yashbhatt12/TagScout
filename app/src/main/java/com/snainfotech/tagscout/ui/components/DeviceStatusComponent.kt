package com.snainfotech.tagscout.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.snainfotech.tagscout.ui.screens.home.ConnectionStatus
import com.snainfotech.tagscout.ui.theme.BorderGray
import com.snainfotech.tagscout.ui.theme.ErrorBg
import com.snainfotech.tagscout.ui.theme.ErrorRed
import com.snainfotech.tagscout.ui.theme.ErrorText
import com.snainfotech.tagscout.ui.theme.InfoBg
import com.snainfotech.tagscout.ui.theme.InfoBlue
import com.snainfotech.tagscout.ui.theme.InfoText
import com.snainfotech.tagscout.ui.theme.SuccessBg
import com.snainfotech.tagscout.ui.theme.SuccessGreen
import com.snainfotech.tagscout.ui.theme.SuccessText
import com.snainfotech.tagscout.ui.theme.WarningBg
import com.snainfotech.tagscout.ui.theme.WarningOrange
import com.snainfotech.tagscout.ui.theme.WarningText

@Composable
fun DeviceStatusComponent(
    isConnected: Boolean,
    deviceName: String,
    serialNumber: String,
    firmwareVersion: String,
    batteryPercent: Int,
    connectionStatus: ConnectionStatus,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // Decide colors and text based on connection status
    val (bgColor, dotColor, textColor, statusText) = when (connectionStatus) {
        ConnectionStatus.CONNECTED -> ColorSet(SuccessBg, SuccessGreen, SuccessText, "Connected")
        ConnectionStatus.DISCONNECTED -> ColorSet(ErrorBg, ErrorRed, ErrorText, "No Device Connected")
        ConnectionStatus.LOW_BATTERY -> ColorSet(WarningBg, WarningOrange, WarningText, "Low Battery")
        ConnectionStatus.CHARGING -> ColorSet(InfoBg, InfoBlue, InfoText, "Charging")
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(bgColor)
            .then(
                if (onClick != null && isConnected) {
                    Modifier.clickable { onClick() }
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
    )  {
        // Row 1: Dot, device name, status, battery
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left side: dot + device name + status
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Colored dot
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Device name and status text
                Text(
                    text = if (isConnected) "$deviceName $statusText" else statusText,
                    color = textColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Right side: battery
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isConnected) "$batteryPercent%" else "—",
                    color = textColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )

                if (isConnected) {
                    Spacer(modifier = Modifier.width(6.dp))
                    BatteryBar(percent = batteryPercent, fillColor = dotColor)
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Row 2: Serial number + Firmware version
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (isConnected) "Serial: $serialNumber" else "Serial: —",
                color = textColor.copy(alpha = 0.8f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = if (isConnected) "FW: $firmwareVersion" else "FW: —",
                color = textColor.copy(alpha = 0.8f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

// Helper composable: the battery bar
@Composable
private fun BatteryBar(percent: Int, fillColor: Color) {
    Box(
        modifier = Modifier
            .width(40.dp)
            .height(12.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color.White)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(percent / 100f)
                .height(12.dp)
                .background(fillColor)
        )
    }
}

// Helper data class for grouping colors
private data class ColorSet(
    val bg: Color,
    val dot: Color,
    val text: Color,
    val status: String
)

// Spacer needs an import — using local override to add convenience
@Composable
private fun Spacer(modifier: Modifier) {
    androidx.compose.foundation.layout.Spacer(modifier = modifier)
}

// ============================================
// PREVIEWS - so you can see the component in Android Studio without running the app
// ============================================

@Preview(showBackground = true)
@Composable
fun DeviceStatusConnectedPreview() {
    DeviceStatusComponent(
        isConnected = true,
        deviceName = "RFR-900",
        serialNumber = "SN-123456",
        firmwareVersion = "v5.90.00.02",
        batteryPercent = 85,
        connectionStatus = ConnectionStatus.CONNECTED
    )
}

@Preview(showBackground = true)
@Composable
fun DeviceStatusDisconnectedPreview() {
    DeviceStatusComponent(
        isConnected = false,
        deviceName = "",
        serialNumber = "",
        firmwareVersion = "",
        batteryPercent = 0,
        connectionStatus = ConnectionStatus.DISCONNECTED
    )
}

@Preview(showBackground = true)
@Composable
fun DeviceStatusLowBatteryPreview() {
    DeviceStatusComponent(
        isConnected = true,
        deviceName = "RFR-900",
        serialNumber = "SN-123456",
        firmwareVersion = "v5.90.00.02",
        batteryPercent = 12,
        connectionStatus = ConnectionStatus.LOW_BATTERY
    )
}

@Preview(showBackground = true)
@Composable
fun DeviceStatusChargingPreview() {
    DeviceStatusComponent(
        isConnected = true,
        deviceName = "RFR-900",
        serialNumber = "SN-123456",
        firmwareVersion = "v5.90.00.02",
        batteryPercent = 45,
        connectionStatus = ConnectionStatus.CHARGING
    )
}