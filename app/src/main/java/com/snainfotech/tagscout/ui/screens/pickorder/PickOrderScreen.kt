package com.snainfotech.tagscout.ui.screens.pickorder

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
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.snainfotech.tagscout.ui.theme.WarningOrange
import kotlinx.coroutines.delay

@Composable
fun PickOrderScreen(
    state: PickOrderState,
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
    onTabSelect: (PickOrderTab) -> Unit = {},
    onStartPicking: () -> Unit = {},
    onPausePicking: () -> Unit = {},
    onResumePicking: () -> Unit = {},
    onConfirmPick: (Int) -> Unit = {},           // serialNo of item
    onLocateItem: (String) -> Unit = {},          // epc of missing item
    onAddUnexpected: (String) -> Unit = {},
    onDismissSnackbar: () -> Unit = {},
    onCompleteOrder: () -> Unit = {},
    onCancelOrder: () -> Unit = {},
    isItemReadyToConfirm: (PickOrderItem) -> Boolean = { false },
    modifier: Modifier = Modifier
) {
    // Trigger periodic recomposition while picking (for proximity threshold UI)
    var tickCounter by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.isPicking) {
        while (state.isPicking) {
            delay(200L)
            tickCounter++
        }
    }

    val timerBadge = when {
        state.isPicking -> TimerBadge.Live("Picking")
        state.phase == PickOrderPhase.LOADED -> TimerBadge.Paused("Ready")
        else -> TimerBadge.None
    }

    Column(modifier = modifier.fillMaxSize().background(LightGray)) {

        AppHeader(
            title = if (state.orderNumber.isNotEmpty()) state.orderNumber else "Pick Order",
            showBackButton = !state.isPicking,
            onBackClick = onBackClick,
            onMenuClick = onMenuClick,
            timerBadge = timerBadge
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
            enabled = !state.isPicking,
            onValueChange = onAntennaChange
        )

        when (state.phase) {
            PickOrderPhase.EMPTY -> Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                EmptyState(
                    isDeviceConnected = isDeviceConnected,
                    onLoadFileClick = onLoadFileClick
                )
            }
            PickOrderPhase.LOADED, PickOrderPhase.PICKING -> Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LoadedContent(
                    state = state,
                    isItemReadyToConfirm = isItemReadyToConfirm,
                    isDeviceConnected = isDeviceConnected,
                    tickCounter = tickCounter,
                    onTabSelect = onTabSelect,
                    onStartPicking = onStartPicking,
                    onPausePicking = onPausePicking,
                    onResumePicking = onResumePicking,
                    onConfirmPick = onConfirmPick,
                    onLocateItem = onLocateItem,
                    onAddUnexpected = onAddUnexpected,
                    onCompleteOrder = onCompleteOrder,
                    onCancelOrder = onCancelOrder
                )
            }
            PickOrderPhase.COMPLETE -> Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                CompleteState(
                    state = state,
                    onCancelOrder = onCancelOrder
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
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "📋", fontSize = 64.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Pick Order",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = DarkText
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Upload a pick list Excel file to start fulfilling a customer order.",
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
            Text("📁 Upload Pick List", fontSize = 14.sp)
        }
        if (!isDeviceConnected) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "⚠ Connect a device first",
                fontSize = 12.sp,
                color = ErrorRed
            )
        }
    }
}

// ============================================
// LOADED CONTENT
// ============================================

@Composable
private fun LoadedContent(
    state: PickOrderState,
    isItemReadyToConfirm: (PickOrderItem) -> Boolean,
    isDeviceConnected: Boolean,
    tickCounter: Long,
    onTabSelect: (PickOrderTab) -> Unit,
    onStartPicking: () -> Unit,
    onPausePicking: () -> Unit,
    onResumePicking: () -> Unit,
    onConfirmPick: (Int) -> Unit,
    onLocateItem: (String) -> Unit,
    onAddUnexpected: (String) -> Unit,
    onCompleteOrder: () -> Unit,
    onCancelOrder: () -> Unit
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
                    text = "📁 ${state.orderFilename}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = DarkText
                )
                Text(
                    text = "${state.totalItems} items · ${state.pickedCount} picked · ${state.remainingCount} left",
                    fontSize = 10.sp,
                    color = MediumGray
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Tab row
        TabRow(
            selectedTab = state.selectedTab,
            pickListCount = "${state.pickedCount}/${state.totalItems}",
            unexpectedCount = state.unexpectedItems.size,
            onTabSelect = onTabSelect
        )

        // Content per tab
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (state.selectedTab) {
                PickOrderTab.PICK_LIST -> PickListView(
                    state = state,
                    isItemReadyToConfirm = isItemReadyToConfirm,
                    tickCounter = tickCounter,
                    onConfirmPick = onConfirmPick,
                    onLocateItem = onLocateItem
                )
                PickOrderTab.UNEXPECTED -> UnexpectedListView(
                    unexpectedItems = state.unexpectedItems,
                    onAddUnexpected = onAddUnexpected
                )
            }
        }

        // Wrong EPC snackbar (rendered above the button bar)
        if (state.showWrongEpcSnackbar) {
            WrongEpcSnackbar(
                message = state.wrongEpcMessage,
                onAdd = {
                    val latestUnexpected = state.unexpectedItems.lastOrNull()
                    if (latestUnexpected != null) {
                        onAddUnexpected(latestUnexpected.epc)
                    }
                }
            )
        }

        // Bottom button bar
        BottomButtons(
            state = state,
            isDeviceConnected = isDeviceConnected,
            onStartPicking = onStartPicking,
            onPausePicking = onPausePicking,
            onResumePicking = onResumePicking,
            onCompleteOrder = onCompleteOrder,
            onCancelOrder = onCancelOrder
        )
    }
}

// ============================================
// TABS
// ============================================

@Composable
private fun TabRow(
    selectedTab: PickOrderTab,
    pickListCount: String,
    unexpectedCount: Int,
    onTabSelect: (PickOrderTab) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(LightGray)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TabButton(
            label = "Pick List",
            count = pickListCount,
            isSelected = selectedTab == PickOrderTab.PICK_LIST,
            onClick = { onTabSelect(PickOrderTab.PICK_LIST) },
            modifier = Modifier.weight(1f)
        )
        TabButton(
            label = "Unexpected",
            count = "$unexpectedCount",
            isSelected = selectedTab == PickOrderTab.UNEXPECTED,
            onClick = { onTabSelect(PickOrderTab.UNEXPECTED) },
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
// PICK LIST VIEW (grouped by bin)
// ============================================

@Composable
private fun PickListView(
    state: PickOrderState,
    isItemReadyToConfirm: (PickOrderItem) -> Boolean,
    tickCounter: Long,  // Forces recomposition periodically
    onConfirmPick: (Int) -> Unit,
    onLocateItem: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().background(Color.White)) {

        // Progress bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Pick Progress",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DarkText,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${state.pickedCount}/${state.totalItems}  (${state.progressPercent}%)",
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

        // Grouped list
        val grouped = state.itemsByBin
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            grouped.forEach { (bin, items) ->
                item {
                    BinHeader(binNumber = bin, items = items)
                }
                items(items, key = { it.serialNo }) { item ->
                    PickItemRow(
                        item = item,
                        isReadyToConfirm = isItemReadyToConfirm(item),
                        isPicking = state.isPicking,
                        onConfirmPick = { onConfirmPick(item.serialNo) },
                        onLocateItem = { onLocateItem(item.epc) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BinHeader(binNumber: String, items: List<PickOrderItem>) {
    val pickedInBin = items.count { it.isPicked }
    val totalInBin = items.size
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(LightGray)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "📦 Bin $binNumber",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = DarkText,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "$pickedInBin/$totalInBin",
            fontSize = 10.sp,
            color = MediumGray,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun PickItemRow(
    item: PickOrderItem,
    isReadyToConfirm: Boolean,
    isPicking: Boolean,
    onConfirmPick: () -> Unit,
    onLocateItem: () -> Unit
) {
    val bgColor = when {
        item.isPicked -> Color(0xFFE8F5E9)  // Green tint for picked
        isReadyToConfirm -> Color(0xFFFFF3E0)  // Amber tint when ready to confirm
        else -> Color.White
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Status icon
            Text(
                text = when {
                    item.isPicked -> "✓"
                    isReadyToConfirm -> "🎯"
                    else -> "○"
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = when {
                    item.isPicked -> SuccessGreen
                    isReadyToConfirm -> WarningOrange
                    else -> MediumGray
                },
                modifier = Modifier.width(28.dp)
            )

            // Item info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.productName,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = DarkText
                )
                Text(
                    text = item.epc.take(20) + if (item.epc.length > 20) "..." else "",
                    fontSize = 10.sp,
                    color = MediumGray,
                    fontFamily = FontFamily.Monospace
                )
                if (item.isPicked && item.pickedAt != null) {
                    Text(
                        text = "Picked at ${formatTime(item.pickedAt)}",
                        fontSize = 9.sp,
                        color = SuccessGreen
                    )
                }
                if (isReadyToConfirm && !item.isPicked) {
                    Text(
                        text = "Signal: ${signalLabel(item.currentRssi)} (${item.currentRssi} dBm)",
                        fontSize = 9.sp,
                        color = signalColor(item.currentRssi)
                    )
                }
            }

            // Action button
            if (isReadyToConfirm && !item.isPicked) {
                Button(
                    onClick = onConfirmPick,
                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                    modifier = Modifier.height(32.dp).padding(start = 4.dp)
                ) {
                    Text("Confirm", fontSize = 11.sp)
                }
            } else if (!item.isPicked && isPicking) {
                OutlinedButton(
                    onClick = onLocateItem,
                    modifier = Modifier.height(32.dp).padding(start = 4.dp)
                ) {
                    Text("🔍 Locate", fontSize = 10.sp)
                }
            }
        }
    }
}

// ============================================
// UNEXPECTED LIST VIEW
// ============================================

@Composable
private fun UnexpectedListView(
    unexpectedItems: List<UnexpectedItem>,
    onAddUnexpected: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().background(Color.White)) {
        if (unexpectedItems.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "✨", fontSize = 32.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No unexpected tags yet",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = DarkText
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Tags scanned but not on the pick list will appear here",
                        fontSize = 11.sp,
                        color = MediumGray
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(unexpectedItems, key = { it.epc }) { item ->
                    UnexpectedItemRow(item = item, onAddUnexpected = onAddUnexpected)
                }
            }
        }
    }
}

@Composable
private fun UnexpectedItemRow(item: UnexpectedItem, onAddUnexpected: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (item.includedInOrder) Color(0xFFE8F5E9) else Color.White)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .border(0.5.dp, BorderGray),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (item.includedInOrder) "✓" else "?",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = if (item.includedInOrder) SuccessGreen else WarningOrange,
            modifier = Modifier.width(24.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.epc.take(20),
                fontSize = 11.sp,
                color = DarkText,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = if (item.includedInOrder) "Added to order" else "Not on pick list",
                fontSize = 9.sp,
                color = MediumGray
            )
        }
        if (!item.includedInOrder) {
            OutlinedButton(
                onClick = { onAddUnexpected(item.epc) },
                modifier = Modifier.height(28.dp)
            ) {
                Text("Add", fontSize = 10.sp)
            }
        }
    }
}

// ============================================
// SNACKBAR (Wrong EPC)
// ============================================

@Composable
private fun WrongEpcSnackbar(
    message: String,
    onAdd: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(WarningOrange)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "⚠ $message",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            Text(
                text = "Not on this order",
                fontSize = 10.sp,
                color = Color.White.copy(alpha = 0.9f)
            )
        }
        Button(
            onClick = onAdd,
            colors = ButtonDefaults.buttonColors(containerColor = Color.White),
            modifier = Modifier.height(32.dp)
        ) {
            Text("Add", fontSize = 11.sp, color = WarningOrange, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ============================================
// COMPLETE STATE
// ============================================

@Composable
private fun CompleteState(
    state: PickOrderState,
    onCancelOrder: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "✅", fontSize = 64.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Order Complete",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = SuccessGreen
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Order ${state.orderNumber}",
            fontSize = 12.sp,
            color = MediumGray,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "${state.pickedCount} of ${state.totalItems} items picked",
            fontSize = 14.sp,
            color = DarkText
        )
        if (state.unexpectedItems.count { it.includedInOrder } > 0) {
            Text(
                text = "+ ${state.unexpectedItems.count { it.includedInOrder }} additional items added",
                fontSize = 12.sp,
                color = MediumGray
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
        // Placeholder: Excel export button (Phase F)
        Button(
            onClick = { /* TODO: Phase F */ },
            colors = ButtonDefaults.buttonColors(containerColor = Primary),
            modifier = Modifier.height(48.dp)
        ) {
            Text("📊 Download Excel Report", fontSize = 14.sp)
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(
            onClick = onCancelOrder,
            modifier = Modifier.height(40.dp)
        ) {
            Text("Start New Order", fontSize = 12.sp)
        }
    }
}

// ============================================
// BOTTOM BUTTONS
// ============================================

@Composable
private fun BottomButtons(
    state: PickOrderState,
    isDeviceConnected: Boolean,
    onStartPicking: () -> Unit,
    onPausePicking: () -> Unit,
    onResumePicking: () -> Unit,
    onCompleteOrder: () -> Unit,
    onCancelOrder: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = onCancelOrder,
            enabled = !state.isPicking,
            modifier = Modifier.weight(1f).height(44.dp)
        ) {
            Text("🗑 Cancel", fontSize = 12.sp)
        }

        val middleAction: () -> Unit
        val middleText: String
        val middleColor: Color

        when {
            state.isPicking -> {
                middleAction = onPausePicking
                middleText = "⏸ Pause"
                middleColor = WarningOrange
            }
            state.phase == PickOrderPhase.LOADED && !state.isPicking && state.pickedCount == 0 -> {
                middleAction = onStartPicking
                middleText = "▶ Start"
                middleColor = SuccessGreen
            }
            !state.isPicking && state.pickedCount > 0 && !state.isFullyPicked -> {
                middleAction = onResumePicking
                middleText = "▶ Resume"
                middleColor = SuccessGreen
            }
            else -> {
                middleAction = onStartPicking
                middleText = "▶ Start"
                middleColor = SuccessGreen
            }
        }

        Button(
            onClick = middleAction,
            enabled = isDeviceConnected,
            colors = ButtonDefaults.buttonColors(
                containerColor = middleColor,
                disabledContainerColor = Disabled,
                disabledContentColor = DisabledText
            ),
            modifier = Modifier.weight(1f).height(44.dp)
        ) {
            Text(text = middleText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }

        Button(
            onClick = onCompleteOrder,
            enabled = !state.isPicking && state.pickedCount > 0,
            colors = ButtonDefaults.buttonColors(
                containerColor = Primary,
                disabledContainerColor = Disabled,
                disabledContentColor = DisabledText
            ),
            modifier = Modifier.weight(1f).height(44.dp)
        ) {
            Text("✓ Complete", fontSize = 12.sp)
        }
    }
}

// ============================================
// HELPERS
// ============================================

private fun formatTime(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}

private fun signalLabel(rssi: Int): String = when {
    rssi >= -40 -> "Excellent"
    rssi >= -50 -> "Good"
    rssi >= -60 -> "Fair"
    else -> "Weak"
}

private fun signalColor(rssi: Int): Color = when {
    rssi >= -40 -> SuccessGreen
    rssi >= -50 -> SuccessGreen
    rssi >= -60 -> WarningOrange
    else -> ErrorRed
}