package com.snainfotech.tagscout.ui.screens.jewellery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.snainfotech.tagscout.ui.components.AppHeader
import com.snainfotech.tagscout.ui.theme.DarkText
import com.snainfotech.tagscout.ui.theme.InfoBg
import com.snainfotech.tagscout.ui.theme.InfoText
import com.snainfotech.tagscout.ui.theme.LightGray
import com.snainfotech.tagscout.ui.theme.MediumGray
import com.snainfotech.tagscout.ui.theme.WarningBg
import com.snainfotech.tagscout.ui.theme.WarningText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Jewellery Checkout screen — scan a piece at the counter and mark it sold.
 *
 * The scanner integration is passed as a lambda so this screen stays unit-testable
 * without a live sled. NavGraph wires onScanTriggered to the real RfidScannerManager
 * (matching the pattern used by the Enrollment screen).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JewelleryCheckoutScreen(
    onBackClick: () -> Unit,
    onScanTriggered: (onEpc: (String) -> Unit) -> Unit = { _ -> },
    modifier: Modifier = Modifier,
    viewModel: JewelleryCheckoutViewModel = viewModel(factory = JewelleryCheckoutViewModelFactory())
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.errorMessage, state.successMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessage()
        }
        state.successMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LightGray)
        ) {
            AppHeader(
                title = "Checkout",
                showBackButton = true,
                onBackClick = onBackClick,
                showMenu = false
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ── Instructional banner (only when idle) ─────────────────
                if (state.lookupStatus == LookupStatus.IDLE) {
                    InfoBanner(text = "🛍️ Scan the piece being sold.")
                }

                // ── Scan Tag button ───────────────────────────────────────
                Button(
                    onClick = {
                        onScanTriggered { epc -> viewModel.onEpcScanned(epc) }
                    },
                    enabled = !state.isSaving &&
                            state.lookupStatus != LookupStatus.LOOKING_UP,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = when (state.lookupStatus) {
                            LookupStatus.IDLE -> "Scan Tag"
                            LookupStatus.LOOKING_UP -> "Checking…"
                            else -> "Scan Again"
                        },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // ── Result section — switches based on lookupStatus ──────
                when (state.lookupStatus) {
                    LookupStatus.IDLE -> {
                        /* nothing — banner above handles the empty state */
                    }

                    LookupStatus.LOOKING_UP -> LookingUpCard()

                    LookupStatus.FOUND -> {
                        val piece = state.foundPiece
                        if (piece != null) {
                            FoundPieceCard(
                                epc = piece.epc,
                                itemCode = piece.itemCode ?: "—",
                                price = piece.price,
                                enrolledAt = piece.inwardedAt?.toDate()
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Button(
                                onClick = viewModel::confirmSold,
                                enabled = !state.isSaving,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF2E7D32) // green 800
                                )
                            ) {
                                if (state.isSaving) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = Color.White,
                                        strokeWidth = 2.dp
                                    )
                                    Text(
                                        text = "  Marking sold…",
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                } else {
                                    Text(
                                        text = "Confirm Sold",
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                            TextButton(
                                onClick = viewModel::clearScan,
                                enabled = !state.isSaving,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "Scan a different piece",
                                    color = MediumGray,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }

                    LookupStatus.NOT_FOUND -> {
                        ResultCard(
                            emoji = "⚠️",
                            title = "This tag is not enrolled",
                            body = "The scanned tag has no jewellery record. Enroll it first, or scan a different piece.",
                            backgroundColor = Color(0xFFFFEBEE), // red 50
                            textColor = Color(0xFFB71C1C)         // red 900
                        )
                    }

                    LookupStatus.NOT_JEWELLERY -> {
                        ResultCard(
                            emoji = "ℹ️",
                            title = "Not a jewellery piece",
                            body = "This tag is on a warehouse item. Use the Warehouse flow to dispatch it.",
                            backgroundColor = WarningBg,
                            textColor = WarningText
                        )
                    }

                    LookupStatus.ALREADY_SOLD -> {
                        val piece = state.foundPiece
                        val soldDateText = piece?.soldAt?.toDate()?.let { formatDate(it) }
                            ?: "earlier"
                        ResultCard(
                            emoji = "✓",
                            title = "Already sold",
                            body = "Item ${piece?.itemCode ?: "—"} was marked sold on $soldDateText.",
                            backgroundColor = WarningBg,
                            textColor = WarningText
                        )
                    }

                    LookupStatus.WRONG_STATUS -> {
                        val piece = state.foundPiece
                        ResultCard(
                            emoji = "ℹ️",
                            title = "Cannot checkout",
                            body = "Item ${piece?.itemCode ?: "—"} has status \"${piece?.status ?: "unknown"}\" " +
                                    "and cannot be marked sold from here.",
                            backgroundColor = WarningBg,
                            textColor = WarningText
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        ) { data ->
            Snackbar(snackbarData = data)
        }
    }
}

// ============================================
// SUB-COMPOSABLES
// ============================================

@Composable
private fun InfoBanner(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(InfoBg)
            .padding(12.dp)
    ) {
        Text(text = text, color = InfoText, fontSize = 13.sp)
    }
}

@Composable
private fun LookingUpCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "Checking inventory…",
            fontSize = 14.sp,
            color = DarkText
        )
    }
}

@Composable
private fun FoundPieceCard(
    epc: String,
    itemCode: String,
    price: Double?,
    enrolledAt: Date?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFE8F5E9)) // green 50
            .padding(16.dp)
    ) {
        Text(
            text = "✓  READY TO SELL",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF2E7D32) // green 800
        )
        Spacer(modifier = Modifier.height(10.dp))
        LabelValue(label = "Item Code", value = itemCode)
        Spacer(modifier = Modifier.height(6.dp))
        LabelValue(
            label = "Price",
            value = if (price != null) "₹${price.toInt()}" else "—"
        )
        Spacer(modifier = Modifier.height(6.dp))
        LabelValue(label = "Tag EPC", value = epc, mono = true)
        if (enrolledAt != null) {
            Spacer(modifier = Modifier.height(6.dp))
            LabelValue(label = "Enrolled", value = formatDate(enrolledAt))
        }
    }
}

@Composable
private fun LabelValue(label: String, value: String, mono: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MediumGray,
            modifier = Modifier.width(100.dp)
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = DarkText,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default
        )
    }
}

@Composable
private fun ResultCard(
    emoji: String,
    title: String,
    body: String,
    backgroundColor: Color,
    textColor: Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .padding(16.dp)
    ) {
        Text(
            text = "$emoji  $title",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = textColor
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = body,
            fontSize = 13.sp,
            color = textColor
        )
    }
}

private fun formatDate(date: Date): String {
    val fmt = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
    return fmt.format(date)
}

// ============================================
// PREVIEW
// ============================================

@Preview(showBackground = true, heightDp = 700)
@Composable
fun JewelleryCheckoutScreenPreview() {
    JewelleryCheckoutScreen(
        onBackClick = {}
    )
}