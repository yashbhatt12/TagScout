package com.snainfotech.tagscout.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
fun FeatureButton(
    icon: String,                  // Emoji icon (e.g., "📱")
    title: String,                 // Title (e.g., "Quick Scan")
    description: String,           // Subtitle text
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .then(
                if (enabled) Modifier.clickable { onClick() }
                else Modifier
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, BorderGray)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Icon
            Text(
                text = icon,
                fontSize = 28.sp
            )

            // Middle: Title + Description
            Column(
                modifier = Modifier
                    .padding(start = 14.dp)
                    .weight(1f)
            ) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DarkText
                )
                Text(
                    text = description,
                    fontSize = 11.sp,
                    color = MediumGray
                )
            }

            // Right: Chevron
            Text(
                text = "›",
                fontSize = 22.sp,
                color = if (enabled) Primary else BorderGray,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ============================================
// PREVIEWS
// ============================================

@Preview(showBackground = true)
@Composable
fun FeatureButtonEnabledPreview() {
    FeatureButton(
        icon = "📱",
        title = "Quick Scan",
        description = "Scan and identify tags",
        enabled = true,
        onClick = {}
    )
}

@Preview(showBackground = true)
@Composable
fun FeatureButtonDisabledPreview() {
    FeatureButton(
        icon = "📊",
        title = "Inventory by File",
        description = "Match tags against inventory",
        enabled = false,
        onClick = {}
    )
}