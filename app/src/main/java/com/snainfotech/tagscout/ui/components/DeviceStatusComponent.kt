package com.snainfotech.tagscout.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import com.snainfotech.tagscout.ui.screens.home.ConnectionStatus
import com.snainfotech.tagscout.ui.theme.BorderGray
import com.snainfotech.tagscout.ui.theme.DarkText
import com.snainfotech.tagscout.ui.theme.ErrorRed
import com.snainfotech.tagscout.ui.theme.MediumGray
import com.snainfotech.tagscout.ui.theme.Primary
import com.snainfotech.tagscout.ui.theme.SuccessGreen
import com.snainfotech.tagscout.ui.theme.WarningOrange
import androidx.compose.ui.text.style.TextOverflow
import com.snainfotech.tagscout.ui.theme.SuccessBg
import com.snainfotech.tagscout.ui.theme.ErrorBg
import com.snainfotech.tagscout.ui.theme.WarningBg
import com.snainfotech.tagscout.ui.theme.InfoBg

@Composable
fun DeviceStatusComponent(
    isConnected: Boolean,
    deviceName: String,
    serialNumber: String,
    firmwareVersion: String,  // Kept for API compatibility; not displayed.
    batteryPercent: Int,
    connectionStatus: ConnectionStatus,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // Background and dot colour tied to connection state. The background
    // change is deliberate — it makes the sled's state obvious at a glance
    // without the user having to read the strip.
    val background = when (connectionStatus) {
        ConnectionStatus.CONNECTED    -> SuccessBg
        ConnectionStatus.DISCONNECTED -> ErrorBg
        ConnectionStatus.LOW_BATTERY  -> WarningBg
        ConnectionStatus.CHARGING     -> InfoBg
    }
    val dotColor = when (connectionStatus) {
        ConnectionStatus.CONNECTED    -> SuccessGreen
        ConnectionStatus.DISCONNECTED -> ErrorRed
        ConnectionStatus.LOW_BATTERY  -> WarningOrange
        ConnectionStatus.CHARGING     -> Primary
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .then(
                if (onClick != null && isConnected) Modifier.clickable { onClick() }
                else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status dot
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(8.dp))

        if (isConnected) {
            // Device name — primary identity
            Text(
                text = deviceName,
                color = DarkText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Serial — secondary, monospace. Takes only its own width; the
            // flex spacer after it pushes the battery to the right edge.
            if (serialNumber.isNotBlank()) {
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = serialNumber,
                    color = MediumGray,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Flex spacer — eats all remaining space so battery sticks to the right.
            Spacer(modifier = Modifier.weight(1f))

            // Battery — right-aligned
            Text(
                text = "$batteryPercent%",
                color = MediumGray,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.width(6.dp))
            BatteryBar(percent = batteryPercent)
        } else {
            // Disconnected — just the message, no serial / battery to show
            Text(
                text = "No Device Connected",
                color = DarkText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

// Battery bar: green normally, amber when low, red when critical.
@Composable
private fun BatteryBar(percent: Int) {
    val fill = when {
        percent <= 10 -> ErrorRed
        percent <= 20 -> WarningOrange
        else -> SuccessGreen
    }
    Box(
        modifier = Modifier
            .width(40.dp)
            .height(12.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color.White)
            .border(1.dp, BorderGray, RoundedCornerShape(3.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(percent.coerceIn(0, 100) / 100f)
                .height(12.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(fill)
        )
    }
}

// ============================================
// PREVIEWS
// ============================================

@Preview(showBackground = true)
@Composable
fun DeviceStatusConnectedPreview() {
    DeviceStatusComponent(
        isConnected = true,
        deviceName = "RFR-901",
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
        deviceName = "RFR-901",
        serialNumber = "SN-123456",
        firmwareVersion = "v5.90.00.02",
        batteryPercent = 12,
        connectionStatus = ConnectionStatus.LOW_BATTERY
    )
}