package com.snainfotech.tagscout.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snainfotech.tagscout.ui.screens.inventory.InventoryItem
import com.snainfotech.tagscout.ui.screens.inventory.InventoryTab
import com.snainfotech.tagscout.ui.screens.inventory.UnexpectedTag
import com.snainfotech.tagscout.ui.theme.BorderGray
import com.snainfotech.tagscout.ui.theme.DarkText
import com.snainfotech.tagscout.ui.theme.ErrorRed
import com.snainfotech.tagscout.ui.theme.LightGray
import com.snainfotech.tagscout.ui.theme.MediumGray
import com.snainfotech.tagscout.ui.theme.Primary
import com.snainfotech.tagscout.ui.theme.SuccessGreen
import com.snainfotech.tagscout.ui.theme.TableRowFound
import com.snainfotech.tagscout.ui.theme.TableRowMissing

/**
 * The two-tab row used by any scan screen that reconciles an expected set
 * (from a file or a bin) against what was actually scanned.
 *
 * `expectedLabel` — label for the first tab (e.g. "File Mapping", "Bin Contents").
 */
@Composable
fun ScanTabRow(
    selectedTab: InventoryTab,
    expectedFound: Int,
    expectedTotal: Int,
    additionalCount: Int,
    expectedLabel: String,
    onTabSelect: (InventoryTab) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(LightGray)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ScanTabButton(
            label = expectedLabel,
            count = "$expectedFound/$expectedTotal",
            isSelected = selectedTab == InventoryTab.FILE_MAPPING,
            onClick = { onTabSelect(InventoryTab.FILE_MAPPING) },
            modifier = Modifier.weight(1f)
        )
        ScanTabButton(
            label = "Additional Tags",
            count = "$additionalCount",
            isSelected = selectedTab == InventoryTab.ADDITIONAL_TAGS,
            onClick = { onTabSelect(InventoryTab.ADDITIONAL_TAGS) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ScanTabButton(
    label: String,
    count: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = if (isSelected) Primary else Color.White
    val textColor = if (isSelected) Color.White else DarkText
    val borderColor = if (isSelected) Primary else BorderGray

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(vertical = 8.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = textColor
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = count,
            fontSize = 11.sp,
            color = textColor,
            fontFamily = FontFamily.Monospace
        )
    }
}

/** The expected-items table — Status / EPC / Product. Rows coloured by `isFound`. */
@Composable
fun ExpectedItemTable(
    items: List<InventoryItem>,
    emptyText: String = "No items loaded",
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().background(Color.White)) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(LightGray)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Status", fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                color = MediumGray, modifier = Modifier.weight(0.18f)
            )
            Text(
                text = "EPC", fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                color = MediumGray, modifier = Modifier.weight(0.5f)
            )
            Text(
                text = "Product", fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                color = MediumGray, modifier = Modifier.weight(0.32f)
            )
        }

        if (items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(text = emptyText, fontSize = 12.sp, color = MediumGray)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(items = items, key = { it.id }) { item ->
                    ExpectedItemRow(item = item)
                }
            }
        }
    }
}

@Composable
private fun ExpectedItemRow(item: InventoryItem) {
    val bgColor = if (item.isFound) TableRowFound else TableRowMissing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (item.isFound) "✓" else "✗",
            fontSize = 14.sp, fontWeight = FontWeight.Bold,
            color = if (item.isFound) SuccessGreen else ErrorRed,
            modifier = Modifier.weight(0.18f)
        )
        Text(
            text = item.epc.take(16),
            fontSize = 11.sp, color = DarkText, fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(0.5f)
        )
        Text(
            text = item.productName,
            fontSize = 11.sp, color = DarkText,
            modifier = Modifier.weight(0.32f)
        )
    }
}

/**
 * The unexpected-tags table — EPC / Status / First Seen.
 * `Status` shows the tag's actual location from Firestore if known
 * (set via UnexpectedTag.knownLocation), otherwise falls back to TID.
 */
@Composable
fun UnexpectedTagsTable(
    tags: List<UnexpectedTag>,
    emptyEmoji: String = "✨",
    emptyTitle: String = "No additional tags",
    emptyHint: String = "Tags scanned that aren't expected will appear here",
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().background(Color.White)) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(LightGray)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "EPC", fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                color = MediumGray, modifier = Modifier.weight(0.45f)
            )
            Text(
                text = "Status", fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                color = MediumGray, modifier = Modifier.weight(0.4f)
            )
            Text(
                text = "First Seen", fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                color = MediumGray, modifier = Modifier.weight(0.15f)
            )
        }

        if (tags.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = emptyEmoji, fontSize = 32.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = emptyTitle,
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = DarkText
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = emptyHint,
                        fontSize = 11.sp, color = MediumGray
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(items = tags, key = { it.epc }) { tag ->
                    UnexpectedTagRow(tag = tag)
                }
            }
        }
    }
}

@Composable
private fun UnexpectedTagRow(tag: UnexpectedTag) {
    val statusText = tag.knownLocation ?: if (tag.tid.isNotBlank()) tag.tid.take(10) else "Unknown"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .border(0.5.dp, BorderGray)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = tag.epc.take(20),
            fontSize = 11.sp, color = DarkText, fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(0.45f)
        )
        Text(
            text = statusText,
            fontSize = 11.sp,
            color = if (tag.knownLocation != null) DarkText else MediumGray,
            fontFamily = if (tag.knownLocation != null) FontFamily.Default else FontFamily.Monospace,
            modifier = Modifier.weight(0.4f)
        )
        Text(
            text = formatElapsedShort(tag.firstSeenSeconds),
            fontSize = 10.sp, color = MediumGray,
            modifier = Modifier.weight(0.15f)
        )
    }
}

private fun formatElapsedShort(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return if (m > 0) "${m}m ${s}s" else "${s}s"
}