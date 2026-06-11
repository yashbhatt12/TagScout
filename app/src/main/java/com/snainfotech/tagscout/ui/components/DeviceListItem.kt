package com.snainfotech.tagscout.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import com.snainfotech.tagscout.ui.screens.connect.DeviceStatus
import com.snainfotech.tagscout.ui.screens.connect.DiscoveredDevice
import com.snainfotech.tagscout.ui.theme.BorderGray
import com.snainfotech.tagscout.ui.theme.DarkText
import com.snainfotech.tagscout.ui.theme.Disabled
import com.snainfotech.tagscout.ui.theme.DisabledText
import com.snainfotech.tagscout.ui.theme.InfoBg
import com.snainfotech.tagscout.ui.theme.InfoText
import com.snainfotech.tagscout.ui.theme.MediumGray
import com.snainfotech.tagscout.ui.theme.Primary
import com.snainfotech.tagscout.ui.theme.SuccessBg
import com.snainfotech.tagscout.ui.theme.SuccessGreen
import com.snainfotech.tagscout.ui.theme.SuccessText

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DeviceListItem(
    device: DiscoveredDevice,
    isCurrentlyConnected: Boolean = false,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isInteractive = device.status != DeviceStatus.OUT_OF_RANGE
    val isFaded = device.status == DeviceStatus.OUT_OF_RANGE

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .alpha(if (isFaded) 0.4f else 1f),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrentlyConnected) SuccessBg else Color.White
        ),
        border = BorderStroke(
            width = if (isCurrentlyConnected) 2.dp else 1.dp,
            color = if (isCurrentlyConnected) SuccessGreen else BorderGray
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .let {
                    if (isInteractive) {
                        it.combinedClickable(
                            onClick = { onClick() },
                            onLongClick = { onLongPress() }
                        )
                    } else it
                }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Device icon
            Text(
                text = "📱",
                fontSize = 24.sp
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Device info (name + signal)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DarkText
                )
                Spacer(modifier = Modifier.height(2.dp))

                if (device.status == DeviceStatus.OUT_OF_RANGE) {
                    Text(
                        text = "Out of range",
                        fontSize = 11.sp,
                        color = MediumGray
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SignalBars(bars = device.signalBars)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = signalLabel(device.signalBars),
                            fontSize = 11.sp,
                            color = MediumGray
                        )
                    }
                }
            }

            // Status badge on the right
            StatusBadge(
                status = device.status,
                isSaved = device.isSaved,
                isCurrentlyConnected = isCurrentlyConnected
            )
        }
    }
}

@Composable
private fun SignalBars(bars: Int) {
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        repeat(4) { index ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((4 + index * 2).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(
                        if (index < bars) Primary else BorderGray
                    )
            )
        }
    }
}

@Composable
private fun StatusBadge(
    status: DeviceStatus,
    isSaved: Boolean,
    isCurrentlyConnected: Boolean
) {
    val (bgColor, textColor, label) = when {
        isCurrentlyConnected -> Triple(SuccessBg, SuccessText, "CONNECTED")
        status == DeviceStatus.IN_RANGE && isSaved -> Triple(InfoBg, InfoText, "SWITCH")
        status == DeviceStatus.IN_RANGE && !isSaved -> Triple(InfoBg, InfoText, "TAP TO CONNECT")
        status == DeviceStatus.OUT_OF_RANGE -> Triple(Disabled, DisabledText, "OUT OF RANGE")
        else -> Triple(Disabled, DisabledText, "—")
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = textColor
        )
    }
}

private fun signalLabel(bars: Int): String = when (bars) {
    4 -> "Strong Signal"
    3 -> "Good Signal"
    2 -> "Fair Signal"
    1 -> "Weak Signal"
    else -> "No Signal"
}

// ============================================
// PREVIEWS
// ============================================

@Preview(showBackground = true)
@Composable
fun DeviceListItemConnectedPreview() {
    DeviceListItem(
        device = DiscoveredDevice(
            id = "ABC123",
            name = "RFR-901 (ABC123)",
            signalBars = 4,
            status = DeviceStatus.CONNECTED,
            isSaved = true
        ),
        isCurrentlyConnected = true,
        onClick = {}
    )
}

@Preview(showBackground = true)
@Composable
fun DeviceListItemInRangeSwitchPreview() {
    DeviceListItem(
        device = DiscoveredDevice(
            id = "XYZ789",
            name = "RFR-901 (XYZ789)",
            signalBars = 3,
            status = DeviceStatus.IN_RANGE,
            isSaved = true
        ),
        onClick = {}
    )
}

@Preview(showBackground = true)
@Composable
fun DeviceListItemNewDevicePreview() {
    DeviceListItem(
        device = DiscoveredDevice(
            id = "NEW001",
            name = "RFR-901 (NEW001)",
            signalBars = 2,
            status = DeviceStatus.IN_RANGE,
            isSaved = false
        ),
        onClick = {}
    )
}

@Preview(showBackground = true)
@Composable
fun DeviceListItemOutOfRangePreview() {
    DeviceListItem(
        device = DiscoveredDevice(
            id = "OLD001",
            name = "RFR-900 (OLD001)",
            signalBars = 0,
            status = DeviceStatus.OUT_OF_RANGE,
            isSaved = true
        ),
        onClick = {}
    )
}