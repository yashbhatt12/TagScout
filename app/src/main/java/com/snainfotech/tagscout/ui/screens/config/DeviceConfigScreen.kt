package com.snainfotech.tagscout.ui.screens.config

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snainfotech.tagscout.ui.components.AppHeader
import com.snainfotech.tagscout.ui.components.DeviceStatusComponent
import com.snainfotech.tagscout.ui.screens.home.ConnectionStatus
import com.snainfotech.tagscout.ui.theme.BorderGray
import com.snainfotech.tagscout.ui.theme.DarkText
import com.snainfotech.tagscout.ui.theme.ErrorRed
import com.snainfotech.tagscout.ui.theme.LightGray
import com.snainfotech.tagscout.ui.theme.MediumGray
import com.snainfotech.tagscout.ui.theme.Primary
import com.snainfotech.tagscout.ui.theme.SuccessBg
import com.snainfotech.tagscout.ui.theme.SuccessGreen
import com.snainfotech.tagscout.ui.theme.SuccessText
import com.snainfotech.tagscout.ui.theme.Disabled
import com.snainfotech.tagscout.ui.theme.DisabledText
import com.snainfotech.tagscout.ui.theme.InfoBg
import com.snainfotech.tagscout.ui.theme.InfoText

@Composable
fun DeviceConfigScreen(
    state: DeviceConfigState,
    deviceName: String = "RFR-901",
    serialNumber: String = "SN-123456",
    batteryPercent: Int = 85,
    isConnected: Boolean = true,
    firmwareVersion: String = "v5.90.00.02",
    onBackClick: () -> Unit = {},
    onMenuClick: () -> Unit = {},
    onBuzzerChange: (BuzzerLevel) -> Unit = {},
    onSleepTimeoutChange: (SleepTimeout) -> Unit = {},
    onResetClick: () -> Unit = {},
    onCheckFirmwareClick: () -> Unit = {},
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

        // Device status
        DeviceStatusComponent(
            isConnected = isConnected,
            deviceName = deviceName,
            serialNumber = serialNumber,
            firmwareVersion = state.firmwareVersion,
            batteryPercent = batteryPercent,
            connectionStatus = if (isConnected) ConnectionStatus.CONNECTED else ConnectionStatus.DISCONNECTED
        )

        // Scrollable settings
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Warning banner when device disconnected
            if (!isConnected) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(InfoBg)
                        .padding(12.dp)
                ) {
                    Text(
                        text = "ℹ️ Device must be connected to reset or update firmware. Connect a device first to enable these actions.",
                        color = InfoText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            // === BUZZER LEVEL ===
            SettingGroup(
                icon = "🔊",
                label = "Buzzer Level",
                description = "Control notification beeps"
            ) {
                CustomDropdown(
                    selectedValue = state.buzzerLevel.label,
                    options = BuzzerLevel.values().map { it.label },
                    onSelected = { selectedLabel ->
                        val level = BuzzerLevel.values().find { it.label == selectedLabel }
                        if (level != null) onBuzzerChange(level)
                    }
                )
            }

            // === AUTO SLEEP TIMEOUT ===
            SettingGroup(
                icon = "💤",
                label = "Auto Sleep Timeout",
                description = "Device enters sleep mode after inactivity"
            ) {
                CustomDropdown(
                    selectedValue = state.sleepTimeout.label,
                    options = SleepTimeout.values().map { it.label },
                    onSelected = { selectedLabel ->
                        val timeout = SleepTimeout.values().find { it.label == selectedLabel }
                        if (timeout != null) onSleepTimeoutChange(timeout)
                    }
                )
            }

            // === SUCCESS TOAST (auto-dismissing) ===
            if (state.showSettingChangedToast.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(SuccessBg)
                        .padding(12.dp)
                ) {
                    Text(
                        text = state.showSettingChangedToast,
                        color = SuccessText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // === RESET DEVICE ===
            SettingGroup(
                icon = "🔄",
                label = "Reset Device",
                description = "Restore device to factory defaults"
            ) {
                Button(
                    onClick = onResetClick,
                    enabled = isConnected,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ErrorRed,
                        disabledContainerColor = Disabled,
                        disabledContentColor = DisabledText
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "Reset Device",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // === UPDATE FIRMWARE ===
            SettingGroup(
                icon = "📥",
                label = "Update Firmware",
                description = "Current: ${state.firmwareVersion}"
            ) {
                Button(
                    onClick = onCheckFirmwareClick,
                    enabled = isConnected,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Primary,
                        disabledContainerColor = Disabled,
                        disabledContentColor = DisabledText
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "Check for Updates",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

// ============================================
// HELPER: Setting group (icon + label + description + content)
// ============================================

@Composable
private fun SettingGroup(
    icon: String,
    label: String,
    description: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = icon, fontSize = 16.sp)
            Spacer(modifier = Modifier.padding(start = 6.dp))
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = DarkText
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = description,
            fontSize = 11.sp,
            color = MediumGray
        )
        Spacer(modifier = Modifier.height(10.dp))
        content()
    }
}

// ============================================
// HELPER: Custom dropdown
// ============================================

@Composable
private fun CustomDropdown(
    selectedValue: String,
    options: List<String>,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, BorderGray, RoundedCornerShape(8.dp))
                .background(Color.White)
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = selectedValue,
                fontSize = 14.sp,
                color = DarkText,
                modifier = Modifier.weight(1f)
            )
            Text(text = "▼", fontSize = 10.sp, color = MediumGray)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

// ============================================
// PREVIEWS
// ============================================

@Preview(showBackground = true, heightDp = 800)
@Composable
fun DeviceConfigScreenConnectedPreview() {
    DeviceConfigScreen(
        state = DeviceConfigState(),
        isConnected = true
    )
}

@Preview(showBackground = true, heightDp = 800)
@Composable
fun DeviceConfigScreenDisconnectedPreview() {
    DeviceConfigScreen(
        state = DeviceConfigState(),
        isConnected = false
    )
}