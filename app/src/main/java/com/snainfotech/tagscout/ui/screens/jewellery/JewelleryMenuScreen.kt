package com.snainfotech.tagscout.ui.screens.jewellery

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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snainfotech.tagscout.R
import com.snainfotech.tagscout.ui.components.AppHeader
import com.snainfotech.tagscout.ui.components.FeatureButton
import com.snainfotech.tagscout.ui.theme.LightGray
import com.snainfotech.tagscout.ui.theme.MediumGray

/**
 * The Jewellery submenu — hub for all jewellery retail operations.
 *
 * Mirrors WmsMenuScreen's structure so the UX feels consistent.
 * Jewellery pieces are stored as InventoryUnit documents with
 * category = "jewellery" and the retail-specific fields populated
 * (itemCode, price, soldAt, soldBy).
 *
 * Phase 1 (demo) covers Enroll, Checkout and Cycle Count.
 * Later phases will add portal event history, daily closing report,
 * and multi-location vault vs display tracking.
 */
@Composable
fun JewelleryMenuScreen(
    onBackClick: () -> Unit,
    onEnrollClick: () -> Unit = {},
    onCheckoutClick: () -> Unit = {},
    onCycleCountClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LightGray)
    ) {
        AppHeader(
            title = "Jewellery",
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
            // ── Operations ───────────────────────────
            Text(
                text = "OPERATIONS",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MediumGray,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp)
            )

            FeatureButton(
                icon = R.drawable.ic_jewellery,
                title = "Enroll Piece",
                description = "Scan tag, enter item code and price",
                enabled = true,
                onClick = onEnrollClick
            )
            FeatureButton(
                icon = R.drawable.ic_rfid_tag,
                title = "Checkout / Mark Sold",
                description = "Scan at counter to mark piece as sold",
                enabled = true,
                onClick = onCheckoutClick
            )
            FeatureButton(
                icon = R.drawable.ic_scan,
                title = "Cycle Count",
                description = "Reconcile physical stock vs expected",
                enabled = true,
                onClick = onCycleCountClick
            )

            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

// ============================================
// PREVIEW
// ============================================

@Preview(showBackground = true, heightDp = 700)
@Composable
fun JewelleryMenuScreenPreview() {
    JewelleryMenuScreen(
        onBackClick = {}
    )
}