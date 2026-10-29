package com.snainfotech.tagscout.ui.screens.enrollment

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snainfotech.tagscout.ui.components.AppHeader
import com.snainfotech.tagscout.ui.components.FormHint
import com.snainfotech.tagscout.ui.components.FormPane
import com.snainfotech.tagscout.ui.components.FormSection
import com.snainfotech.tagscout.ui.theme.CardBg
import com.snainfotech.tagscout.ui.theme.DarkText
import com.snainfotech.tagscout.ui.theme.LightGray
import com.snainfotech.tagscout.ui.theme.MediumGray
import com.snainfotech.tagscout.ui.theme.Primary

enum class EnrollTab(val label: String) {
    BULK("Bulk (Excel)"),
    UNIT("Unit")
}

/**
 * One Enroll screen for WMS and Retail, with two tabs:
 *
 *  - Bulk (Excel): the default, always available. Upload a filled-in
 *    template; no reader needed.
 *  - Unit: scan one piece at a time with a desktop reader (USB) or a fixed
 *    reader (network). Enabled only when the app knows one is connected;
 *    the Bluetooth sled is never used here.
 *
 * Tabs are locked while either side is busy (validating / saving a file,
 * or mid-scan), so work in progress is never left half done.
 *
 * The header's back arrow sends a normal system Back, so each tab's own
 * back handling (e.g. "Discard scanned tags?") still applies.
 */
@Composable
fun EnrollHubScreen(
    unitReaderConnected: Boolean,
    bulkBusy: Boolean,
    tabsLocked: Boolean,
    bulkContent: @Composable () -> Unit,
    unitContent: @Composable () -> Unit
) {
    var tab by rememberSaveable { mutableStateOf(EnrollTab.BULK) }
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher

    // A reader unplugged while on the Unit tab: fall back to Bulk.
    LaunchedEffect(unitReaderConnected) {
        if (!unitReaderConnected && tab == EnrollTab.UNIT && !tabsLocked) tab = EnrollTab.BULK
    }

    // Stay on screen while a file is being validated or saved.
    BackHandler(enabled = bulkBusy) { /* wait for it to finish */ }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LightGray)
    ) {
        AppHeader(
            title = "Enroll stock",
            showBackButton = true,
            onBackClick = { dispatcher?.onBackPressed() },
            showMenu = false
        )

        val tabs = EnrollTab.entries
        TabRow(
            selectedTabIndex = tabs.indexOf(tab),
            containerColor = CardBg,
            contentColor = Primary,
            indicator = { positions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(positions[tabs.indexOf(tab)]),
                    color = Primary
                )
            }
        ) {
            tabs.forEach { t ->
                val enabled = !tabsLocked && (t == EnrollTab.BULK || unitReaderConnected)
                Tab(
                    selected = tab == t,
                    enabled = enabled,
                    onClick = { tab = t },
                    text = {
                        Text(
                            text = t.label,
                            fontWeight = if (tab == t) FontWeight.SemiBold else FontWeight.Normal,
                            color = when {
                                tab == t -> Primary
                                enabled -> DarkText
                                else -> MediumGray.copy(alpha = 0.5f)
                            }
                        )
                    }
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (tab) {
                EnrollTab.BULK -> bulkContent()
                EnrollTab.UNIT -> if (unitReaderConnected || tabsLocked) unitContent() else NoUnitReaderPane()
            }
        }

        if (!unitReaderConnected && tab == EnrollTab.BULK) {
            Text(
                text = "Unit enroll needs a desktop reader (USB) or a fixed reader (network).",
                fontSize = 11.sp,
                color = MediumGray,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardBg)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun NoUnitReaderPane() {
    FormPane {
        FormSection("No reader connected") {
            FormHint("Unit enroll scans one piece at a time. It needs a desktop reader plugged in by USB, or a fixed reader on the same network.")
            FormHint("The handheld sled isn't used for enrollment. Use the Bulk (Excel) tab instead.")
        }
    }
}
