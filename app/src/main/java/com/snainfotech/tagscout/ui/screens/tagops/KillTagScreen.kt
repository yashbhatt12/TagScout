package com.snainfotech.tagscout.ui.screens.tagops

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snainfotech.tagscout.ui.components.AntennaSlider
import com.snainfotech.tagscout.ui.components.DeviceStatusComponent
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

// Custom red colors specific to Kill Tag screen
private val KillRed = Color(0xFFC92A2A)
private val KillRedDark = Color(0xFF862020)
private val WarningBg = Color(0xFFFFF3F3)

@Composable
fun KillTagScreen(
    state: KillTagState,
    deviceName: String,
    serialNumber: String,
    firmwareVersion: String,
    batteryPercent: Int,
    isDeviceConnected: Boolean,
    onBackClick: () -> Unit = {},
    onMenuClick: () -> Unit = {},
    onDeviceStatusClick: () -> Unit = {},
    onAntennaChange: (Int) -> Unit = {},
    onTargetEpcChange: (String) -> Unit = {},
    onPasswordChange: (String) -> Unit = {},
    onFindTag: () -> Unit = {},
    onKillTag: () -> Unit = {},
    onConfirmIrreversibleChange: (Boolean) -> Unit = {},
    onConfirmCorrectTagChange: (Boolean) -> Unit = {},
    onRetry: () -> Unit = {},
    onStartOver: () -> Unit = {},
    canFindTag: Boolean = false,
    canKillTag: Boolean = false,
    isTargetEpcValid: Boolean = false,
    modifier: Modifier = Modifier
) {
    val isBusy = state.phase == KillPhase.SEARCHING || state.phase == KillPhase.KILLING

    Column(modifier = modifier.fillMaxSize().background(LightGray)) {

        // CUSTOM RED HEADER (not the standard AppHeader)
        KillTagHeader(
            showBackButton = !isBusy,
            onBackClick = onBackClick,
            onMenuClick = onMenuClick
        )

        // Device status
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
            enabled = !isBusy,
            onValueChange = onAntennaChange
        )

        // Phase-specific content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // PERSISTENT WARNING BANNER — always visible except during active operations
            if (!isBusy && state.phase != KillPhase.KILL_SUCCESS) {
                WarningBanner()
                Spacer(modifier = Modifier.height(12.dp))
            }

            when (state.phase) {
                KillPhase.ENTER_TARGET -> EnterTargetSection(
                    targetEpc = state.targetEpc,
                    onTargetEpcChange = onTargetEpcChange,
                    isValid = isTargetEpcValid,
                    canProceed = canFindTag,
                    onFindTag = onFindTag,
                    isDeviceConnected = isDeviceConnected
                )

                KillPhase.SEARCHING -> SearchingSection(targetEpc = state.targetEpc)

                KillPhase.TAG_NOT_FOUND -> TagNotFoundSection(
                    targetEpc = state.targetEpc,
                    errorMessage = state.killError,
                    onRetry = onRetry
                )

                KillPhase.TAG_FOUND -> TagFoundSection(
                    targetEpc = state.targetEpc,
                    rssi = state.foundTagRssi,
                    password = state.killPassword,
                    onPasswordChange = onPasswordChange,
                    confirmIrreversible = state.confirmIrreversible,
                    onConfirmIrreversibleChange = onConfirmIrreversibleChange,
                    confirmCorrectTag = state.confirmCorrectTag,
                    onConfirmCorrectTagChange = onConfirmCorrectTagChange,
                    canKill = canKillTag,
                    onKill = onKillTag,
                    onCancel = onRetry
                )

                KillPhase.KILLING -> KillingSection(targetEpc = state.targetEpc)

                KillPhase.KILL_SUCCESS -> SuccessSection(
                    killedEpc = state.killedEpc,
                    onDone = onBackClick
                )

                KillPhase.KILL_FAILURE -> FailureSection(
                    errorMessage = state.killError,
                    onStartOver = onStartOver
                )
            }
        }
    }
}

// ============================================
// CUSTOM RED HEADER
// ============================================

@Composable
private fun KillTagHeader(
    showBackButton: Boolean,
    onBackClick: () -> Unit,
    onMenuClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(KillRed, KillRedDark)
                )
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showBackButton) {
            Text(
                text = "←",
                fontSize = 40.sp,
                color = Color.White,
                modifier = Modifier
                    .size(40.dp)
                    .clickable { onBackClick() }
                    .padding(8.dp)
            )
        } else {
            Spacer(modifier = Modifier.width(40.dp))
        }

        Spacer(modifier = Modifier.width(4.dp))

        Text(
            text = "Kill Tag",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )

        Text(
            text = "⋮",
            fontSize = 22.sp,
            color = Color.White,
            modifier = Modifier
                .size(40.dp)
                .clickable { onMenuClick() }
                .padding(8.dp)
        )
    }
}

// ============================================
// WARNING BANNER (always visible)
// ============================================

@Composable
private fun WarningBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(WarningBg, RoundedCornerShape(6.dp))
            .border(2.dp, ErrorRed, RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "⚠️", fontSize = 18.sp)
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = "Permanent and irreversible",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = ErrorRed
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Killing a tag cannot be undone. The tag will be dead forever.",
                fontSize = 10.sp,
                color = DarkText
            )
        }
    }
}

// ============================================
// PHASE 1: ENTER TARGET EPC
// ============================================

@Composable
private fun EnterTargetSection(
    targetEpc: String,
    onTargetEpcChange: (String) -> Unit,
    isValid: Boolean,
    canProceed: Boolean,
    onFindTag: () -> Unit,
    isDeviceConnected: Boolean
) {
    SectionCard {
        StepHeader(stepNumber = 1, title = "Find the tag to kill")

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Enter the EPC of the tag you want to permanently kill.",
            fontSize = 12.sp,
            color = MediumGray
        )

        Spacer(modifier = Modifier.height(12.dp))

        EpcField(
            value = targetEpc,
            onChange = onTargetEpcChange,
            label = "Target EPC",
            enabled = isDeviceConnected
        )

        Spacer(modifier = Modifier.height(4.dp))

        if (targetEpc.isNotEmpty() && !isValid) {
            Text(
                text = "EPC must be exactly 24 or 32 hex characters (0-9, A-F)",
                fontSize = 10.sp,
                color = ErrorRed
            )
        } else {
            Text(
                text = "${targetEpc.length} / 24 or 32 hex chars",
                fontSize = 10.sp,
                color = MediumGray
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onFindTag,
            enabled = canProceed && isDeviceConnected,
            colors = ButtonDefaults.buttonColors(
                containerColor = Primary,
                disabledContainerColor = Disabled,
                disabledContentColor = DisabledText
            ),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text("🔍 Find Tag", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }

        if (!isDeviceConnected) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "⚠ Connect a device to continue",
                fontSize = 11.sp,
                color = ErrorRed
            )
        }
    }
}

// ============================================
// SEARCHING
// ============================================

@Composable
private fun SearchingSection(targetEpc: String) {
    SectionCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(color = KillRed)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Searching for tag...",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = DarkText
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = targetEpc,
                fontSize = 10.sp,
                color = MediumGray,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Hold the reader near the tag",
                fontSize = 11.sp,
                color = MediumGray
            )
        }
    }
}

// ============================================
// TAG NOT FOUND
// ============================================

@Composable
private fun TagNotFoundSection(
    targetEpc: String,
    errorMessage: String,
    onRetry: () -> Unit
) {
    SectionCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "❌ Tag Not Found",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = ErrorRed
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (errorMessage.isNotBlank()) errorMessage
                else "Could not find a tag with EPC:",
                fontSize = 12.sp,
                color = DarkText
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = targetEpc,
                fontSize = 11.sp,
                color = MediumGray,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = Primary),
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) {
                Text("Try Again", fontSize = 13.sp)
            }
        }
    }
}

// ============================================
// TAG FOUND — show confirmations + kill button
// ============================================

@Composable
private fun TagFoundSection(
    targetEpc: String,
    rssi: Int,
    password: String,
    onPasswordChange: (String) -> Unit,
    confirmIrreversible: Boolean,
    onConfirmIrreversibleChange: (Boolean) -> Unit,
    confirmCorrectTag: Boolean,
    onConfirmCorrectTagChange: (Boolean) -> Unit,
    canKill: Boolean,
    onKill: () -> Unit,
    onCancel: () -> Unit
) {
    // Found tag info
    SectionCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "✓ Tag Found",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = SuccessGreen
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = targetEpc,
                fontSize = 12.sp,
                color = DarkText,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(10.dp))
            SignalStrengthIndicator(rssi = rssi)
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Optional kill password
    SectionCard {
        StepHeader(stepNumber = 2, title = "Kill password (optional)")
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Leave blank to use factory default",
            fontSize = 10.sp,
            color = MediumGray
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    // CONFIRMATION CHECKBOXES — both required
    SectionCard {
        StepHeader(stepNumber = 3, title = "Confirm — both required")

        Spacer(modifier = Modifier.height(12.dp))

        ConfirmationCheckbox(
            checked = confirmIrreversible,
            onCheckedChange = onConfirmIrreversibleChange,
            text = "I understand this action cannot be undone"
        )

        Spacer(modifier = Modifier.height(8.dp))

        ConfirmationCheckbox(
            checked = confirmCorrectTag,
            onCheckedChange = onConfirmCorrectTagChange,
            text = "I have verified this is the correct tag"
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.weight(1f).height(52.dp)
        ) {
            Text("Cancel", fontSize = 13.sp)
        }

        Button(
            onClick = onKill,
            enabled = canKill,
            colors = ButtonDefaults.buttonColors(
                containerColor = KillRed,
                disabledContainerColor = Disabled,
                disabledContentColor = DisabledText
            ),
            modifier = Modifier.weight(1f).height(52.dp)
        ) {
            Text(
                text = "⚠\uFE0F KILL TAG",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ConfirmationCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    text: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(LightGray, RoundedCornerShape(4.dp))
            .border(
                width = 1.dp,
                color = if (checked) ErrorRed else BorderGray,
                shape = RoundedCornerShape(4.dp)
            )
            .clickable { onCheckedChange(!checked) }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = ErrorRed,
                uncheckedColor = BorderGray
            )
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            fontSize = 12.sp,
            color = DarkText,
            fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

// ============================================
// KILLING
// ============================================

@Composable
private fun KillingSection(targetEpc: String) {
    SectionCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(color = KillRed)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Killing tag...",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = KillRed
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = targetEpc,
                fontSize = 10.sp,
                color = MediumGray,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Keep the reader steady",
                fontSize = 11.sp,
                color = MediumGray
            )
        }
    }
}

// ============================================
// SUCCESS — no quick-retry button
// ============================================

@Composable
private fun SuccessSection(
    killedEpc: String,
    onDone: () -> Unit
) {
    SectionCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "💀 Tag Killed",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = KillRed
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "The following tag has been permanently disabled and will no longer respond to readers:",
                fontSize = 12.sp,
                color = DarkText
            )
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LightGray, RoundedCornerShape(4.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = killedEpc,
                    fontSize = 12.sp,
                    color = DarkText,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "💡 To kill another tag, go back to the home screen and re-enter Kill Tag.",
                fontSize = 10.sp,
                color = MediumGray
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    Button(
        onClick = onDone,
        colors = ButtonDefaults.buttonColors(containerColor = Primary),
        modifier = Modifier.fillMaxWidth().height(48.dp)
    ) {
        Text("Done", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ============================================
// FAILURE
// ============================================

@Composable
private fun FailureSection(
    errorMessage: String,
    onStartOver: () -> Unit
) {
    SectionCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "❌ Kill Failed",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = ErrorRed
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = errorMessage.ifBlank { "An unknown error occurred." },
                fontSize = 12.sp,
                color = DarkText
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "💡 The tag has NOT been killed and is still active.",
                fontSize = 11.sp,
                color = SuccessGreen,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onStartOver,
                colors = ButtonDefaults.buttonColors(containerColor = Primary),
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) {
                Text("Start Over", fontSize = 13.sp)
            }
        }
    }
}

// ============================================
// SHARED COMPONENTS
// ============================================

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(8.dp))
            .border(1.dp, BorderGray, RoundedCornerShape(8.dp))
            .padding(16.dp)
    ) {
        content()
    }
}

@Composable
private fun StepHeader(stepNumber: Int, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .background(Primary, RoundedCornerShape(50))
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text(
                text = "$stepNumber",
                fontSize = 10.sp,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = DarkText
        )
    }
}

@Composable
private fun EpcField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    enabled: Boolean
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input ->
            val filtered = input.filter { it.isLetterOrDigit() && it.uppercaseChar() in "0123456789ABCDEF" }
            onChange(filtered.take(32).uppercase())
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        textStyle = androidx.compose.ui.text.TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp
        )
    )
}

@Composable
private fun SignalStrengthIndicator(rssi: Int) {
    val signalLevel = when {
        rssi >= -40 -> 4
        rssi >= -50 -> 3
        rssi >= -60 -> 2
        else -> 1
    }

    val signalLabel = when (signalLevel) {
        4 -> "Excellent"
        3 -> "Good"
        2 -> "Fair"
        else -> "Weak"
    }

    val signalColor = when (signalLevel) {
        4, 3 -> SuccessGreen
        2 -> Color(0xFFFFA500)
        else -> ErrorRed
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Signal:",
                fontSize = 11.sp,
                color = MediumGray,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "$signalLabel ($rssi dBm)",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = signalColor
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { signalLevel / 4f },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = signalColor,
            trackColor = BorderGray
        )
        if (signalLevel <= 2) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "💡 Move closer for a more reliable kill",
                fontSize = 10.sp,
                color = MediumGray
            )
        }
    }
}