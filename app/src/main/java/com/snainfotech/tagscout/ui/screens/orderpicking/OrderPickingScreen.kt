package com.snainfotech.tagscout.ui.screens.orderpicking

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snainfotech.tagscout.ui.components.AntennaSlider
import com.snainfotech.tagscout.ui.components.AppHeader
import com.snainfotech.tagscout.ui.components.BottomButtonBar
import com.snainfotech.tagscout.ui.components.DeviceStatusComponent
import com.snainfotech.tagscout.ui.components.ScanButtonState
import com.snainfotech.tagscout.ui.components.TimerBadge
import com.snainfotech.tagscout.ui.screens.home.ConnectionStatus
import com.snainfotech.tagscout.ui.theme.BorderGray
import com.snainfotech.tagscout.ui.theme.DarkText
import com.snainfotech.tagscout.ui.theme.LightGray
import com.snainfotech.tagscout.ui.theme.MediumGray
import com.snainfotech.tagscout.ui.theme.Primary
import com.snainfotech.tagscout.ui.theme.SuccessGreen
import com.snainfotech.tagscout.ui.theme.TableRowFound
import com.snainfotech.tagscout.ui.theme.TableRowPending

@Composable
fun OrderPickingScreen(
    state: OrderPickingState,
    deviceName: String,
    serialNumber: String,
    firmwareVersion: String,
    batteryPercent: Int,
    isDeviceConnected: Boolean,
    onBackClick: () -> Unit = {},
    onMenuClick: () -> Unit = {},
    onDeviceStatusClick: () -> Unit = {},
    onUploadFileClick: () -> Unit = {},
    onAntennaChange: (Int) -> Unit = {},
    onPlayPauseClick: () -> Unit = {},
    onSaveClick: () -> Unit = {},
    onClearClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val buttonState = when {
        state.isScanning -> ScanButtonState.SCANNING
        state.isPaused -> ScanButtonState.PAUSED
        else -> ScanButtonState.INITIAL
    }

    val connectionStatus = if (isDeviceConnected) ConnectionStatus.CONNECTED else ConnectionStatus.DISCONNECTED

    Column(modifier = modifier.fillMaxSize().background(LightGray)) {

        AppHeader(
            title = "Order Picking",
            showBackButton = !state.isScanning,
            onBackClick = onBackClick,
            onMenuClick = onMenuClick,
            timerBadge = TimerBadge.None
        )

        DeviceStatusComponent(
            isConnected = isDeviceConnected,
            deviceName = deviceName,
            serialNumber = serialNumber,
            firmwareVersion = firmwareVersion,
            batteryPercent = batteryPercent,
            connectionStatus = connectionStatus,
            onClick = onDeviceStatusClick
        )

        if (!state.hasFileLoaded) {
            UploadPrompt(isParsing = state.isParsingFile, onUploadFileClick = onUploadFileClick)
        } else {
            Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                OrderSummaryCard(state)

                AntennaSlider(
                    value = state.antennaPower,
                    enabled = !state.isScanning,
                    onValueChange = onAntennaChange,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)
                )

                Text(
                    text = "Order Items",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = DarkText,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                )

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                ) {
                    items(state.items, key = { it.rowIndex }) { item ->
                        OrderItemRow(item)
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            }

            BottomButtonBar(
                state = buttonState,
                onSaveClick = onSaveClick,
                onPlayPauseClick = onPlayPauseClick,
                onClearClick = onClearClick
            )
        }
    }
}

@Composable
private fun UploadPrompt(isParsing: Boolean, onUploadFileClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(64.dp))
        Text(
            text = "📋",
            fontSize = 48.sp
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No Order Loaded",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = DarkText
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Upload an Order Picking file (.xlsx) with Product Name, Product ID, " +
                    "EPC Code, Bin Number, and Order ID columns to begin.",
            fontSize = 13.sp,
            color = MediumGray
        )
        Spacer(modifier = Modifier.height(24.dp))

        if (isParsing) {
            CircularProgressIndicator(color = Primary)
        } else {
            Button(
                onClick = onUploadFileClick,
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) {
                Text("Upload Order Picking File")
            }
        }
    }
}

@Composable
private fun OrderSummaryCard(state: OrderPickingState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .padding(14.dp)
    ) {
        Text(
            text = state.fileName,
            fontSize = 12.sp,
            color = MediumGray
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SummaryStat(label = "Total", value = state.totalCount.toString())
            SummaryStat(label = "Picked", value = state.pickedCount.toString(), color = SuccessGreen)
            SummaryStat(label = "Remaining", value = state.remainingCount.toString())
        }
        Spacer(modifier = Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { state.progressPercent / 100f },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = SuccessGreen,
            trackColor = BorderGray
        )
    }
}

@Composable
private fun SummaryStat(label: String, value: String, color: Color = DarkText) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
        Text(text = label, fontSize = 11.sp, color = MediumGray)
    }
}

@Composable
private fun OrderItemRow(item: OrderPickingItem) {
    val bgColor = if (item.picked) TableRowFound else TableRowPending
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(1.dp, BorderGray, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = item.productName, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = DarkText)
            Text(text = "ID: ${item.productId}  •  Bin: ${item.binNumber}", fontSize = 11.sp, color = MediumGray)
            Text(text = "EPC: ${item.epc}", fontSize = 10.sp, color = MediumGray)
        }
        Text(
            text = if (item.picked) "✓ Picked" else "Pending",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (item.picked) SuccessGreen else MediumGray
        )
    }
}