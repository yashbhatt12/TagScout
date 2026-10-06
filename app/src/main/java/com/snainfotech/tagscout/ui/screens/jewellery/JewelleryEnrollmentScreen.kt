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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.viewmodel.compose.viewModel
import com.snainfotech.tagscout.ui.components.AppHeader
import com.snainfotech.tagscout.ui.theme.DarkText
import com.snainfotech.tagscout.ui.theme.InfoBg
import com.snainfotech.tagscout.ui.theme.InfoText
import com.snainfotech.tagscout.ui.theme.LightGray
import com.snainfotech.tagscout.ui.theme.MediumGray

/**
 * Jewellery Enrollment screen — scan a tag, enter item code and price, save.
 *
 * The scanner integration is passed as a lambda so this screen stays unit-testable
 * without a live sled. NavGraph wires onScanTriggered to the real RfidScannerManager
 * (matching the pattern used by CycleCountScreen / QuickScanScreen).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JewelleryEnrollmentScreen(
    onBackClick: () -> Unit,
    onScanTriggered: (onEpc: (String) -> Unit) -> Unit = { _ -> },
    modifier: Modifier = Modifier,
    viewModel: JewelleryEnrollmentViewModel = viewModel(factory = JewelleryEnrollmentViewModelFactory())
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Snackbar wiring — show error/success as they appear, then dismiss in ViewModel.
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
                // ── Instructional banner ──────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(InfoBg)
                        .padding(12.dp)
                ) {
                    Text(
                        text = "💍 Tap Scan Tag, then enter the item code and price.",
                        color = InfoText,
                        fontSize = 13.sp
                    )
                }

                // ── Scan Tag button ───────────────────────────────────────
                Button(
                    onClick = {
                        onScanTriggered { epc -> viewModel.onEpcScanned(epc) }
                    },
                    enabled = !state.isSaving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (state.scannedEpc.isBlank()) "Scan Tag" else "Scan Again",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // ── EPC display card ──────────────────────────────────────
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White)
                        .padding(12.dp)
                ) {
                    Text(
                        text = "SCANNED TAG",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MediumGray
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = state.scannedEpc.ifBlank { "No tag scanned yet" },
                        fontSize = 15.sp,
                        fontWeight = if (state.scannedEpc.isBlank()) FontWeight.Normal else FontWeight.Bold,
                        color = if (state.scannedEpc.isBlank()) MediumGray else DarkText,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // ── Item Code input ───────────────────────────────────────
                OutlinedTextField(
                    value = state.itemCode,
                    onValueChange = viewModel::onItemCodeChanged,
                    label = { Text("Item Code") },
                    placeholder = { Text("e.g. DR-2451") },
                    singleLine = true,
                    enabled = state.scannedEpc.isNotBlank() && !state.isSaving,
                    modifier = Modifier.fillMaxWidth()
                )

                // ── Price input ───────────────────────────────────────────
                OutlinedTextField(
                    value = state.price,
                    onValueChange = viewModel::onPriceChanged,
                    label = { Text("Price (₹)") },
                    placeholder = { Text("e.g. 45000") },
                    singleLine = true,
                    enabled = state.scannedEpc.isNotBlank() && !state.isSaving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(4.dp))

                // ── Save button ───────────────────────────────────────────
                Button(
                    onClick = viewModel::savePiece,
                    enabled = state.scannedEpc.isNotBlank() &&
                            state.itemCode.isNotBlank() &&
                            state.price.isNotBlank() &&
                            !state.isSaving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    if (state.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.height(0.dp))
                        Text(
                            text = "  Saving…",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                    } else {
                        Text(
                            text = "Save Piece",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // ── Clear Form button ─────────────────────────────────────
                TextButton(
                    onClick = viewModel::clearForm,
                    enabled = !state.isSaving,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Clear Form",
                        color = MediumGray,
                        fontSize = 14.sp
                    )
                }
            }
        }

        // ── Snackbar overlay ──────────────────────────────────────────────
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        ) { data ->
            Snackbar(snackbarData = data)
        }
    }
}

// ============================================
// PREVIEWS
// ============================================

@Preview(showBackground = true, heightDp = 700)
@Composable
fun JewelleryEnrollmentScreenPreview() {
    JewelleryEnrollmentScreen(
        onBackClick = {}
    )
}