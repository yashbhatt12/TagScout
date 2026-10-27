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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snainfotech.tagscout.data.wms.InventoryUnit
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
import com.snainfotech.tagscout.data.config.UnitStatus
/**
 * Jewellery Checkout screen — scan a piece at the counter and mark it sold.
 *
 * This is a pure-UI screen: state and callbacks come from the NavGraph wrapper
 * which owns the ViewModel and the scanner lifecycle. Whenever
 * [state.isWaitingForTag] is true, the NavGraph has the sled running on low
 * antenna power. Tag reads come back as [JewelleryCheckoutViewModel.onEpcScanned].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JewelleryCheckoutScreen(
    state: JewelleryCheckoutState,
    onBackClick: () -> Unit,
    onMarkSoldClick: () -> Unit,
    onScanAnotherClick: () -> Unit,
    onMessageDismissed: () -> Unit,
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var showConfirmDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            onMessageDismissed()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LightGray)
        ) {
            AppHeader(
                title = "Mark Sold",
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
                when (state.phase) {
                    CheckoutPhase.WAITING -> WaitingCard()

                    CheckoutPhase.LOOKING_UP -> LookingUpCard()

                    CheckoutPhase.FOUND -> {
                        if (state.foundPiece != null) {
                            FoundPieceCard(state)
                            Spacer(modifier = Modifier.height(4.dp))
                            MarkSoldButton(
                                isSaving = state.isSaving,
                                onClick = { showConfirmDialog = true }
                            )
                            SecondaryButton(
                                label = "Scan a different piece",
                                enabled = !state.isSaving,
                                onClick = onScanAnotherClick
                            )
                        }
                    }

                    CheckoutPhase.SAVING -> {
                        if (state.foundPiece != null) {
                            FoundPieceCard(state)
                            Spacer(modifier = Modifier.height(4.dp))
                            MarkSoldButton(isSaving = true, onClick = {})
                        }
                    }

                    CheckoutPhase.SOLD -> {
                        if (state.foundPiece != null) SoldSuccessCard(state)
                        Spacer(modifier = Modifier.height(4.dp))
                        PrimaryButton(
                            label = "Sell another piece",
                            onClick = onScanAnotherClick
                        )
                    }

                    CheckoutPhase.NOT_FOUND -> {
                        ResultCard(
                            emoji = "⚠️",
                            title = "This tag is not enrolled",
                            body = "The scanned tag has no stock record. Enroll it first, or place a different piece on the reader.",
                            backgroundColor = Color(0xFFFFEBEE),
                            textColor = Color(0xFFB71C1C)
                        )
                        ScannedEpcLine(state.scannedEpc)
                        SecondaryButton(
                            label = "Scan another piece",
                            onClick = onScanAnotherClick
                        )
                    }

                    CheckoutPhase.NOT_JEWELLERY -> {
                        ResultCard(
                            emoji = "ℹ️",
                            title = "Not sold at the counter",
                            body = "${state.displayName} isn't a product type sold at checkout. " +
                                    "Use the Warehouse flow to dispatch it.",
                            backgroundColor = WarningBg,
                            textColor = WarningText
                        )
                        ScannedEpcLine(state.scannedEpc)
                        SecondaryButton(
                            label = "Scan another piece",
                            onClick = onScanAnotherClick
                        )
                    }

                    CheckoutPhase.ALREADY_SOLD -> {
                        val soldDateText = state.foundPiece?.soldAt?.toDate()?.let { formatDate(it) }
                            ?: "earlier"
                        ResultCard(
                            emoji = "✓",
                            title = "Already sold",
                            body = "${state.displayName} was marked sold on $soldDateText.",
                            backgroundColor = WarningBg,
                            textColor = WarningText
                        )
                        ScannedEpcLine(state.scannedEpc)
                        SecondaryButton(
                            label = "Scan another piece",
                            onClick = onScanAnotherClick
                        )
                    }

                    CheckoutPhase.WRONG_STATUS -> {
                        val piece = state.foundPiece
                        val statusText = UnitStatus.fromKey(piece?.status)?.displayName
                            ?: piece?.status ?: "unknown"
                        ResultCard(
                            emoji = "ℹ️",
                            title = "Cannot sell this piece",
                            body = state.blockedReason
                                ?: "${state.displayName} is \"$statusText\" and can't be marked sold from here.",
                            backgroundColor = WarningBg,
                            textColor = WarningText
                        )
                        ScannedEpcLine(state.scannedEpc)
                        SecondaryButton(
                            label = "Scan another piece",
                            onClick = onScanAnotherClick
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        ) { data -> Snackbar(snackbarData = data) }
    }

    // Confirmation dialog — shown when cashier taps Mark Sold.
    // Confirmation dialog — shown when cashier taps Mark Sold.
    if (showConfirmDialog) {
        val price = state.keyDetails.firstOrNull { it.first == "Tag price" }?.second
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("Mark this piece sold?") },
            text = {
                Column {
                    Text(state.displayName)
                    if (price != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(price, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This will prevent the exit alarm from firing for this piece.",
                        fontSize = 13.sp,
                        color = MediumGray
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmDialog = false
                        onMarkSoldClick()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                ) { Text("Mark Sold", color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) { Text("Cancel") }
            }
        )
    }
}

// ============================================
// PHASE-SPECIFIC CARDS
// ============================================

/** The initial waiting state — cashier hasn't placed a piece yet. */
@Composable
private fun WaitingCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(InfoBg)
            .padding(vertical = 48.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "🛍️",
            fontSize = 56.sp
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Place the piece on the reader",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = InfoText
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "The reader will detect the tag automatically.",
            fontSize = 14.sp,
            color = InfoText
        )
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = InfoText
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Listening…",
                fontSize = 12.sp,
                color = InfoText
            )
        }
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
        Text(text = "Checking inventory…", fontSize = 14.sp, color = DarkText)
    }
}

@Composable
private fun FoundPieceCard(state: JewelleryCheckoutState) {
    val piece = state.foundPiece ?: return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFE8F5E9))
            .padding(16.dp)
    ) {
        Text(
            text = "✓  READY TO SELL",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF2E7D32)
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = state.displayName,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = DarkText
        )
        state.keyDetails
            .filterNot { it.first == "Product" }
            .forEach { (label, value) ->
                Spacer(modifier = Modifier.height(6.dp))
                LabelValue(label = label, value = value)
            }
        Spacer(modifier = Modifier.height(6.dp))
        LabelValue(label = "Tag EPC", value = piece.epc, mono = true)
        (piece.enrolledAt ?: piece.inwardedAt)?.let {
            Spacer(modifier = Modifier.height(6.dp))
            LabelValue(label = "Enrolled", value = formatDate(it.toDate()))
        }
    }
}

/** Big success card shown after a mark-sold completes. */
@Composable
private fun SoldSuccessCard(state: JewelleryCheckoutState) {
    val price = state.keyDetails.firstOrNull { it.first == "Tag price" }?.second
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFE8F5E9))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "✓", fontSize = 56.sp, color = Color(0xFF2E7D32))
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Marked sold",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF1B5E20)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = state.displayName,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = DarkText
        )
        if (price != null) {
            Spacer(Modifier.height(2.dp))
            Text(text = price, fontSize = 14.sp, color = DarkText)
        }
        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
                .padding(12.dp)
        ) {
            Text(
                text = "🔕  This piece will no longer trigger an alarm at the exit gate.",
                fontSize = 13.sp,
                color = DarkText
            )
        }
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
        Text(text = body, fontSize = 13.sp, color = textColor)
    }
}

@Composable
private fun ScannedEpcLine(epc: String) {
    if (epc.isBlank()) return
    Text(
        text = "Scanned: $epc",
        fontSize = 11.sp,
        color = MediumGray,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(start = 4.dp)
    )
}

// ============================================
// SHARED CONTROLS
// ============================================

@Composable
private fun MarkSoldButton(isSaving: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !isSaving,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
    ) {
        if (isSaving) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = Color.White,
                strokeWidth = 2.dp
            )
            Text(
                text = "  Marking sold…",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        } else {
            Text(
                text = "Mark Sold",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(text = label, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SecondaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text = label, fontSize = 14.sp)
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

private fun formatDate(date: Date): String {
    val fmt = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
    return fmt.format(date)
}

// ============================================
// PREVIEWS
// ============================================

@Preview(showBackground = true, heightDp = 700)
@Composable
fun JewelleryCheckoutWaitingPreview() {
    JewelleryCheckoutScreen(
        state = JewelleryCheckoutState(phase = CheckoutPhase.WAITING),
        onBackClick = {},
        onMarkSoldClick = {},
        onScanAnotherClick = {},
        onMessageDismissed = {}
    )
}

@Preview(showBackground = true, heightDp = 700)
@Composable
fun JewelleryCheckoutFoundPreview() {
    JewelleryCheckoutScreen(
        state = JewelleryCheckoutState(
            phase = CheckoutPhase.FOUND,
            scannedEpc = "3000E2801191A504007518B5AC54",
            foundPiece = InventoryUnit(
                epc = "3000E2801191A504007518B5AC54",
                itemCode = "DR-2451",
                category = "jewellery",
                price = 50000.0,
                status = "in_stock"
            )
        ),
        onBackClick = {},
        onMarkSoldClick = {},
        onScanAnotherClick = {},
        onMessageDismissed = {}
    )
}

@Preview(showBackground = true, heightDp = 700)
@Composable
fun JewelleryCheckoutSoldPreview() {
    JewelleryCheckoutScreen(
        state = JewelleryCheckoutState(
            phase = CheckoutPhase.SOLD,
            foundPiece = InventoryUnit(
                epc = "3000E2801191A504007518B5AC54",
                itemCode = "DR-2451",
                category = "jewellery",
                price = 50000.0,
                status = "sold"
            )
        ),
        onBackClick = {},
        onMarkSoldClick = {},
        onScanAnotherClick = {},
        onMessageDismissed = {}
    )
}