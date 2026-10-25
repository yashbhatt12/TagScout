package com.snainfotech.tagscout.ui.screens.jewellery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snainfotech.tagscout.R
import com.snainfotech.tagscout.ui.components.AppHeader
import com.snainfotech.tagscout.ui.components.FeatureButton
import com.snainfotech.tagscout.ui.theme.InfoBg
import com.snainfotech.tagscout.ui.theme.InfoText
import com.snainfotech.tagscout.ui.theme.LightGray
import com.snainfotech.tagscout.ui.theme.MediumGray

/**
 * The Retail Management submenu — hub for all retail operations.
 *
 * Mirrors WmsMenuScreen's structure so the UX feels consistent.
 * Retail pieces are stored as InventoryUnit documents with
 * category = "jewellery" (the internal category tag is unchanged for
 * backwards compatibility with existing enrolled stock) and the retail-
 * specific fields populated (itemCode, price, soldAt, soldBy).
 *
 * Buttons that require an RFID reader (Enroll, Checkout) are disabled
 * when no sled is connected — opening those screens without hardware
 * would crash the Bluebird SDK. Cycle Count stays enabled since it
 * routes into the existing WMS flow which has its own connection
 * handling. Manager Alerts is also always enabled — its data flows in
 * through Firestore from the fixed-reader edge service, not the sled.
 */
@Composable
fun JewelleryMenuScreen(
    onBackClick: () -> Unit,
    isReaderConnected: Boolean = true,
    onEnrollClick: () -> Unit = {},
    onCheckoutClick: () -> Unit = {},
    onCycleCountClick: () -> Unit = {},
    onManagerAlertsClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LightGray)
    ) {
        AppHeader(
            title = "Retail Management",
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
            // Hint banner when no reader is connected — explains why some
            // buttons below are greyed out.
            if (!isReaderConnected) {
                NoReaderBanner()
            }

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
                enabled = isReaderConnected,
                onClick = onEnrollClick
            )
            FeatureButton(
                icon = R.drawable.ic_rfid_tag,
                title = "Checkout / Mark Sold",
                description = "Scan at counter to mark piece as sold",
                enabled = isReaderConnected,
                onClick = onCheckoutClick
            )
            FeatureButton(
                icon = R.drawable.ic_scan,
                title = "Cycle Count",
                description = "Coming soon — retail-specific stock reconciliation",
                enabled = false,
                onClick = onCycleCountClick
            )

            // ── Alerts ───────────────────────────────────
            // Manager Alerts does not require a connected reader — the
            // alerts come in via Firestore from the fixed-reader edge
            // service, so this button stays enabled even when offline.
            Text(
                text = "ALERTS",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MediumGray,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp)
            )

            FeatureButton(
                icon = R.drawable.ic_jewellery,
                title = "Manager Alerts",
                description = "Live notifications when unsold pieces are seen at the exit",
                enabled = true,
                onClick = onManagerAlertsClick
            )

            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun NoReaderBanner() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(InfoBg)
            .padding(12.dp)
    ) {
        Text(
            text = "💡 Connect an RFID reader to enable Enroll and Checkout.",
            color = InfoText,
            fontSize = 12.sp
        )
    }
}

// ============================================
// PREVIEWS
// ============================================
@Preview(showBackground = true, heightDp = 700)
@Composable
fun RetailMenuScreenConnectedPreview() {
    JewelleryMenuScreen(
        onBackClick = {},
        isReaderConnected = true
    )
}

@Preview(showBackground = true, heightDp = 700)
@Composable
fun RetailMenuScreenDisconnectedPreview() {
    JewelleryMenuScreen(
        onBackClick = {},
        isReaderConnected = false
    )
}