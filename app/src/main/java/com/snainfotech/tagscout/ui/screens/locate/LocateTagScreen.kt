package com.snainfotech.tagscout.ui.screens.locate

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snainfotech.tagscout.ui.components.AppHeader
import com.snainfotech.tagscout.ui.components.DeviceStatusComponent
import com.snainfotech.tagscout.ui.screens.home.ConnectionStatus
import com.snainfotech.tagscout.ui.theme.Amber
import com.snainfotech.tagscout.ui.theme.BorderGray
import com.snainfotech.tagscout.ui.theme.DarkText
import com.snainfotech.tagscout.ui.theme.ErrorRed
import com.snainfotech.tagscout.ui.theme.LightGray
import com.snainfotech.tagscout.ui.theme.MediumGray
import com.snainfotech.tagscout.ui.theme.Primary
import com.snainfotech.tagscout.ui.theme.SuccessGreen

@Composable
fun LocateTagScreen(
    state: LocateTagState,
    isDeviceConnected: Boolean,
    deviceName: String,
    serialNumber: String,
    firmwareVersion: String,
    batteryPercent: Int,
    onBackClick: () -> Unit,
    onMenuClick: () -> Unit = {},
    onDeviceStatusClick: () -> Unit = {},
    onTargetEpcChange: (String) -> Unit,
    onStartLocate: () -> Unit,
    onStopLocate: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LightGray)
    ) {
        AppHeader(
            title = "Locate Tag",
            showBackButton = !state.isLocating,
            onBackClick = onBackClick,
            onMenuClick = onMenuClick,
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // EPC input field
            OutlinedTextField(
                value = state.targetEpc,
                onValueChange = onTargetEpcChange,
                label = { Text("Target EPC") },
                placeholder = { Text("Enter the tag's EPC to locate") },
                singleLine = true,
                enabled = !state.isLocating,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = BorderGray,
                    focusedLabelColor = Primary,
                    cursorColor = Primary
                )
            )

            // Error
            if (state.error != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = state.error,
                    color = ErrorRed,
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // EPC display (monospace, truncated for readability)
            if (state.targetEpc.isNotBlank()) {
                Text(
                    text = state.targetEpc,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MediumGray,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.weight(0.3f))

            // Proximity indicator ring
            ProximityRing(
                proximity = state.proximity,
                isLocating = state.isLocating,
                modifier = Modifier.size(220.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Status message
            Text(
                text = state.statusMessage,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    state.proximity >= 80 -> SuccessGreen
                    state.proximity >= 40 -> Amber
                    state.isLocating -> DarkText
                    else -> MediumGray
                },
                textAlign = TextAlign.Center
            )

            // Proximity percentage
            if (state.isLocating) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${state.proximity}%",
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = proximityColor(state.proximity),
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.weight(0.5f))

            // Antenna info
            if (state.isLocating) {
                Text(
                    text = "Antenna fixed at short range (~5m)",
                    fontSize = 11.sp,
                    color = MediumGray,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Start / Stop button
            if (state.isLocating) {
                OutlinedButton(
                    onClick = onStopLocate,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
                    border = androidx.compose.foundation.BorderStroke(2.dp, ErrorRed)
                ) {
                    Text("Stop Locating", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Button(
                    onClick = onStartLocate,
                    enabled = state.targetEpc.length >= 16 && isDeviceConnected,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) {
                    Text("Start Locating", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// ============================================
// PROXIMITY RING — animated concentric circles
// ============================================

@Composable
private fun ProximityRing(
    proximity: Int,
    isLocating: Boolean,
    modifier: Modifier = Modifier
) {
    // Animate the fill smoothly
    val animatedProximity by animateFloatAsState(
        targetValue = proximity / 100f,
        animationSpec = tween(300),
        label = "proximity"
    )

    val ringColor = proximityColor(proximity)
    val bgRingColor = BorderGray

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val maxRadius = size.minDimension / 2 - 8f
            val strokeWidth = 12f

            // Background ring (full circle, muted)
            drawCircle(
                color = bgRingColor,
                radius = maxRadius,
                center = center,
                style = Stroke(width = strokeWidth)
            )

            if (isLocating && animatedProximity > 0f) {
                // Filled arc showing proximity (sweeps from top)
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f * animatedProximity,
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    topLeft = Offset(center.x - maxRadius, center.y - maxRadius),
                    size = androidx.compose.ui.geometry.Size(maxRadius * 2, maxRadius * 2)
                )

                // Inner pulse rings (concentric, fading)
                val rings = 3
                for (i in 1..rings) {
                    val ringRadius = maxRadius * (animatedProximity * 0.7f) * (i.toFloat() / rings)
                    if (ringRadius > 10f) {
                        drawCircle(
                            color = ringColor.copy(alpha = 0.15f / i),
                            radius = ringRadius,
                            center = center
                        )
                    }
                }
            }
        }

        // Center text
        if (isLocating) {
            Text(
                text = if (proximity > 0) "📡" else "🔍",
                fontSize = 40.sp
            )
        } else {
            Text(
                text = "📡",
                fontSize = 40.sp,
                color = Color.Gray
            )
        }
    }
}

private fun proximityColor(proximity: Int): Color = when {
    proximity >= 80 -> SuccessGreen
    proximity >= 50 -> Amber
    proximity >= 20 -> Primary
    else -> MediumGray
}