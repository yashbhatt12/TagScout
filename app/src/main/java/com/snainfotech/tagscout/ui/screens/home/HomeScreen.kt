package com.snainfotech.tagscout.ui.screens.home
import com.snainfotech.tagscout.R
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snainfotech.tagscout.data.config.CompanyConfig
import com.snainfotech.tagscout.data.config.ConfigRepository
import com.snainfotech.tagscout.data.config.Feature
import com.snainfotech.tagscout.ui.components.AppHeader
import com.snainfotech.tagscout.ui.components.DeviceStatusComponent
import com.snainfotech.tagscout.ui.components.FeatureButton
import com.snainfotech.tagscout.ui.theme.DarkText
import com.snainfotech.tagscout.ui.theme.InfoBg
import com.snainfotech.tagscout.ui.theme.InfoBlue
import com.snainfotech.tagscout.ui.theme.InfoText
import com.snainfotech.tagscout.ui.theme.LightGray
import com.snainfotech.tagscout.ui.theme.MediumGray
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
    onOrderPickingClick: () -> Unit = {},
    onPickOrderClick: () -> Unit = {},
    onWriteTagClick: () -> Unit = {},
    onKillTagClick: () -> Unit = {},
    onLocateTagClick: () -> Unit = {},
    onShopClick: () -> Unit = {},
    onWmsClick: () -> Unit = {},
    onRetailClick: () -> Unit = {},
    onTagOperationsClick: () -> Unit = {},
    onDeviceConfigClick: () -> Unit = {}
) {
    // Live company config. Previews can't start Firebase, so they use defaults.
    val config: CompanyConfig = if (LocalInspectionMode.current) {
        CompanyConfig.DEFAULT
    } else {
        ConfigRepository.config.collectAsState().value
    }

    // Title of the feature whose "upgrade" tile was tapped; null = no dialog.
    var upgradeFor by remember { mutableStateOf<String?>(null) }

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
                .weight(1f)
                .verticalScroll(rememberScrollState())
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

            // Connect Device and Device Config are never flagged — the app is
            // useless without them. Every other tile follows settings/config:
            // enabled → normal tile, disabled → "upgrade" tile.
            FeatureButton(
                icon = R.drawable.ic_connect,
                title = "Connect Device",
                description = "Pair your RFID reader",
                enabled = true,
                onClick = onConnectDeviceClick
            )

            GatedFeatureButton(
                available = config.anyTagOperationEnabled(),
                icon = R.drawable.ic_rfid_tag,
                title = "Tag Operations",
                description = "Scan, locate, read and write RFID tags",
                enabled = true,
                onClick = onTagOperationsClick,
                onUpgrade = { upgradeFor = it }
            )
            GatedFeatureButton(
                available = config.isEnabled(Feature.WMS),
                icon = R.drawable.ic_inventory,
                title = "Warehouse Management",
                description = "Inward, cycle count, pick lists & dispatch",
                enabled = true,
                onClick = onWmsClick,
                onUpgrade = { upgradeFor = it }
            )
            GatedFeatureButton(
                available = config.isEnabled(Feature.RETAIL),
                icon = R.drawable.ic_jewellery,
                title = "Retail Management",
                description = "Enroll pieces, checkout, manager alerts and cycle count",
                enabled = deviceState.isConnected,
                onClick = onRetailClick,
                onUpgrade = { upgradeFor = it }
            )
            GatedFeatureButton(
                available = config.isEnabled(Feature.SHOP),
                icon = R.drawable.ic_shop,
                title = "Order Supplies",
                description = "Purchase RFID labels, scanners & printers",
                enabled = true,
                onClick = onShopClick,
                onUpgrade = { upgradeFor = it }
            )

            FeatureButton(
                icon = R.drawable.ic_config,
                title = "Device Config",
                description = "Configure your device",
                enabled = deviceState.isConnected,
                onClick = onDeviceConfigClick
            )
        }
    }

    upgradeFor?.let { title ->
        UpgradeDialog(featureTitle = title, onDismiss = { upgradeFor = null })
    }
}

/**
 * A feature tile that respects the company's plan.
 *   available = true  → the normal tile (still greyed when [enabled] is false,
 *                       e.g. Retail without a connected sled)
 *   available = false → a faded tile that stays tappable and opens the
 *                       upgrade dialog instead of the feature
 */
@Composable
private fun GatedFeatureButton(
    available: Boolean,
    @DrawableRes icon: Int,
    title: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    onUpgrade: (String) -> Unit
) {
    if (available) {
        FeatureButton(
            icon = icon,
            title = title,
            description = description,
            enabled = enabled,
            onClick = onClick
        )
    } else {
        FeatureButton(
            icon = icon,
            title = title,
            description = "🔒 Not in your plan · Tap to upgrade",
            enabled = true,
            onClick = { onUpgrade(title) },
            modifier = Modifier.alpha(0.6f)
        )
    }
}

@Composable
private fun UpgradeDialog(featureTitle: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "🔒 $featureTitle",
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column {
                Text(
                    text = "$featureTitle isn't part of your current plan.",
                    fontSize = 13.sp,
                    color = DarkText
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "To enable it, contact SNA Infotech at contactus@sna-infotech.co.in or +91-22-42182620.",
                    fontSize = 12.sp,
                    color = MediumGray
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    )
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