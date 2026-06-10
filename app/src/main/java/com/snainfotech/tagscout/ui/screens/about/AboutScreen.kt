package com.snainfotech.tagscout.ui.screens.about

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snainfotech.tagscout.ui.components.AppHeader
import com.snainfotech.tagscout.ui.theme.BorderGray
import com.snainfotech.tagscout.ui.theme.DarkText
import com.snainfotech.tagscout.ui.theme.LightGray
import com.snainfotech.tagscout.ui.theme.MediumGray
import com.snainfotech.tagscout.ui.theme.Primary

@Composable
fun AboutScreen(
    onBackClick: () -> Unit = {},
    onMenuClick: () -> Unit = {},
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

        // Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            // Big icon
            Text(
                text = "📱",
                fontSize = 64.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Info card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, BorderGray, RoundedCornerShape(12.dp))
                    .background(Color.White)
                    .padding(20.dp)
            ) {
                AboutRow(label = "App Name", value = "TagScout")
                AboutDivider()
                AboutRow(label = "Version", value = "1.0.0")
                AboutDivider()
                AboutRow(label = "Designed By", value = "SNA Infotech Pvt Ltd.")
                AboutDivider()
                AboutRow(
                    label = "Support Email",
                    value = "rfid@sna-infotech.co.in",
                    valueColor = Primary
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "© 2026 SNA Infotech",
                fontSize = 11.sp,
                color = MediumGray
            )
            Text(
                text = "All rights reserved",
                fontSize = 11.sp,
                color = MediumGray
            )
        }
    }
}

@Composable
private fun AboutRow(
    label: String,
    value: String,
    valueColor: Color = DarkText
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label.uppercase(),
            fontSize = 10.sp,
            color = MediumGray,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            fontSize = 14.sp,
            color = valueColor,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun AboutDivider() {
    Spacer(modifier = Modifier.height(12.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color(0xFFF0F0F0))
    )
    Spacer(modifier = Modifier.height(12.dp))
}

@Preview(showBackground = true, heightDp = 700)
@Composable
fun AboutScreenPreview() {
    AboutScreen()
}