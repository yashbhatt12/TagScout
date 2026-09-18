package com.snainfotech.tagscout.ui.screens.wms

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snainfotech.tagscout.data.wms.GrnService
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
fun InwardScreen(
    state: InwardState,
    onBackClick: () -> Unit,
    onGrnReferenceChange: (String) -> Unit,
    onFilePicked: (android.net.Uri, String) -> Unit,
    onConfirm: () -> Unit,
    onReset: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val fileName = uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.substringAfterLast(':')
                ?.replace("%20", " ")
                ?.take(60)
                ?: "grn.xlsx"
            onFilePicked(uri, fileName)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LightGray)
        ) {
            AppHeader(
                title = when (state.step) {
                    InwardStep.ENTER_GRN -> "Inward (GRN)"
                    InwardStep.VALIDATING -> "Validating…"
                    InwardStep.REVIEW_VALIDATION -> "Review GRN"
                    InwardStep.COMMITTING -> "Submitting…"
                    InwardStep.DONE -> "GRN Submitted"
                },
                showBackButton = state.step == InwardStep.ENTER_GRN || state.step == InwardStep.DONE,
                onBackClick = onBackClick,
                showMenu = false
            )

            when (state.step) {
                InwardStep.ENTER_GRN -> EnterGrnStep(
                    state = state,
                    onGrnReferenceChange = onGrnReferenceChange,
                    onPickFile = {
                        filePickerLauncher.launch(arrayOf(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            "application/vnd.ms-excel"
                        ))
                    }
                )

                InwardStep.VALIDATING, InwardStep.COMMITTING -> LoadingStep(
                    label = if (state.step == InwardStep.VALIDATING)
                        "Validating rows against database…"
                    else
                        "Writing to inventory…"
                )

                InwardStep.REVIEW_VALIDATION -> ReviewStep(
                    state = state,
                    onConfirm = onConfirm,
                    onReset = onReset
                )

                InwardStep.DONE -> DoneStep(
                    state = state,
                    onReset = onReset,
                    onBackClick = onBackClick
                )
            }
        }

        if (state.message != null) {
            LaunchedEffect(state.message) {
                kotlinx.coroutines.delay(4000)
                onDismissMessage()
            }
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                action = { TextButton(onClick = onDismissMessage) { Text("OK", color = Color.White) } },
                containerColor = DarkText
            ) {
                Text(state.message, color = Color.White)
            }
        }
    }
}

// ── STEP: Enter GRN + pick file ─────────────────────────────

@Composable
private fun EnterGrnStep(
    state: InwardState,
    onGrnReferenceChange: (String) -> Unit,
    onPickFile: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
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
                    text = "Step 1 — GRN Reference",
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MediumGray
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Enter the GRN number for this batch. It'll be attached to every inward record created.",
                    fontSize = 12.sp, color = MediumGray
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.grnReference,
                    onValueChange = onGrnReferenceChange,
                    label = { Text("GRN Number") },
                    placeholder = { Text("e.g. GRN-2026-001") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary, cursorColor = Primary, focusedLabelColor = Primary
                    )
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Step 2 — Upload GRN File",
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MediumGray
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "The Excel file must have these columns: SKU, EPC, Serial Number, Bin Code, Warehouse Name (Notes optional).",
                    fontSize = 12.sp, color = MediumGray
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onPickFile,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                    enabled = state.grnReference.isNotBlank()
                ) {
                    Text("Choose Excel File", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                if (state.grnReference.isBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Enter the GRN number first",
                        fontSize = 10.sp, color = MediumGray, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

// ── STEP: Loading (validating or committing) ───────────────

@Composable
private fun LoadingStep(label: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Primary)
            Spacer(modifier = Modifier.height(16.dp))
            Text(label, color = DarkText, fontSize = 14.sp)
        }
    }
}

// ── STEP: Review validation results ────────────────────────

@Composable
private fun ReviewStep(
    state: InwardState,
    onConfirm: () -> Unit,
    onReset: () -> Unit
) {
    val hasErrors = state.validationHasErrors

    Column(modifier = Modifier.fillMaxSize()) {
        // Summary banner
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (hasErrors) ErrorRed.copy(alpha = 0.08f) else SuccessGreen.copy(alpha = 0.08f))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Column {
                Text(
                    text = if (hasErrors)
                        "File Rejected — ${state.errorRowCount} of ${state.totalRowCount} rows have errors"
                    else
                        "All ${state.totalRowCount} rows valid — ready to inward",
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = if (hasErrors) ErrorRed else SuccessGreen
                )
                Text(
                    text = "GRN: ${state.grnReference}  •  File: ${state.fileName}",
                    fontSize = 11.sp, color = MediumGray,
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (hasErrors) {
                    Text(
                        text = "Fix the errors in your file and try again. Nothing has been written to inventory.",
                        fontSize = 11.sp, color = DarkText,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
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
            state.validatedRows.forEach { row ->
                RowResultCard(row)
            }
            Spacer(modifier = Modifier.height(8.dp))
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
                    onClick = onReset,
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text(
                        text = if (hasErrors) "Back — Try Another File" else "Cancel",
                        color = MediumGray, fontWeight = FontWeight.SemiBold
                    )
                }
                if (!hasErrors) {
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.weight(2f).height(48.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Amber)
                    ) {
                        Text(
                            text = "Confirm Inward — ${state.totalRowCount} rows",
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RowResultCard(row: GrnService.ValidatedRow) {
    val hasErrors = row.errors.isNotEmpty()
    val borderColor = if (hasErrors) ErrorRed else BorderGray

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(8.dp)),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (hasErrors) ErrorRed.copy(alpha = 0.03f) else Color.White
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Row ${row.rowNumber}",
                    fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    color = if (hasErrors) ErrorRed else Primary
                )
                Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                Text(
                    text = "•  ${row.sku}",
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = DarkText,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = row.binCode,
                    fontSize = 11.sp, color = MediumGray
                )
            }
            Text(
                text = "EPC: ${row.epc}",
                fontSize = 10.sp, color = MediumGray,
                modifier = Modifier.padding(top = 2.dp)
            )
            if (row.serialNumber.isNotBlank()) {
                Text(
                    text = "Serial: ${row.serialNumber}",
                    fontSize = 10.sp, color = MediumGray
                )
            }

            if (hasErrors) {
                Spacer(modifier = Modifier.height(4.dp))
                row.errors.forEach { err ->
                    Row(modifier = Modifier.padding(vertical = 1.dp)) {
                        Text(text = "⚠", fontSize = 10.sp, color = ErrorRed)
                        Spacer(modifier = Modifier.padding(horizontal = 2.dp))
                        Text(text = err, fontSize = 10.sp, color = ErrorRed)
                    }
                }
            }
        }
    }
}

// ── STEP: Done (success or partial failure) ────────────────

@Composable
private fun DoneStep(
    state: InwardState,
    onReset: () -> Unit,
    onBackClick: () -> Unit
) {
    val fullSuccess = state.commitSuccess

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = if (fullSuccess) "✓" else "!",
            fontSize = 64.sp,
            color = if (fullSuccess) SuccessGreen else Amber,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = if (fullSuccess) "GRN Submitted" else "Partially Complete",
            fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            color = if (fullSuccess) SuccessGreen else Amber
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "${state.inwardedCount} unit${if (state.inwardedCount != 1) "s" else ""} inwarded against ${state.grnReference}",
            fontSize = 14.sp, color = DarkText, textAlign = TextAlign.Center
        )

        if (!fullSuccess && state.commitFailedAtRow != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = Amber.copy(alpha = 0.08f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Stopped at row ${state.commitFailedAtRow}",
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = DarkText
                    )
                    if (!state.commitError.isNullOrBlank()) {
                        Text(
                            text = state.commitError,
                            fontSize = 11.sp, color = MediumGray,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Rows before this were inwarded successfully. You can prepare a fresh GRN with only the remaining rows.",
                        fontSize = 11.sp, color = MediumGray
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onReset,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Primary)
        ) {
            Text("Submit Another GRN", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(
            onClick = onBackClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Back to WMS Menu", color = MediumGray)
        }
    }
}