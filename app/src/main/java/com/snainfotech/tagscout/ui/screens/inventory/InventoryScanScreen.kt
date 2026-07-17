package com.snainfotech.tagscout.ui.screens.inventory

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import com.snainfotech.tagscout.ui.components.TimerBadge
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
import com.snainfotech.tagscout.ui.theme.TableRowFound
import com.snainfotech.tagscout.ui.theme.TableRowMissing
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
    // Build the timer badge for the header
    val timerBadge = when {
        state.isScanning -> TimerBadge.Live(formatTime(state.timeRemaining))
        state.isPaused -> TimerBadge.Paused(formatTime(state.timeRemaining))
        else -> TimerBadge.None
    }

    Column(modifier = modifier.fillMaxSize().background(LightGray)) {

        // Top bar with timer badge
        AppHeader(
            title = "Inventory by File",
            showBackButton = !state.isScanning,
            onBackClick = onBackClick,
            onMenuClick = onMenuClick,
            timerBadge = timerBadge
        )

        // Device status bar
        DeviceStatusComponent(
            isConnected = isDeviceConnected,
            deviceName = deviceName,
            serialNumber = serialNumber,
            firmwareVersion = firmwareVersion,
            batteryPercent = batteryPercent,
            connectionStatus = if (isDeviceConnected) ConnectionStatus.CONNECTED else ConnectionStatus.DISCONNECTED,
            onClick = if (isDeviceConnected) onDeviceStatusClick else null
        )

        // Antenna control
        AntennaSlider(
            value = state.antennaStrength,
            enabled = !state.isScanning,
            onValueChange = onAntennaChange
        )

        // Content varies based on state
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

        // File info bar (compact, no extra stats)
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

        // Tab row
        TabRow(
            selectedTab = state.selectedTab,
            fileMappingFound = state.foundItems,
            fileMappingTotal = state.expectedItems,
            additionalTagsCount = state.unexpectedTags.size,
            onTabSelect = onTabSelect
        )

        // Conditional table based on selected tab
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

        // Bottom button bar
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

        // Match progress section
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

        // Item table fills the rest
        ItemTable(
            items = state.inventoryItems,
            modifier = Modifier.weight(1f)
        )
    }
}

// ============================================
// TAB ROW
// ============================================

@Composable
private fun TabRow(
    selectedTab: InventoryTab,
    fileMappingFound: Int,
    fileMappingTotal: Int,
    additionalTagsCount: Int,
    onTabSelect: (InventoryTab) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(LightGray)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TabButton(
            label = "File Mapping",
            count = "$fileMappingFound/$fileMappingTotal",
            isSelected = selectedTab == InventoryTab.FILE_MAPPING,
            onClick = { onTabSelect(InventoryTab.FILE_MAPPING) },
            modifier = Modifier.weight(1f)
        )
        TabButton(
            label = "Additional Tags",
            count = "$additionalTagsCount",
            isSelected = selectedTab == InventoryTab.ADDITIONAL_TAGS,
            onClick = { onTabSelect(InventoryTab.ADDITIONAL_TAGS) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TabButton(
    label: String,
    count: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = if (isSelected) Primary else Color.White
    val textColor = if (isSelected) Color.White else DarkText
    val borderColor = if (isSelected) Primary else BorderGray

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(vertical = 8.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = textColor
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = count,
            fontSize = 11.sp,
            color = textColor,
            fontFamily = FontFamily.Monospace
        )
    }
}

// ============================================
// ITEM TABLE (File Mapping)
// ============================================

@Composable
private fun ItemTable(
    items: List<InventoryItem>,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().background(Color.White)) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(LightGray)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Status",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = MediumGray,
                modifier = Modifier.weight(0.18f)
            )
            Text(
                text = "EPC",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = MediumGray,
                modifier = Modifier.weight(0.5f)
            )
            Text(
                text = "Product",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = MediumGray,
                modifier = Modifier.weight(0.32f)
            )
        }

        if (items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No items loaded",
                    fontSize = 12.sp,
                    color = MediumGray
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(items = items, key = { it.id }) { item ->
                    InventoryItemRow(item = item)
                }
            }
        }
    }
}

@Composable
private fun InventoryItemRow(item: InventoryItem) {
    val bgColor = if (item.isFound) TableRowFound else TableRowMissing

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (item.isFound) "✓" else "✗",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = if (item.isFound) SuccessGreen else ErrorRed,
            modifier = Modifier.weight(0.18f)
        )
        Text(
            text = item.epc.take(16),
            fontSize = 11.sp,
            color = DarkText,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(0.5f)
        )
        Text(
            text = item.productName,
            fontSize = 11.sp,
            color = DarkText,
            modifier = Modifier.weight(0.32f)
        )
    }
}

// ============================================
// UNEXPECTED TAGS TABLE
// ============================================

@Composable
private fun UnexpectedTagsTable(
    tags: List<UnexpectedTag>,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().background(Color.White)) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(LightGray)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "EPC",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = MediumGray,
                modifier = Modifier.weight(0.55f)
            )
            Text(
                text = "TID",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = MediumGray,
                modifier = Modifier.weight(0.25f)
            )
            Text(
                text = "First Seen",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = MediumGray,
                modifier = Modifier.weight(0.2f)
            )
        }

        if (tags.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "✨", fontSize = 32.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No additional tags",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = DarkText
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Tags scanned that aren't in your file will appear here",
                        fontSize = 11.sp,
                        color = MediumGray
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(items = tags, key = { it.epc }) { tag ->
                    UnexpectedTagRow(tag = tag)
                }
            }
        }
    }
}

@Composable
private fun UnexpectedTagRow(tag: UnexpectedTag) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .border(0.5.dp, BorderGray)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = tag.epc.take(20),
            fontSize = 11.sp,
            color = DarkText,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(0.55f)
        )
        Text(
            text = tag.tid.take(10),
            fontSize = 11.sp,
            color = MediumGray,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(0.25f)
        )
        Text(
            text = formatElapsed(tag.firstSeenSeconds),
            fontSize = 10.sp,
            color = MediumGray,
            modifier = Modifier.weight(0.2f)
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

private fun formatElapsed(seconds: Int): String {
    return if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"
}