package com.snainfotech.tagscout.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.snainfotech.tagscout.ui.theme.BorderGray
import com.snainfotech.tagscout.ui.theme.DarkText
import com.snainfotech.tagscout.ui.theme.MediumGray
import com.snainfotech.tagscout.ui.theme.SuccessGreen

@Composable
fun StatsCards(
    label1: String,
    value1: String,
    label2: String,
    value2: String,
    label3: String,
    value3: String,
    isLive: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatCard(label = label1, value = value1, isLive = isLive, modifier = Modifier.weight(1f))
        StatCard(label = label2, value = value2, isLive = isLive, modifier = Modifier.weight(1f))
        StatCard(label = label3, value = value3, isLive = isLive, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    isLive: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .border(1.dp, BorderGray, RoundedCornerShape(10.dp))
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = if (isLive) SuccessGreen else DarkText
        )
        Text(
            text = label,
            fontSize = 9.sp,
            color = MediumGray,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Preview(showBackground = true)
@Composable
fun StatsCardsInitialPreview() {
    StatsCards(
        label1 = "UNIQUE TAGS", value1 = "0",
        label2 = "TOTAL TAGS", value2 = "0",
        label3 = "READ/SEC", value3 = "0"
    )
}

@Preview(showBackground = true)
@Composable
fun StatsCardsLivePreview() {
    StatsCards(
        label1 = "UNIQUE TAGS", value1 = "12",
        label2 = "TOTAL TAGS", value2 = "45",
        label3 = "READ/SEC", value3 = "8.2",
        isLive = true
    )
}