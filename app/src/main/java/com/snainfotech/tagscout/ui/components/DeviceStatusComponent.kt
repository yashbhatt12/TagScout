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
    // In the redesign the bar itself stays a neutral light surface; the status
    // color lives on the dot (and the battery bar), not the whole background.
    val (dotColor, statusText) = when (connectionStatus) {
        ConnectionStatus.CONNECTED -> SuccessGreen to "Connected"
        ConnectionStatus.DISCONNECTED -> ErrorRed to "No Device Connected"
        ConnectionStatus.LOW_BATTERY -> WarningOrange to "Low Battery"
        ConnectionStatus.CHARGING -> Primary to "Charging"
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFFEDF2FA))
            .then(
                if (onClick != null && isConnected) Modifier.clickable { onClick() }
                else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        // Row 1: dot + device name + status  |  battery % + bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isConnected) "$deviceName · $statusText" else statusText,
                    color = DarkText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (isConnected) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "$batteryPercent%",
                        color = MediumGray,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    BatteryBar(percent = batteryPercent)
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Row 2: serial + firmware (monospace, muted)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (isConnected) "Serial: $serialNumber" else "Serial: —",
                color = MediumGray,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = if (isConnected) "FW: $firmwareVersion" else "FW: —",
                color = MediumGray,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
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