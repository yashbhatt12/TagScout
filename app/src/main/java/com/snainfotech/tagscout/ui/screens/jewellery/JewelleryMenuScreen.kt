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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snainfotech.tagscout.R
import com.snainfotech.tagscout.data.config.CompanyConfig
import com.snainfotech.tagscout.data.config.ConfigRepository
import com.snainfotech.tagscout.data.config.Feature
import com.snainfotech.tagscout.data.config.LabelKey
import com.snainfotech.tagscout.ui.components.AppHeader
import com.snainfotech.tagscout.ui.components.FeatureButton
import com.snainfotech.tagscout.ui.components.GatedFeatureButton
import com.snainfotech.tagscout.ui.components.UpgradeDialog
import com.snainfotech.tagscout.ui.theme.InfoBg
import com.snainfotech.tagscout.ui.theme.InfoText
import com.snainfotech.tagscout.ui.theme.LightGray
import com.snainfotech.tagscout.ui.theme.MediumGray

/**
 * The Retail Management submenu — hub for all retail operations.
 *
 * Mirrors WmsMenuScreen's structure so the UX feels consistent.
 * Products are managed in Company Set Up on Home.
 *
 * Enroll opens the shared unit enrollment screen, which works with the
 * sled or a desktop reader and handles a missing sled itself, so it is
 * always enabled. Checkout still needs a connected sled: opening it without
 * hardware would crash the Bluebird SDK. Products and Manager Alerts need
 * no reader: products are catalog data, and alerts arrive via Firestore
 * from the fixed-reader edge service.
 *
 * Pilot note: pieces enrolled before the product model have
 * category = "jewellery" and no productId. Checkout still accepts them.
 */
@Composable
fun JewelleryMenuScreen(
    onBackClick: () -> Unit,
    isReaderConnected: Boolean = true,
    onEnrollClick: () -> Unit = {},
    onCheckoutClick: () -> Unit = {},
    onPieceDetailsClick: () -> Unit = {},
    onCycleCountClick: () -> Unit = {},
    onManagerAlertsClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Live company config. Previews can't start Firebase, so they use defaults.
    val config: CompanyConfig = if (LocalInspectionMode.current) {
        CompanyConfig.DEFAULT
    } else {
        ConfigRepository.config.collectAsState().value
    }

    var upgradeFor by remember { mutableStateOf<String?>(null) }

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
            SectionLabel("OPERATIONS", topPadding = 4)

            FeatureButton(
                icon = R.drawable.ic_jewellery,
                title = "Enroll stock",
                description = "Bulk by Excel, or one at a time with a desktop or fixed reader",
                enabled = true,
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
                title = "Piece details",
                description = "Scan or search a piece to view, edit or change its status",
                enabled = true,
                onClick = onPieceDetailsClick
            )
            // Same screen as WMS cycle count; it counts by bin (tray for jewellers).
            FeatureButton(
                icon = R.drawable.ic_scan,
                title = "Cycle Count",
                description = "${config.labels.singular(LabelKey.BIN)}-level stock reconciliation",
                enabled = true,
                onClick = onCycleCountClick
            )

            // ── Alerts ───────────────────────────────
            SectionLabel("ALERTS", topPadding = 8)

            GatedFeatureButton(
                available = config.isEnabled(Feature.MANAGER_ALERTS),
                icon = R.drawable.ic_jewellery,
                title = "Manager Alerts",
                description = "Live notifications when unsold pieces are seen at the exit",
                enabled = true,
                onClick = onManagerAlertsClick,
                onUpgrade = { upgradeFor = it }
            )

            Spacer(modifier = Modifier.height(4.dp))
        }
    }

    upgradeFor?.let { title ->
        UpgradeDialog(featureTitle = title, onDismiss = { upgradeFor = null })
    }
}

@Composable
private fun SectionLabel(text: String, topPadding: Int) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = MediumGray,
        modifier = Modifier.padding(start = 4.dp, top = topPadding.dp, bottom = 2.dp)
    )
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
            text = "💡 Connect an RFID reader to enable Checkout.",
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