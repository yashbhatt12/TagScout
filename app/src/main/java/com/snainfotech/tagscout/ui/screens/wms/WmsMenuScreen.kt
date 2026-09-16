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
 * Two admin screens (Warehouse Setup, Product Catalog) are enabled now.
 * The four workflow features (Inward, Cycle Count, Pick List, Dispatch)
 * appear here disabled/coming-soon and are built in later sessions.
 */
@Composable
fun WmsMenuScreen(
    onBackClick: () -> Unit,
    onWarehouseSetupClick: () -> Unit,
    onProductCatalogClick: () -> Unit,
    onDashboardClick: () -> Unit = {},
    onInwardClick: () -> Unit = {},
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
            // ── Setup ────────────────────────────────
            Text(
                text = "SETUP",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MediumGray,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp)
            )

            FeatureButton(
                icon = R.drawable.ic_config,
                title = "Warehouse Setup",
                description = "Create warehouses, racks and bins",
                enabled = true,
                onClick = onWarehouseSetupClick
            )
            FeatureButton(
                icon = R.drawable.ic_inventory,
                title = "Product Catalog",
                description = "Create and manage products (SKUs)",
                enabled = true,
                onClick = onProductCatalogClick
            )

            Spacer(modifier = Modifier.height(4.dp))

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

            FeatureButton(
                icon = R.drawable.ic_inventory,
                title = "Inward (GRN)",
                description = "Coming soon — receive goods into inventory",
                enabled = false,
                onClick = onInwardClick
            )
            FeatureButton(
                icon = R.drawable.ic_scan,
                title = "Cycle Count",
                description = "Coming soon — stock take by bin or rack",
                enabled = false,
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