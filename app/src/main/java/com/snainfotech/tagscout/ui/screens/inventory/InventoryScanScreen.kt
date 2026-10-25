package com.snainfotech.tagscout.ui.screens.inventory

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snainfotech.tagscout.ui.components.AntennaSlider
import com.snainfotech.tagscout.ui.components.AppHeader
import com.snainfotech.tagscout.ui.components.DeviceStatusComponent
import com.snainfotech.tagscout.ui.components.ExpectedItemTable
import com.snainfotech.tagscout.ui.components.ScanTabRow
import com.snainfotech.tagscout.ui.components.TimerBadge
import com.snainfotech.tagscout.ui.components.UnexpectedTagsTable
import com.snainfotech.tagscout.ui.screens.home.ConnectionStatus
import com.snainfotech.tagscout.ui.theme.BorderGray
import com.snainfotech.tagscout.ui.theme.DarkText
import com.snainfotech.tagscout.ui.theme.Disabled
import com.snainfotech.tagscout.ui.theme.DisabledText
import com.snainfotech.tagscout.ui.theme.ErrorRed
import com.snainfotech.tagscout.ui.theme.LightGray
import com.snainfotech.tagscout.ui.theme.MediumGray
import com.snainfotech.tagscout.ui.theme.Primary
import com.snainfotech.tagscout.ui.theme.SuccessGreen
import com.snainfotech.tagscout.ui.theme.WarningOrange

@Composable
fun InventoryScanScreen(
    state: InventoryScanState,
    deviceName: String,
    serialNumber: String,
    firmwareVersion: String,
    batteryPercent: Int,
    isDeviceConnected: Boolean,
    onBackClick: () -> Unit = {},
    onMenuClick: () -> Unit = {},
    onDeviceStatusClick: () -> Unit = {},
    onLoadFileClick: () -> Unit = {},
    onAntennaChange: (Int) -> Unit = {},
    onTabSelect: (InventoryTab) -> Unit = {},
    onPlayPauseClick: () -> Unit = {},
    onSaveClick: () -> Unit = {},
    onClearClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val timerBadge = when {
        state.isScanning -> TimerBadge.Live(formatTime(state.timeRemaining))
        state.isPaused -> TimerBadge.Paused(formatTime(state.timeRemaining))
        else -> TimerBadge.None
    }

    Column(modifier = modifier.fillMaxSize().background(LightGray)) {

        AppHeader(
            title = "Inventory by File",
            showBackButton = !state.isScanning,
            onBackClick = onBackClick,
            onMenuClick = onMenuClick,
            timerBadge = timerBadge,
            showMenu = false
        )

        DeviceStatusComponent(
            isConnected = isDeviceConnected,
            deviceName = deviceName,
            serialNumber = serialNumber,
            firmwareVersion = firmwareVersion,
            batteryPercent = batteryPercent,
            connectionStatus = if (isDeviceConnected) ConnectionStatus.CONNECTED else ConnectionStatus.DISCONNECTED,
            onClick = if (isDeviceConnected) onDeviceStatusClick else null
        )

        AntennaSlider(
            value = state.antennaStrength,
            enabled = !state.isScanning,
            onValueChange = onAntennaChange
        )

        if (!state.hasFileLoaded) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                EmptyState(
                    isDeviceConnected = isDeviceConnected,
                    onLoadFileClick = onLoadFileClick
                )
            }
        } else {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LoadedFileContent(
                    state = state,
                    isDeviceConnected = isDeviceConnected,
                    onLoadFileClick = onLoadFileClick,
                    onTabSelect = onTabSelect,
                    onPlayPauseClick = onPlayPauseClick,
                    onSaveClick = onSaveClick,
                    onClearClick = onClearClick
                )
            }
        }
    }
}

// ============================================
// EMPTY STATE
// ============================================

@Composable
private fun EmptyState(
    isDeviceConnected: Boolean,
    onLoadFileClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "📋", fontSize = 64.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Inventory by File",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = DarkText
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Load an Excel or CSV file with expected tags to begin inventory matching.",
            fontSize = 14.sp,
            color = MediumGray,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onLoadFileClick,
            enabled = isDeviceConnected,
            colors = ButtonDefaults.buttonColors(
                containerColor = Primary,
                disabledContainerColor = Disabled,
                disabledContentColor = DisabledText
            ),
            modifier = Modifier.height(48.dp)
        ) {
            Text("📁 Load Inventory File", fontSize = 14.sp)
        }
        if (!isDeviceConnected) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "⚠ Connect a device first to load a file",
                fontSize = 12.sp,
                color = ErrorRed
            )
        }
    }
}

// ============================================
// LOADED FILE CONTENT
// ============================================

@Composable
private fun LoadedFileContent(
    state: InventoryScanState,
    isDeviceConnected: Boolean,
    onLoadFileClick: () -> Unit,
    onTabSelect: (InventoryTab) -> Unit,
    onPlayPauseClick: () -> Unit,
    onSaveClick: () -> Unit,
    onClearClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {

        // File info bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "📁 ${state.uploadedFilename}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = DarkText
                )
                Text(
                    text = "${state.expectedItems} expected items",
                    fontSize = 11.sp,
                    color = MediumGray
                )
            }
            OutlinedButton(
                onClick = onLoadFileClick,
                enabled = !state.isScanning
            ) {
                Text("Change", fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Shared tab row
        ScanTabRow(
            selectedTab = state.selectedTab,
            expectedFound = state.foundItems,
            expectedTotal = state.expectedItems,
            additionalCount = state.unexpectedTags.size,
            expectedLabel = "File Mapping",
            onTabSelect = onTabSelect
        )

        when (state.selectedTab) {
            InventoryTab.FILE_MAPPING -> FileMappingContent(
                state = state,
                modifier = Modifier.weight(1f)
            )
            InventoryTab.ADDITIONAL_TAGS -> UnexpectedTagsTable(
                tags = state.unexpectedTags,
                modifier = Modifier.weight(1f)
            )
        }

        BottomButtons(
            state = state,
            isDeviceConnected = isDeviceConnected,
            onPlayPauseClick = onPlayPauseClick,
            onSaveClick = onSaveClick,
            onClearClick = onClearClick
        )
    }
}

// ============================================
// FILE MAPPING TAB CONTENT
// ============================================

@Composable
private fun FileMappingContent(
    state: InventoryScanState,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().background(Color.White)) {

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Match Progress",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DarkText,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${state.foundItems}/${state.expectedItems}  (${state.progressPercent}%)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SuccessGreen,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { state.progressPercent / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = SuccessGreen,
                trackColor = BorderGray
            )
        }

        ExpectedItemTable(
            items = state.inventoryItems,
            modifier = Modifier.weight(1f)
        )
    }
}

// ============================================
// BOTTOM BUTTONS
// ============================================

@Composable
private fun BottomButtons(
    state: InventoryScanState,
    isDeviceConnected: Boolean,
    onPlayPauseClick: () -> Unit,
    onSaveClick: () -> Unit,
    onClearClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = onSaveClick,
            enabled = !state.isScanning && state.tagsScanned > 0,
            modifier = Modifier.weight(1f).height(44.dp)
        ) {
            Text("💾 Save", fontSize = 13.sp)
        }

        Button(
            onClick = onPlayPauseClick,
            enabled = isDeviceConnected,
            colors = ButtonDefaults.buttonColors(
                containerColor = when {
                    state.isScanning -> WarningOrange
                    else -> SuccessGreen
                },
                disabledContainerColor = Disabled,
                disabledContentColor = DisabledText
            ),
            modifier = Modifier.weight(1f).height(44.dp)
        ) {
            Text(
                text = when {
                    state.isScanning -> "⏸ Pause"
                    state.isPaused -> "▶ Resume"
                    else -> "▶ Scan"
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        OutlinedButton(
            onClick = onClearClick,
            enabled = !state.isScanning && state.tagsScanned > 0,
            modifier = Modifier.weight(1f).height(44.dp)
        ) {
            Text("🗑 Clear", fontSize = 13.sp)
        }
    }
}

// ============================================
// HELPERS
// ============================================

private fun formatTime(seconds: Int): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return "%d:%02d".format(mins, secs)
}