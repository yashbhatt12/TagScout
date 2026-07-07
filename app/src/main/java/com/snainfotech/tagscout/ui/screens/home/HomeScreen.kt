package com.snainfotech.tagscout.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snainfotech.tagscout.ui.components.AppHeader
import com.snainfotech.tagscout.ui.components.DeviceStatusComponent
import com.snainfotech.tagscout.ui.components.FeatureButton
import com.snainfotech.tagscout.ui.theme.DarkText
import com.snainfotech.tagscout.ui.theme.InfoBg
import com.snainfotech.tagscout.ui.theme.InfoBlue
import com.snainfotech.tagscout.ui.theme.InfoText
import com.snainfotech.tagscout.ui.theme.LightGray
import com.snainfotech.tagscout.ui.theme.WarningBg
import com.snainfotech.tagscout.ui.theme.WarningOrange
import com.snainfotech.tagscout.ui.theme.WarningText

@Composable
fun HomeScreen(
    deviceState: DeviceState,
    modifier: Modifier = Modifier,
    onMenuClick: () -> Unit = {},
    onConnectDeviceClick: () -> Unit = {},
    onQuickScanClick: () -> Unit = {},
    onInventoryClick: () -> Unit = {},
    onPickOrderClick: () -> Unit = {},
    onWriteTagClick: () -> Unit = {},
    onKillTagClick: () -> Unit = {},
    onDeviceConfigClick: () -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LightGray)
    ) {
        // 1. Header
        AppHeader(
            title = "TagScout",
            onMenuClick = onMenuClick
        )

        // 2. Device Status
        DeviceStatusComponent(
            isConnected = deviceState.isConnected,
            deviceName = deviceState.deviceName,
            serialNumber = deviceState.serialNumber,
            firmwareVersion = deviceState.firmwareVersion,
            batteryPercent = deviceState.batteryPercent,
            connectionStatus = deviceState.connectionStatus,
            onClick = onDeviceConfigClick
        )

        // 3. Main content area

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)                              // claim remaining space below the status bar
                .verticalScroll(rememberScrollState())   // scroll when content overflows
                .padding(16.dp)
        ) {
            // Show banner based on state
            when (deviceState.connectionStatus) {
                ConnectionStatus.DISCONNECTED -> {
                    InfoBanner(
                        text = "💡 Connect a Device to Start."
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                ConnectionStatus.LOW_BATTERY -> {
                    WarningBanner(
                        text = "⚠️ Low Battery — Battery is at ${deviceState.batteryPercent}%. Device may shut down during scanning. Please charge soon."
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                else -> { /* no banner for Connected or Charging */ }
            }

            // Feature buttons
            // Feature buttons
            FeatureButton(
                icon = "🔗",
                title = "Connect Device",
                description = "Pair your RFID reader",
                enabled = true,
                onClick = onConnectDeviceClick
            )
            FeatureButton(
                icon = "📱",
                title = "Quick Scan",
                description = "Scan and identify tags",
                enabled = deviceState.isConnected,
                onClick = onQuickScanClick
            )

            FeatureButton(
                icon = "📊",
                title = "Inventory by File",
                description = "Match tags against inventory",
                enabled = deviceState.isConnected,
                onClick = onInventoryClick
            )
            FeatureButton(
                icon = "📦",
                title = "Pick Order",
                description = "Fulfill customer orders",
                enabled = deviceState.isConnected,
                onClick = onPickOrderClick
            )
            FeatureButton(
                icon = "✏️",
                title = "Write Tag",
                description= "Change a tag's EPC",
                enabled = deviceState.isConnected,
                onClick= onWriteTagClick,
            )

            FeatureButton(
                icon = "❌",
                title = "Kill Tag",
                description= "Permanently Disable a Tag",
                enabled = deviceState.isConnected,
                onClick= onKillTagClick,
            )
            FeatureButton(
                icon = "⚙️",
                title = "Device Config",
                description = "Configure your device",
                enabled = deviceState.isConnected,
                onClick = onDeviceConfigClick
            )
        }
    }
}

// Info banner (blue) - shown when disconnected
@Composable
private fun InfoBanner(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(InfoBg)
            .padding(12.dp)
    ) {
        Text(
            text = text,
            color = InfoText,
            fontSize = 12.sp
        )
    }
}

// Warning banner (orange) - shown for low battery
@Composable
private fun WarningBanner(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(WarningBg)
            .padding(12.dp)
    ) {
        Text(
            text = text,
            color = WarningText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// ============================================
// PREVIEWS - All 4 states
// ============================================

@Preview(showBackground = true, heightDp = 700)
@Composable
fun HomeScreenConnectedPreview() {
    HomeScreen(
        deviceState = DeviceState(
            isConnected = true,
            deviceName = "RFR-900",
            serialNumber = "SN-123456",
            firmwareVersion = "v5.90.00.02",
            batteryPercent = 85,
            connectionStatus = ConnectionStatus.CONNECTED
        )
    )
}

@Preview(showBackground = true, heightDp = 700)
@Composable
fun HomeScreenDisconnectedPreview() {
    HomeScreen(
        deviceState = DeviceState(
            isConnected = false,
            connectionStatus = ConnectionStatus.DISCONNECTED
        )
    )
}

@Preview(showBackground = true, heightDp = 700)
@Composable
fun HomeScreenLowBatteryPreview() {
    HomeScreen(
        deviceState = DeviceState(
            isConnected = true,
            deviceName = "RFR-900",
            serialNumber = "SN-123456",
            firmwareVersion = "v5.90.00.02",
            batteryPercent = 12,
            connectionStatus = ConnectionStatus.LOW_BATTERY
        )
    )
}

@Preview(showBackground = true, heightDp = 700)
@Composable
fun HomeScreenChargingPreview() {
    HomeScreen(
        deviceState = DeviceState(
            isConnected = true,
            deviceName = "RFR-900",
            serialNumber = "SN-123456",
            firmwareVersion = "v5.90.00.02",
            batteryPercent = 45,
            connectionStatus = ConnectionStatus.CHARGING
        )
    )
}