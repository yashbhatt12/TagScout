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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Jewellery Enrollment screen — scan a new piece and record its details.
 *
 * Pure-UI screen: state and callbacks come from the NavGraph wrapper which
 * owns the ViewModel and the scanner lifecycle. Whenever
 * [state.isWaitingForTag] is true, the NavGraph has the sled running on low
 * antenna power. Tag reads come back via onEpcScanned on the ViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JewelleryEnrollmentScreen(
    state: JewelleryEnrollmentState,
    onBackClick: () -> Unit,
    onItemCodeChanged: (String) -> Unit,
    onPriceChanged: (String) -> Unit,
    onSaveClick: () -> Unit,
    onScanAnotherClick: () -> Unit,
    onMessageDismissed: () -> Unit,
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }

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
                title = "Enroll Piece",
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
                    EnrollmentPhase.WAITING -> WaitingCard()

                    EnrollmentPhase.LOOKING_UP -> LookingUpCard()

                    EnrollmentPhase.NEW_PIECE -> {
                        ScannedEpcCard(state.scannedEpc)
                        EnrollmentForm(
                            itemCode = state.itemCode,
                            price = state.price,
                            onItemCodeChanged = onItemCodeChanged,
                            onPriceChanged = onPriceChanged,
                            enabled = true
                        )
                        SaveButton(
                            enabled = state.canSave,
                            isSaving = false,
                            onClick = onSaveClick
                        )
                        SecondaryButton(
                            label = "Scan a different piece",
                            onClick = onScanAnotherClick
                        )
                    }

                    EnrollmentPhase.SAVING -> {
                        ScannedEpcCard(state.scannedEpc)
                        EnrollmentForm(
                            itemCode = state.itemCode,
                            price = state.price,
                            onItemCodeChanged = { /* locked */ },
                            onPriceChanged = { /* locked */ },
                            enabled = false
                        )
                        SaveButton(
                            enabled = false,
                            isSaving = true,
                            onClick = {}
                        )
                    }

                    EnrollmentPhase.DUPLICATE -> {
                        DuplicateCard(
                            scannedEpc = state.scannedEpc,
                            existing = state.existingPiece
                        )
                        SecondaryButton(
                            label = "Scan another piece",
                            onClick = onScanAnotherClick
                        )
                    }

                    EnrollmentPhase.ENROLLED -> {
                        state.savedPiece?.let { EnrolledSuccessCard(it) }
                        Spacer(modifier = Modifier.height(4.dp))
                        PrimaryButton(
                            label = "Enroll another piece",
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
}

// ============================================
// PHASE-SPECIFIC CARDS
// ============================================

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
        Text(text = "💎", fontSize = 56.sp)
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
            Text(text = "Listening…", fontSize = 12.sp, color = InfoText)
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
        Text(text = "Checking if already enrolled…", fontSize = 14.sp, color = DarkText)
    }
}

@Composable
private fun ScannedEpcCard(epc: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFE8F5E9))
            .padding(14.dp)
    ) {
        Text(
            text = "✓  TAG DETECTED",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF2E7D32)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = epc,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            color = DarkText
        )
    }
}

@Composable
private fun EnrollmentForm(
    itemCode: String,
    price: String,
    onItemCodeChanged: (String) -> Unit,
    onPriceChanged: (String) -> Unit,
    enabled: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "PIECE DETAILS",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MediumGray
        )

        OutlinedTextField(
            value = itemCode,
            onValueChange = onItemCodeChanged,
            label = { Text("Item code (e.g. DR-2451)") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = price,
            onValueChange = onPriceChanged,
            label = { Text("Price (₹)") },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun DuplicateCard(scannedEpc: String, existing: InventoryUnit?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFFFEBEE))
            .padding(20.dp)
    ) {
        Text(
            text = "⚠️  Already enrolled",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFB71C1C)
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "This tag is already in inventory. If you need to replace it, " +
                    "unenroll the existing piece first.",
            fontSize = 13.sp,
            color = Color(0xFFB71C1C)
        )

        if (existing != null) {
            Spacer(modifier = Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White)
                    .padding(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LabelValue("Item Code", existing.itemCode ?: "—")
                    LabelValue(
                        label = "Price",
                        value = if (existing.price != null) "₹${existing.price.toInt()}" else "—"
                    )
                    LabelValue("Status", existing.status)
                    if (existing.category != null) {
                        LabelValue("Category", existing.category)
                    }
                    if (existing.inwardedAt != null) {
                        LabelValue("Enrolled", formatDate(existing.inwardedAt.toDate()))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Scanned: $scannedEpc",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MediumGray
        )
    }
}

@Composable
private fun EnrolledSuccessCard(piece: InventoryUnit) {
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
            text = "Piece enrolled",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF1B5E20)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = piece.itemCode ?: "—",
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = DarkText
        )
        if (piece.price != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "₹${piece.price.toInt()}",
                fontSize = 14.sp,
                color = DarkText
            )
        }
        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
                .padding(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "This piece is now tracked.",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = DarkText
                )
                Text(
                    text = "If it leaves the shop without being marked sold, the exit alarm will fire.",
                    fontSize = 12.sp,
                    color = MediumGray
                )
            }
        }
    }
}

// ============================================
// SHARED CONTROLS
// ============================================

@Composable
private fun SaveButton(enabled: Boolean, isSaving: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
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
                text = "  Saving…",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        } else {
            Text(
                text = "Save",
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
private fun LabelValue(label: String, value: String) {
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
            color = DarkText
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
fun JewelleryEnrollmentWaitingPreview() {
    JewelleryEnrollmentScreen(
        state = JewelleryEnrollmentState(phase = EnrollmentPhase.WAITING),
        onBackClick = {},
        onItemCodeChanged = {},
        onPriceChanged = {},
        onSaveClick = {},
        onScanAnotherClick = {},
        onMessageDismissed = {}
    )
}

@Preview(showBackground = true, heightDp = 700)
@Composable
fun JewelleryEnrollmentNewPiecePreview() {
    JewelleryEnrollmentScreen(
        state = JewelleryEnrollmentState(
            phase = EnrollmentPhase.NEW_PIECE,
            scannedEpc = "3000E2801191A504007518B5AC54",
            itemCode = "DR-2451",
            price = "50000"
        ),
        onBackClick = {},
        onItemCodeChanged = {},
        onPriceChanged = {},
        onSaveClick = {},
        onScanAnotherClick = {},
        onMessageDismissed = {}
    )
}

@Preview(showBackground = true, heightDp = 700)
@Composable
fun JewelleryEnrollmentDuplicatePreview() {
    JewelleryEnrollmentScreen(
        state = JewelleryEnrollmentState(
            phase = EnrollmentPhase.DUPLICATE,
            scannedEpc = "3000E2801191A504007518B5AC54",
            existingPiece = InventoryUnit(
                epc = "3000E2801191A504007518B5AC54",
                itemCode = "DR-2451",
                category = "jewellery",
                price = 50000.0,
                status = "in_stock"
            )
        ),
        onBackClick = {},
        onItemCodeChanged = {},
        onPriceChanged = {},
        onSaveClick = {},
        onScanAnotherClick = {},
        onMessageDismissed = {}
    )
}

@Preview(showBackground = true, heightDp = 700)
@Composable
fun JewelleryEnrollmentEnrolledPreview() {
    JewelleryEnrollmentScreen(
        state = JewelleryEnrollmentState(
            phase = EnrollmentPhase.ENROLLED,
            savedPiece = InventoryUnit(
                epc = "3000E2801191A504007518B5AC54",
                itemCode = "DR-2451",
                category = "jewellery",
                price = 50000.0,
                status = "in_stock"
            )
        ),
        onBackClick = {},
        onItemCodeChanged = {},
        onPriceChanged = {},
        onSaveClick = {},
        onScanAnotherClick = {},
        onMessageDismissed = {}
    )
}