package com.snainfotech.tagscout.ui.screens.wms

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snainfotech.tagscout.data.wms.Bin
import com.snainfotech.tagscout.data.wms.CycleCountService
import com.snainfotech.tagscout.data.wms.Rack
import com.snainfotech.tagscout.data.wms.Warehouse
import com.snainfotech.tagscout.ui.components.AppHeader
import com.snainfotech.tagscout.ui.theme.Amber
import com.snainfotech.tagscout.ui.theme.BorderGray
import com.snainfotech.tagscout.ui.theme.DarkText
import com.snainfotech.tagscout.ui.theme.ErrorRed
import com.snainfotech.tagscout.ui.theme.LightGray
import com.snainfotech.tagscout.ui.theme.MediumGray
import com.snainfotech.tagscout.ui.theme.Primary
import com.snainfotech.tagscout.ui.theme.SuccessGreen

@Composable
fun CycleCountScreen(
    state: CycleCountState,
    onBackClick: () -> Unit,
    onUpdateReference: (String) -> Unit,
    onProceedToPickBin: () -> Unit,
    onSelectWarehouse: (Warehouse) -> Unit,
    onSelectRack: (Rack) -> Unit,
    onSelectBin: (Bin) -> Unit,
    onProceedToScanning: () -> Unit,
    onStartScanning: () -> Unit,
    onPauseScanning: () -> Unit,
    onStopAndReview: () -> Unit,
    onBackToPickBin: () -> Unit,
    onCommit: () -> Unit,
    onReset: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LightGray)
        ) {
            AppHeader(
                title = when (state.step) {
                    CycleCountStep.ENTER_REF -> "Cycle Count"
                    CycleCountStep.PICK_BIN -> "Pick Bin to Count"
                    CycleCountStep.SCANNING -> "Scanning Bin ${state.selectedBin?.binCode ?: ""}"
                    CycleCountStep.REVIEW -> "Review Discrepancies"
                    CycleCountStep.DONE -> "Cycle Count Done"
                },
                showBackButton = state.step == CycleCountStep.ENTER_REF ||
                        state.step == CycleCountStep.DONE,
                onBackClick = onBackClick,
                showMenu = false
            )

            when (state.step) {
                CycleCountStep.ENTER_REF -> EnterRefStep(
                    state = state,
                    onUpdate = onUpdateReference,
                    onNext = onProceedToPickBin
                )
                CycleCountStep.PICK_BIN -> PickBinStep(
                    state = state,
                    onSelectWarehouse = onSelectWarehouse,
                    onSelectRack = onSelectRack,
                    onSelectBin = onSelectBin,
                    onNext = onProceedToScanning
                )
                CycleCountStep.SCANNING -> ScanningStep(
                    state = state,
                    onStart = onStartScanning,
                    onPause = onPauseScanning,
                    onStopAndReview = onStopAndReview,
                    onBack = onBackToPickBin
                )
                CycleCountStep.REVIEW -> ReviewStep(
                    state = state,
                    onCommit = onCommit,
                    onBack = onBackToPickBin
                )
                CycleCountStep.DONE -> DoneStep(
                    state = state,
                    onReset = onReset,
                    onBackClick = onBackClick
                )
            }
        }

        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(color = Primary) }
        }

        if (state.message != null) {
            LaunchedEffect(state.message) {
                kotlinx.coroutines.delay(3500)
                onDismissMessage()
            }
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                action = { TextButton(onClick = onDismissMessage) { Text("OK", color = Color.White) } },
                containerColor = DarkText
            ) { Text(state.message, color = Color.White) }
        }
    }
}

// ── STEP: Enter reference ─────────────────────────────────

@Composable
private fun EnterRefStep(
    state: CycleCountState,
    onUpdate: (String) -> Unit,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Step 1 — Reference Number",
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MediumGray
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Enter a reference for this cycle count. Every reconciliation movement will be tagged with it so you can review the session later.",
                    fontSize = 12.sp, color = MediumGray
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.reference,
                    onValueChange = onUpdate,
                    label = { Text("Reference Number") },
                    placeholder = { Text("e.g. CC-2026-001") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary, cursorColor = Primary, focusedLabelColor = Primary
                    )
                )
            }
        }

        Button(
            onClick = onNext,
            enabled = state.canProceedFromRef,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Primary)
        ) {
            Text("Continue", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ── STEP: Pick bin ────────────────────────────────────────

@Composable
private fun PickBinStep(
    state: CycleCountState,
    onSelectWarehouse: (Warehouse) -> Unit,
    onSelectRack: (Rack) -> Unit,
    onSelectBin: (Bin) -> Unit,
    onNext: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Ref: ${state.reference}",
                fontSize = 12.sp, color = MediumGray,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            if (state.warehouses.size > 1) {
                ChipPicker(
                    label = "Warehouse",
                    items = state.warehouses.map { it.name },
                    selectedIndex = state.warehouses.indexOfFirst { it.id == state.selectedWarehouse?.id },
                    onSelect = { i -> onSelectWarehouse(state.warehouses[i]) }
                )
            }

            if (state.racks.isNotEmpty()) {
                ChipPicker(
                    label = "Rack",
                    items = state.racks.map { it.name },
                    selectedIndex = state.racks.indexOfFirst { it.id == state.selectedRack?.id },
                    onSelect = { i -> onSelectRack(state.racks[i]) }
                )
            }

            if (state.bins.isNotEmpty()) {
                ChipPicker(
                    label = "Bin",
                    items = state.bins.map { it.binCode },
                    selectedIndex = state.bins.indexOfFirst { it.id == state.selectedBin?.id },
                    onSelect = { i -> onSelectBin(state.bins[i]) }
                )
            }

            if (state.warehouses.isEmpty() && !state.isLoading) {
                Text(
                    text = "No warehouses set up. Go to WMS → Warehouse Setup first.",
                    fontSize = 12.sp, color = MediumGray
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Row(modifier = Modifier.padding(12.dp)) {
                Button(
                    onClick = onNext,
                    enabled = state.canProceedFromBin,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) {
                    Text(
                        text = if (state.selectedBin != null)
                            "Start Counting ${state.selectedBin.binCode}"
                        else "Pick a bin above",
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun ChipPicker(
    label: String,
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Column {
        Text(
            text = label,
            fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MediumGray,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items.forEachIndexed { i, text ->
                val selected = i == selectedIndex
                val bg = if (selected) Amber else Color.White
                val fg = if (selected) Color.White else DarkText
                val border = if (selected) Amber else BorderGray
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(bg)
                        .border(1.dp, border, RoundedCornerShape(16.dp))
                        .clickable { onSelect(i) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(text = text, fontSize = 12.sp, color = fg, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ── STEP: Scanning ────────────────────────────────────────

@Composable
private fun ScanningStep(
    state: CycleCountState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onStopAndReview: () -> Unit,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Big counter
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(vertical = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = state.scannedCount.toString(),
                    fontSize = 56.sp, fontWeight = FontWeight.Bold, color = Amber
                )
                Text(
                    text = if (state.scannedCount == 1) "unique tag scanned" else "unique tags scanned",
                    fontSize = 12.sp, color = MediumGray
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier
                        .height(8.dp).width(8.dp)
                        .background(if (state.scanning) SuccessGreen else MediumGray, RoundedCornerShape(4.dp))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (state.scanning) "Scanning" else "Paused",
                        fontSize = 11.sp, color = if (state.scanning) SuccessGreen else MediumGray,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Instructions + recent EPCs list
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            Text(
                text = "Walk to bin ${state.selectedBin?.binCode ?: ""} and pull the sled trigger. Sweep the sled across every visible tag. When done, tap Stop & Review.",
                fontSize = 12.sp, color = MediumGray,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            if (state.scannedEpcs.isNotEmpty()) {
                Text(
                    text = "Scanned EPCs",
                    fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MediumGray,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                state.scannedEpcs.take(50).forEach { epc ->
                    Text(
                        text = epc,
                        fontSize = 11.sp, color = DarkText,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
                if (state.scannedEpcs.size > 50) {
                    Text(
                        text = "… and ${state.scannedEpcs.size - 50} more",
                        fontSize = 11.sp, color = MediumGray, fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Bottom action bar
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.height(48.dp)
                ) { Text("Back", color = MediumGray, fontWeight = FontWeight.SemiBold) }

                if (state.scanning) {
                    Button(
                        onClick = onPause,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MediumGray)
                    ) { Text("Pause", color = Color.White, fontWeight = FontWeight.SemiBold) }
                } else {
                    Button(
                        onClick = onStart,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)
                    ) {
                        Text(
                            text = if (state.scannedCount == 0) "Start Scanning" else "Resume",
                            color = Color.White, fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Button(
                    onClick = onStopAndReview,
                    enabled = state.scannedCount > 0 || !state.scanning,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Amber)
                ) { Text("Stop & Review", color = Color.White, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

// ── STEP: Review ──────────────────────────────────────────

@Composable
private fun ReviewStep(
    state: CycleCountState,
    onCommit: () -> Unit,
    onBack: () -> Unit
) {
    val report = state.report ?: return
    Column(modifier = Modifier.fillMaxSize()) {

        // Summary strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatBlock("Matched", report.matchedCount.toString(), SuccessGreen)
            StatBlock("Missing", report.missingCount.toString(), ErrorRed)
            StatBlock("Unexpected", report.unexpectedCount.toString(), Amber)
        }

        // Rows list
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (report.rows.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("📭", fontSize = 40.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Nothing to reconcile — bin is empty in the database and no tags were scanned.",
                            fontSize = 12.sp, color = MediumGray, textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                report.rows.forEach { row -> ReconciliationRowCard(row) }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        // Bottom bar
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f).height(48.dp)
                ) { Text("Recount", color = MediumGray, fontWeight = FontWeight.SemiBold) }
                Button(
                    onClick = onCommit,
                    modifier = Modifier.weight(2f).height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) {
                    Text(
                        text = if (report.hasNoDiscrepancies) "Confirm — No Discrepancies"
                        else "Log ${report.discrepancyCount} Discrepancies",
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun StatBlock(label: String, value: String, color: Color) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.1f))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text = value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
        Text(text = label, fontSize = 10.sp, color = MediumGray)
    }
}

@Composable
private fun ReconciliationRowCard(row: CycleCountService.ReconciliationRow) {
    val (color, label) = when (row.classification) {
        CycleCountService.Classification.MATCHED -> SuccessGreen to "MATCHED"
        CycleCountService.Classification.MISSING -> ErrorRed to "MISSING"
        CycleCountService.Classification.UNEXPECTED -> Amber to "UNEXPECTED"
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.04f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    fontSize = 9.sp, fontWeight = FontWeight.Bold, color = color,
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(color.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = row.sku.ifBlank { "(unknown SKU)" },
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = DarkText,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                text = "EPC: ${row.epc}",
                fontSize = 10.sp, color = MediumGray,
                modifier = Modifier.padding(top = 4.dp)
            )
            if (row.classification == CycleCountService.Classification.UNEXPECTED) {
                Text(
                    text = if (row.expectedBinCode.isNotBlank())
                        "Database has this at ${row.expectedBinCode}"
                    else
                        "Not in database",
                    fontSize = 10.sp, color = Amber, fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

// ── STEP: Done ────────────────────────────────────────────

@Composable
private fun DoneStep(
    state: CycleCountState,
    onReset: () -> Unit,
    onBackClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))
        Text(text = "✓", fontSize = 64.sp, color = SuccessGreen, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = if (state.hadNoDiscrepancies) "All Clear" else "Discrepancies Logged",
            fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = SuccessGreen
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (state.hadNoDiscrepancies)
                "Cycle count for ${state.selectedBin?.binCode ?: ""} completed with no discrepancies. No adjustments needed."
            else
                "${state.movementsWritten} discrepancy movement${if (state.movementsWritten != 1) "s" else ""} written against ${state.reference}. Managers can review these in the movements ledger to decide on next steps.",
            fontSize = 13.sp, color = DarkText, textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onReset,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Primary)
        ) { Text("New Cycle Count", fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }

        Spacer(modifier = Modifier.height(8.dp))
        TextButton(
            onClick = onBackClick,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Back to WMS Menu", color = MediumGray) }
    }
}