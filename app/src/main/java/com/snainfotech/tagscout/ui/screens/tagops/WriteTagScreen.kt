package com.snainfotech.tagscout.ui.screens.tagops

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snainfotech.tagscout.ui.components.AntennaSlider
import com.snainfotech.tagscout.ui.components.AppHeader
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

@Composable
fun WriteTagScreen(
    state: WriteTagState,
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
    onNewEpcChange: (String) -> Unit = {},
    onPasswordChange: (String) -> Unit = {},
    onFindTag: () -> Unit = {},
    onWriteTag: () -> Unit = {},
    onRetry: () -> Unit = {},
    onStartOver: () -> Unit = {},
    onWriteAnother: () -> Unit = {},
    canFindTag: Boolean = false,
    canWriteTag: Boolean = false,
    isTargetEpcValid: Boolean = false,
    isNewEpcValid: Boolean = false,
    modifier: Modifier = Modifier
) {
    val isBusy = state.phase == WritePhase.SEARCHING || state.phase == WritePhase.WRITING

    Column(modifier = modifier.fillMaxSize().background(LightGray)) {

        // Top bar
        AppHeader(
            title = "Write Tag",
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
            when (state.phase) {
                WritePhase.ENTER_TARGET -> EnterTargetSection(
                    targetEpc = state.targetEpc,
                    onTargetEpcChange = onTargetEpcChange,
                    isValid = isTargetEpcValid,
                    canProceed = canFindTag,
                    onFindTag = onFindTag,
                    isDeviceConnected = isDeviceConnected
                )

                WritePhase.SEARCHING -> SearchingSection(
                    targetEpc = state.targetEpc
                )

                WritePhase.TAG_NOT_FOUND -> TagNotFoundSection(
                    targetEpc = state.targetEpc,
                    errorMessage = state.writeError,
                    onRetry = onRetry
                )

                WritePhase.TAG_FOUND -> TagFoundSection(
                    targetEpc = state.targetEpc,
                    rssi = state.foundTagRssi,
                    newEpc = state.newEpc,
                    onNewEpcChange = onNewEpcChange,
                    isNewEpcValid = isNewEpcValid,
                    password = state.accessPassword,
                    onPasswordChange = onPasswordChange,
                    canWrite = canWriteTag,
                    onWriteTag = onWriteTag,
                    onCancel = onRetry
                )

                WritePhase.WRITING -> WritingSection(
                    targetEpc = state.targetEpc,
                    newEpc = state.newEpc
                )

                WritePhase.WRITE_SUCCESS -> SuccessSection(
                    previousEpc = state.previousEpc,
                    newEpc = state.verifiedNewEpc,
                    onWriteAnother = onWriteAnother,
                    onDone = onBackClick
                )

                WritePhase.WRITE_FAILURE -> FailureSection(
                    errorMessage = state.writeError,
                    onStartOver = onStartOver
                )
            }
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
        StepHeader(stepNumber = 1, title = "Find the target tag")

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Enter the EPC of the tag you want to rewrite.",
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
                text = "EPC must be exactly 16-32 hex characters (0-9, A-F)",
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
// PHASE: SEARCHING
// ============================================

@Composable
private fun SearchingSection(targetEpc: String) {
    SectionCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(color = Primary)
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
// PHASE: TAG NOT FOUND
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
            Text(
                text = "Try moving closer to the tag, or check the EPC.",
                fontSize = 11.sp,
                color = MediumGray
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
// PHASE: TAG FOUND (enter new EPC + password)
// ============================================

@Composable
private fun TagFoundSection(
    targetEpc: String,
    rssi: Int,
    newEpc: String,
    onNewEpcChange: (String) -> Unit,
    isNewEpcValid: Boolean,
    password: String,
    onPasswordChange: (String) -> Unit,
    canWrite: Boolean,
    onWriteTag: () -> Unit,
    onCancel: () -> Unit
) {
    // Success banner showing found tag info
    SectionCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "✓ Tag Found",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SuccessGreen
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = targetEpc,
                fontSize = 11.sp,
                color = MediumGray,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(10.dp))
            SignalStrengthIndicator(rssi = rssi)
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    SectionCard {
        StepHeader(stepNumber = 2, title = "New EPC")

        Spacer(modifier = Modifier.height(8.dp))

        EpcField(
            value = newEpc,
            onChange = onNewEpcChange,
            label = "Enter new EPC",
            enabled = true
        )

        Spacer(modifier = Modifier.height(4.dp))

        if (newEpc.isNotEmpty() && !isNewEpcValid) {
            Text(
                text = "EPC must be exactly 24 or 32 hex characters",
                fontSize = 10.sp,
                color = ErrorRed
            )
        } else {
            Text(
                text = "${newEpc.length} / 24 or 32 hex chars",
                fontSize = 10.sp,
                color = MediumGray
            )
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    SectionCard {
        StepHeader(stepNumber = 3, title = "Access password (optional)")

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

    Spacer(modifier = Modifier.height(16.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.weight(1f).height(48.dp)
        ) {
            Text("Search Another", fontSize = 13.sp)
        }

        Button(
            onClick = onWriteTag,
            enabled = canWrite,
            colors = ButtonDefaults.buttonColors(
                containerColor = Primary,
                disabledContainerColor = Disabled,
                disabledContentColor = DisabledText
            ),
            modifier = Modifier.weight(1f).height(48.dp)
        ) {
            Text("✏️ Write Tag", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
// ============================================
// PHASE: WRITING
// ============================================

@Composable
private fun WritingSection(targetEpc: String, newEpc: String) {
    SectionCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(color = Primary)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Writing tag...",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = DarkText
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "From: ${targetEpc}",
                fontSize = 10.sp,
                color = MediumGray,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "To:   ${newEpc}",
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
// PHASE: SUCCESS
// ============================================

@Composable
private fun SuccessSection(
    previousEpc: String,
    newEpc: String,
    onWriteAnother: () -> Unit,
    onDone: () -> Unit
) {
    SectionCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "✅ Tag Written Successfully",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = SuccessGreen
            )
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Previous EPC",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MediumGray
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = previousEpc,
                fontSize = 12.sp,
                color = DarkText,
                fontFamily = FontFamily.Monospace
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "New EPC (verified)",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = SuccessGreen
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = newEpc,
                fontSize = 12.sp,
                color = DarkText,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = onDone,
            modifier = Modifier.weight(1f).height(44.dp)
        ) {
            Text("Done", fontSize = 13.sp)
        }
        Button(
            onClick = onWriteAnother,
            colors = ButtonDefaults.buttonColors(containerColor = Primary),
            modifier = Modifier.weight(1f).height(44.dp)
        ) {
            Text("Write Another", fontSize = 13.sp)
        }
    }
}

// ============================================
// PHASE: FAILURE
// ============================================

@Composable
private fun FailureSection(
    errorMessage: String,
    onStartOver: () -> Unit
) {
    SectionCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "❌ Write Failed",
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
            // Only allow hex characters
            val filtered = input.filter { it.isLetterOrDigit() && it.uppercaseChar() in "0123456789ABCDEF" }
            // Max 32 chars (longest valid EPC)
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
    // RSSI: -35 dBm = strong, -65 dBm = weak
    val signalLevel = when {
        rssi >= -40 -> 4    // Excellent
        rssi >= -50 -> 3    // Good
        rssi >= -60 -> 2    // Fair
        else -> 1            // Weak
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
                text = "💡 Move closer for a more reliable write",
                fontSize = 10.sp,
                color = MediumGray
            )
        }
    }
}
