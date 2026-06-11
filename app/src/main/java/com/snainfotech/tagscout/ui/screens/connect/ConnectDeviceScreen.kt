package com.snainfotech.tagscout.ui.screens.connect

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snainfotech.tagscout.ui.components.AppHeader
import com.snainfotech.tagscout.ui.components.DeviceListItem
import com.snainfotech.tagscout.ui.theme.BorderGray
import com.snainfotech.tagscout.ui.theme.DarkText
import com.snainfotech.tagscout.ui.theme.InfoBg
import com.snainfotech.tagscout.ui.theme.InfoBlue
import com.snainfotech.tagscout.ui.theme.InfoText
import com.snainfotech.tagscout.ui.theme.LightGray
import com.snainfotech.tagscout.ui.theme.MediumGray
import com.snainfotech.tagscout.ui.theme.Primary
import com.snainfotech.tagscout.ui.theme.SuccessBg
import com.snainfotech.tagscout.ui.theme.SuccessGreen
import com.snainfotech.tagscout.ui.theme.SuccessText

@Composable
fun ConnectDeviceScreen(
    state: ConnectDeviceState,
    onBackClick: () -> Unit = {},
    onMenuClick: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    onSearchQueryChange: (String) -> Unit = {},
    onDeviceClick: (DiscoveredDevice) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LightGray)
    ) {
        // Header
        AppHeader(
            title = "TagScout",
            showBackButton = true,
            onBackClick = onBackClick,
            onMenuClick = onMenuClick
        )

        // Sub-header showing screen name
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = "🔌 Connect Device",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = DarkText
            )
        }

        // Scrollable content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Search bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search devices...", fontSize = 13.sp) },
                    singleLine = true,
                    enabled = !state.isSearching
                )

                Button(
                    onClick = onSearchClick,
                    enabled = !state.isSearching,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Primary,
                        disabledContainerColor = BorderGray
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (state.isSearching) "Searching..." else "Search",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Info banner — shown when a device is connected
            val connectedDevice = state.savedDevices.find { it.status == DeviceStatus.CONNECTED }
            if (connectedDevice != null) {
                InfoBanner(
                    text = "✓ ${connectedDevice.name} is currently connected",
                    bgColor = SuccessBg,
                    textColor = SuccessText
                )
            }

            // Searching spinner
            if (state.isSearching) {
                SearchingIndicator()
            }

            // Saved Devices section
            if (state.savedDevices.isNotEmpty()) {
                SectionTitle(text = "📱 Saved Devices")
                state.savedDevices.forEach { device ->
                    DeviceListItem(
                        device = device,
                        isCurrentlyConnected = device.id == state.currentlyConnectedId,
                        onClick = { onDeviceClick(device) }
                    )
                }
            }

            // New Devices section
            if (state.newDevices.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                SectionTitle(text = "🔍 New Devices Found")
                state.newDevices.forEach { device ->
                    DeviceListItem(
                        device = device,
                        isCurrentlyConnected = false,
                        onClick = { onDeviceClick(device) }
                    )
                }
            } else if (!state.isSearching && state.savedDevices.all { it.status == DeviceStatus.OUT_OF_RANGE }) {
                // Empty state — no devices at all
                Spacer(modifier = Modifier.height(20.dp))
                EmptyState(onRetry = onSearchClick)
            }

            // Bottom spacer
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

// ============================================
// HELPER COMPOSABLES
// ============================================

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = Primary,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun InfoBanner(text: String, bgColor: Color, textColor: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .padding(12.dp)
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = textColor,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun SearchingIndicator() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .padding(vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Rotating spinner emoji
            val infiniteTransition = rememberInfiniteTransition(label = "spinner")
            val rotation by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1500)
                ),
                label = "rotation"
            )

            Text(
                text = "🔄",
                fontSize = 36.sp,
                modifier = Modifier.rotate(rotation)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Searching nearby devices...",
                fontSize = 12.sp,
                color = Primary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun EmptyState(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "📭", fontSize = 48.sp)
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "No Devices Found",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = DarkText
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Make sure your RFID reader is powered on and nearby.",
            fontSize = 12.sp,
            color = MediumGray
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = Primary)
        ) {
            Text("Retry Search", fontSize = 13.sp)
        }
    }
}

// ============================================
// PREVIEWS
// ============================================

@Preview(showBackground = true, heightDp = 800)
@Composable
fun ConnectDeviceScreenNormalPreview() {
    ConnectDeviceScreen(
        state = ConnectDeviceState(
            savedDevices = listOf(
                DiscoveredDevice("ABC123", "RFR-901 (ABC123)", 4, DeviceStatus.CONNECTED, true),
                DiscoveredDevice("XYZ789", "RFR-901 (XYZ789)", 3, DeviceStatus.IN_RANGE, true)
            ),
            newDevices = listOf(
                DiscoveredDevice("NEW001", "RFR-901 (NEW001)", 3, DeviceStatus.IN_RANGE, false)
            ),
            currentlyConnectedId = "ABC123"
        )
    )
}

@Preview(showBackground = true, heightDp = 800)
@Composable
fun ConnectDeviceScreenAlreadyConnectedPreview() {
    ConnectDeviceScreen(
        state = ConnectDeviceState(
            savedDevices = listOf(
                DiscoveredDevice("ABC123", "RFR-901 (ABC123)", 4, DeviceStatus.CONNECTED, true),
                DiscoveredDevice("XYZ789", "RFR-901 (XYZ789)", 3, DeviceStatus.IN_RANGE, true),
                DiscoveredDevice("DEF456", "RFR-900 (DEF456)", 0, DeviceStatus.OUT_OF_RANGE, true)
            ),
            currentlyConnectedId = "ABC123"
        )
    )
}

@Preview(showBackground = true, heightDp = 800)
@Composable
fun ConnectDeviceScreenSearchingPreview() {
    ConnectDeviceScreen(
        state = ConnectDeviceState(
            isSearching = true,
            savedDevices = listOf(
                DiscoveredDevice("ABC123", "RFR-901 (ABC123)", 4, DeviceStatus.CONNECTED, true)
            ),
            currentlyConnectedId = "ABC123"
        )
    )
}

@Preview(showBackground = true, heightDp = 800)
@Composable
fun ConnectDeviceScreenNoDevicesPreview() {
    ConnectDeviceScreen(
        state = ConnectDeviceState(
            savedDevices = listOf(
                DiscoveredDevice("ABC123", "RFR-901 (ABC123)", 0, DeviceStatus.OUT_OF_RANGE, true),
                DiscoveredDevice("XYZ789", "RFR-901 (XYZ789)", 0, DeviceStatus.OUT_OF_RANGE, true)
            )
        )
    )
}