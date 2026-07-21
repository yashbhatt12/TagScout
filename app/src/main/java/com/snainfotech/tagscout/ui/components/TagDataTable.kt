package com.snainfotech.tagscout.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snainfotech.tagscout.ui.screens.quickscan.DetectedTag
import com.snainfotech.tagscout.ui.theme.BorderGray
import com.snainfotech.tagscout.ui.theme.DarkText
import com.snainfotech.tagscout.ui.theme.MediumGray
import com.snainfotech.tagscout.ui.theme.SuccessGreen
import com.snainfotech.tagscout.ui.theme.TableRowFound

@Composable
fun TagDataTable(
    tags: List<DetectedTag>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, BorderGray, RoundedCornerShape(10.dp))
            .background(Color.White)
    ) {
        // Header row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF8F9FA))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            TableHeaderCell("Scan", weight = 0.08f)
            TableHeaderCell("EPC", weight = 0.65f)
            TableHeaderCell("Signal", weight = 0.15f)
            TableHeaderCell("Count", weight = 0.12f)
        }

        // Body
        if (tags.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(30.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Click Play to begin scanning",
                    color = MediumGray,
                    fontSize = 12.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 300.dp)
            ) {
                items(tags) { tag ->
                    TagRow(tag)
                }
            }
        }
    }
}

// Header cell — uses weight inside Row
@Composable
private fun RowScope.TableHeaderCell(text: String, weight: Float) {
    Box(
        modifier = Modifier.weight(weight),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = MediumGray
        )
    }
}

@Composable
private fun TagRow(tag: DetectedTag) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TableRowFound)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.weight(0.08f), contentAlignment = Alignment.Center) {
            Text(
                text = "✓",
                color = SuccessGreen,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Box(modifier = Modifier.weight(0.65f), contentAlignment = Alignment.Center) {
            Text(
                text = tag.epc,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = DarkText
            )
        }
        Box(modifier = Modifier.weight(0.15f), contentAlignment = Alignment.Center) {
            Text(
                text = "${tag.signalStrength}dB",
                fontSize = 11.sp,
                color = DarkText
            )
        }
        Box(modifier = Modifier.weight(0.12f), contentAlignment = Alignment.Center) {
            Text(
                text = "${tag.count}",
                fontSize = 11.sp,
                color = DarkText,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun TagDataTableEmptyPreview() {
    TagDataTable(tags = emptyList())
}

@Preview(showBackground = true)
@Composable
fun TagDataTableWithDataPreview() {
    TagDataTable(
        tags = listOf(
            DetectedTag("3004A1B2C3D4E5F6", -45, 5, 0),
            DetectedTag("3004A2B3C4D5E6F7", -52, 3, 0),
            DetectedTag("3004A3B4C5D6E7F8", -48, 4, 0)
        )
    )
}