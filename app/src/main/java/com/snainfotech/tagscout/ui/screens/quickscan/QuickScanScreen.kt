package com.snainfotech.tagscout.ui.screens.quickscan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.snainfotech.tagscout.ui.components.AntennaSlider
import com.snainfotech.tagscout.ui.components.AppHeader
import com.snainfotech.tagscout.ui.components.BottomButtonBar
import com.snainfotech.tagscout.ui.components.DeviceStatusComponent
import com.snainfotech.tagscout.ui.components.ScanButtonState
import com.snainfotech.tagscout.ui.components.StatsCards
import com.snainfotech.tagscout.ui.components.TagDataTable
import com.snainfotech.tagscout.ui.components.TimerBadge
import com.snainfotech.tagscout.ui.screens.home.ConnectionStatus
import com.snainfotech.tagscout.ui.theme.LightGray

@Composable
fun QuickScanScreen(
    state: QuickScanState,
    // Device info (passed from parent - normally from device VM)
    deviceName: String = "RFR-901",
    serialNumber: String = "SN-123456",
    firmwareVersion: String = "v5.90.00.02",
    batteryPercent: Int = 85,
    // Callbacks
    onBackClick: () -> Unit = {},
    onMenuClick: () -> Unit = {},
    onAntennaChange: (Int) -> Unit = {},
    onPlayPauseClick: () -> Unit = {},
    onSaveClick: () -> Unit = {},
    onClearClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Decide button state based on scan state
    val buttonState = when {
        state.isScanning -> ScanButtonState.SCANNING
        state.isPaused -> ScanButtonState.PAUSED
        else -> ScanButtonState.INITIAL
    }

    // Decide timer badge based on state
    val timerBadge = when {
        state.isScanning -> TimerBadge.Live(formatTime(state.timeRemaining))
        state.isPaused -> TimerBadge.Paused(formatTime(state.timeRemaining))
        else -> TimerBadge.None
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(LightGray)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 1. Header with timer badge
            AppHeader(
                title = "TagScout",
                showBackButton = true,
                onBackClick = onBackClick,
                onMenuClick = onMenuClick,
                timerBadge = timerBadge
            )

            // 2. Device status
            DeviceStatusComponent(
                isConnected = true,
                deviceName = deviceName,
                serialNumber = serialNumber,
                firmwareVersion = firmwareVersion,
                batteryPercent = batteryPercent,
                connectionStatus = ConnectionStatus.CONNECTED
            )

            // 3. Scrollable content area
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
                    .padding(bottom = 90.dp), // Extra padding for fixed buttons
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
            ) {
                // Antenna slider (disabled during scan)
                AntennaSlider(
                    value = state.antennaStrength,
                    enabled = !state.isScanning,
                    onValueChange = onAntennaChange
                )

                // Stats cards (live colors when scanning)
                StatsCards(
                    label1 = "UNIQUE TAGS", value1 = state.uniqueTags.toString(),
                    label2 = "TOTAL TAGS", value2 = state.totalTags.toString(),
                    label3 = if (state.isPaused) "MAX/SEC" else "READ/SEC",
                    value3 = String.format("%.1f", state.readPerSecond),
                    isLive = state.isScanning
                )

                // Data table
                TagDataTable(tags = state.detectedTags)
            }
        }

        // 4. Fixed bottom buttons (overlay at bottom)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
        ) {
            BottomButtonBar(
                state = buttonState,
                onSaveClick = onSaveClick,
                onPlayPauseClick = onPlayPauseClick,
                onClearClick = onClearClick
            )
        }
    }
}

// Helper: convert seconds to MM:SS format
private fun formatTime(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

// ============================================
// PREVIEWS — 3 states
// ============================================

@Preview(showBackground = true, heightDp = 800)
@Composable
fun QuickScanInitialPreview() {
    QuickScanScreen(
        state = QuickScanState(
            isScanning = false,
            isPaused = false,
            antennaStrength = 5,
            timeRemaining = 180
        )
    )
}

@Preview(showBackground = true, heightDp = 800)
@Composable
fun QuickScanScanningPreview() {
    QuickScanScreen(
        state = QuickScanState(
            isScanning = true,
            isPaused = false,
            uniqueTags = 12,
            totalTags = 45,
            readPerSecond = 8.2f,
            antennaStrength = 5,
            timeRemaining = 165,  // 2:45
            detectedTags = listOf(
                DetectedTag("3004A1B2C3D4E5F6", -45, 5, 0),
                DetectedTag("3004A2B3C4D5E6F7", -52, 3, 0),
                DetectedTag("3004A3B4C5D6E7F8", -48, 4, 0)
            )
        )
    )
}

@Preview(showBackground = true, heightDp = 800)
@Composable
fun QuickScanPausedPreview() {
    QuickScanScreen(
        state = QuickScanState(
            isScanning = false,
            isPaused = true,
            uniqueTags = 12,
            totalTags = 45,
            readPerSecond = 8.2f,
            antennaStrength = 5,
            timeRemaining = 135,  // 2:15
            detectedTags = listOf(
                DetectedTag("3004A1B2C3D4E5F6", -45, 5, 0),
                DetectedTag("3004A2B3C4D5E6F7", -52, 3, 0),
                DetectedTag("3004A3B4C5D6E7F8", -48, 4, 0)
            )
        )
    )
}