package com.snainfotech.tagscout.ui.screens.wms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
 * The WMS submenu — hub for all warehouse operations.
 *
 * Set-up screens (locations, products, categories & brands) live in
 * Company Set Up on Home, so every business type can reach them.
 * Pick List and Dispatch appear here disabled until they are built.
 */
@Composable
fun WmsMenuScreen(
    onBackClick: () -> Unit,
    onDashboardClick: () -> Unit = {},
    onInwardClick: () -> Unit = {},
    onEnrollUnitsClick: () -> Unit = {},
    onUnitDetailsClick: () -> Unit = {},
    onCycleCountClick: () -> Unit = {},
    onPickListClick: () -> Unit = {},
    onDispatchClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LightGray)
    ) {
        AppHeader(
            title = "Warehouse Management",
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
            // ── Overview ─────────────────────────────
            Text(
                text = "OVERVIEW",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MediumGray,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp)
            )

            FeatureButton(
                icon = R.drawable.ic_scan,
                title = "Inventory Dashboard",
                description = "View inventory by product or by location",
                enabled = true,
                onClick = onDashboardClick
            )

            Spacer(modifier = Modifier.height(4.dp))

            // ── Operations ───────────────────────────
            Text(
                text = "OPERATIONS",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MediumGray,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp)
            )

            // Excel upload (bulk) and desktop / fixed reader (unit) share one screen.
            FeatureButton(
                icon = R.drawable.ic_rfid_tag,
                title = "Enroll stock",
                description = "Bulk by Excel, or one at a time with a desktop or fixed reader",
                enabled = true,
                onClick = onEnrollUnitsClick
            )
            FeatureButton(
                icon = R.drawable.ic_scan,
                title = "Unit details",
                description = "Scan or search a tag to view, edit or change its status",
                enabled = true,
                onClick = onUnitDetailsClick
            )
            FeatureButton(
                icon = R.drawable.ic_scan,
                title = "Cycle Count",
                description = "Bin-level stock reconciliation",
                enabled = true,
                onClick = onCycleCountClick
            )
            FeatureButton(
                icon = R.drawable.ic_pick,
                title = "Pick List",
                description = "Coming soon — pick items for orders",
                enabled = false,
                onClick = onPickListClick
            )
            FeatureButton(
                icon = R.drawable.ic_scan,
                title = "Dispatch Verification",
                description = "Coming soon — verify goods at ship-out bay",
                enabled = false,
                onClick = onDispatchClick
            )
        }
    }
}