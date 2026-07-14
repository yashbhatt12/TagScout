package com.snainfotech.tagscout.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import com.snainfotech.tagscout.ui.theme.BorderGray
import com.snainfotech.tagscout.ui.theme.DarkText
import com.snainfotech.tagscout.ui.theme.MediumGray
import com.snainfotech.tagscout.ui.theme.Primary

@Composable
fun AntennaSlider(
    value: Int,
    enabled: Boolean = true,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .padding(14.dp)
            .alpha(if (enabled) 1f else 0.5f)
    ) {
        Text(
            text = if (enabled) "🔊 Antenna Strength" else "🔊 Antenna Strength (Locked)",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = DarkText
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Low",
                fontSize = 10.sp,
                color = MediumGray
            )

            Slider(
                value = value.toFloat(),
                onValueChange = { onValueChange(it.toInt()) },
                valueRange = 5f..30f,
                steps = 24,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = Primary,
                    activeTrackColor = Primary,
                    inactiveTrackColor = BorderGray
                )
            )

            Text(
                text = "High",
                fontSize = 10.sp,
                color = MediumGray
            )
        }

        Text(
            text = "Value: $value",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = DarkText,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Preview(showBackground = true)
@Composable
fun AntennaSliderEnabledPreview() {
    AntennaSlider(value = 5, enabled = true, onValueChange = {})
}

@Preview(showBackground = true)
@Composable
fun AntennaSliderDisabledPreview() {
    AntennaSlider(value = 7, enabled = false, onValueChange = {})
}