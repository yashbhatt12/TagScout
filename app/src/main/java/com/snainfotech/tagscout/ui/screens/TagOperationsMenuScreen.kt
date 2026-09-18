package com.snainfotech.tagscout.ui.screens.tagops

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snainfotech.tagscout.R
import com.snainfotech.tagscout.ui.components.AppHeader
import com.snainfotech.tagscout.ui.components.FeatureButton
import com.snainfotech.tagscout.ui.theme.LightGray
import com.snainfotech.tagscout.ui.theme.MediumGray

/**
 * Hub for all RFID tag operations. Groups the four scan-workflow screens
 * that were previously scattered on Home under one entry point.
 *
 * Enabled features require an active sled connection (deviceConnected).
 * The two OPERATIONS placeholders (Write Tag, Kill Tag) are hidden today
 * pending hardware validation of the write-selection offset; they surface
 * here as disabled "Coming soon" buttons so users can see the roadmap.
 */
@Composable
fun TagOperationsMenuScreen(
    deviceConnected: Boolean,
    onBackClick: () -> Unit,
    onQuickScanClick: () -> Unit,
    onInventoryByFileClick: () -> Unit,
    onPickListClick: () -> Unit,
    onLocateTagClick: () -> Unit,
    onWriteTagClick: () -> Unit = {},
    onKillTagClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LightGray)
    ) {
        AppHeader(
            title = "Tag Operations",
            showBackButton = true,
            onBackClick = onBackClick,
            showMenu = false
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ── Scan & Read ──────────────────────────
            Text(
                text = "SCAN & READ",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MediumGray,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp)
            )
            FeatureButton(
                icon = R.drawable.ic_scan,
                title = "Quick Scan",
                description = "Scan and identify tags",
                enabled = deviceConnected,
                onClick = onQuickScanClick
            )
            FeatureButton(
                icon = R.drawable.ic_locate,
                title = "Locate Tag",
                description = "Find a product by its RFID tag",
                enabled = deviceConnected,
                onClick = onLocateTagClick
            )

            Spacer(modifier = Modifier.height(4.dp))

            // ── File-Based Workflows ─────────────────
            Text(
                text = "FILE-BASED WORKFLOWS",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MediumGray,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp)
            )
            FeatureButton(
                icon = R.drawable.ic_inventory,
                title = "Inventory by File",
                description = "Match tags against an uploaded inventory list",
                enabled = deviceConnected,
                onClick = onInventoryByFileClick
            )
            FeatureButton(
                icon = R.drawable.ic_pick,
                title = "Pick List",
                description = "Pick goods against an order file",
                enabled = deviceConnected,
                onClick = onPickListClick
            )

            Spacer(modifier = Modifier.height(4.dp))

            // ── Tag Writing (future) ─────────────────
            Text(
                text = "TAG WRITING",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MediumGray,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp)
            )
            FeatureButton(
                icon = R.drawable.ic_write,
                title = "Write Tag",
                description = "Coming soon — change a tag's EPC",
                enabled = false,
                onClick = onWriteTagClick
            )
            FeatureButton(
                icon = R.drawable.ic_kill,
                title = "Kill Tag",
                description = "Coming soon — permanently disable a tag",
                enabled = false,
                onClick = onKillTagClick
            )
        }
    }
}