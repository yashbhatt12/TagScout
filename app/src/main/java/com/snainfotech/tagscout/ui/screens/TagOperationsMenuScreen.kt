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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snainfotech.tagscout.R
import com.snainfotech.tagscout.data.config.CompanyConfig
import com.snainfotech.tagscout.data.config.ConfigRepository
import com.snainfotech.tagscout.data.config.Feature
import com.snainfotech.tagscout.ui.components.AppHeader
import com.snainfotech.tagscout.ui.components.GatedFeatureButton
import com.snainfotech.tagscout.ui.components.UpgradeDialog
import com.snainfotech.tagscout.ui.theme.LightGray
import com.snainfotech.tagscout.ui.theme.MediumGray

/**
 * Hub for all RFID tag operations. Groups the scan-workflow screens
 * under one entry point.
 *
 * Two independent checks per tile:
 *   - plan (settings/config features): not in plan → faded "upgrade" tile
 *   - device: in plan but no sled connected → greyed, not tappable
 *
 * Write Tag and Kill Tag stay "Coming soon" (disabled) pending hardware
 * validation of the write-selection offset, but still show the upgrade
 * tile if a company's plan excludes them.
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
    // Live company config. Previews can't start Firebase, so they use defaults.
    val config: CompanyConfig = if (LocalInspectionMode.current) {
        CompanyConfig.DEFAULT
    } else {
        ConfigRepository.config.collectAsState().value
    }

    // Title of the locked tile that was tapped; null = no dialog.
    var upgradeFor by remember { mutableStateOf<String?>(null) }

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
            SectionLabel("SCAN & READ")
            GatedFeatureButton(
                available = config.isEnabled(Feature.QUICK_SCAN),
                icon = R.drawable.ic_scan,
                title = "Quick Scan",
                description = "Scan and identify tags",
                enabled = deviceConnected,
                onClick = onQuickScanClick,
                onUpgrade = { upgradeFor = it }
            )
            GatedFeatureButton(
                available = config.isEnabled(Feature.LOCATE_TAG),
                icon = R.drawable.ic_locate,
                title = "Locate Tag",
                description = "Find a product by its RFID tag",
                enabled = deviceConnected,
                onClick = onLocateTagClick,
                onUpgrade = { upgradeFor = it }
            )

            Spacer(modifier = Modifier.height(4.dp))

            // ── File-Based Workflows ─────────────────
            SectionLabel("FILE-BASED WORKFLOWS")
            GatedFeatureButton(
                available = config.isEnabled(Feature.INVENTORY_SCAN),
                icon = R.drawable.ic_inventory,
                title = "Inventory by File",
                description = "Match tags against an uploaded inventory list",
                enabled = deviceConnected,
                onClick = onInventoryByFileClick,
                onUpgrade = { upgradeFor = it }
            )
            GatedFeatureButton(
                available = config.isEnabled(Feature.ORDER_PICKING),
                icon = R.drawable.ic_pick,
                title = "Pick List",
                description = "Pick goods against an order file",
                enabled = deviceConnected,
                onClick = onPickListClick,
                onUpgrade = { upgradeFor = it }
            )

            Spacer(modifier = Modifier.height(4.dp))

            // ── Tag Writing (future) ─────────────────
            SectionLabel("TAG WRITING")
            GatedFeatureButton(
                available = config.isEnabled(Feature.WRITE_TAG),
                icon = R.drawable.ic_write,
                title = "Write Tag",
                description = "Coming soon — change a tag's EPC",
                enabled = false,
                onClick = onWriteTagClick,
                onUpgrade = { upgradeFor = it }
            )
            GatedFeatureButton(
                available = config.isEnabled(Feature.KILL_TAG),
                icon = R.drawable.ic_kill,
                title = "Kill Tag",
                description = "Coming soon — permanently disable a tag",
                enabled = false,
                onClick = onKillTagClick,
                onUpgrade = { upgradeFor = it }
            )
        }
    }

    upgradeFor?.let { title ->
        UpgradeDialog(featureTitle = title, onDismiss = { upgradeFor = null })
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = MediumGray,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp)
    )
}