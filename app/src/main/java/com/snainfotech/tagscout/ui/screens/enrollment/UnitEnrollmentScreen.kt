package com.snainfotech.tagscout.ui.screens.enrollment

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snainfotech.tagscout.data.config.CoreField
import com.snainfotech.tagscout.data.config.LabelKey
import com.snainfotech.tagscout.data.wms.Product
import com.snainfotech.tagscout.data.wms.type as typeKey
import com.snainfotech.tagscout.ui.components.AppHeader
import com.snainfotech.tagscout.ui.components.CustomFieldInput
import com.snainfotech.tagscout.ui.components.DateField
import com.snainfotech.tagscout.ui.components.FormError
import com.snainfotech.tagscout.ui.components.FormHint
import com.snainfotech.tagscout.ui.components.FormPane
import com.snainfotech.tagscout.ui.components.FormSection
import com.snainfotech.tagscout.ui.components.FormTextField
import com.snainfotech.tagscout.sdk.Dr90xState
import com.snainfotech.tagscout.ui.components.KeyboardWedgeBuffer
import com.snainfotech.tagscout.ui.components.LabeledDropdown
import com.snainfotech.tagscout.ui.components.PrimaryActionButton
import com.snainfotech.tagscout.ui.components.SecondaryActionButton
import com.snainfotech.tagscout.ui.theme.BorderGray
import com.snainfotech.tagscout.ui.theme.CardBg
import com.snainfotech.tagscout.ui.theme.DarkText
import com.snainfotech.tagscout.ui.theme.ErrorRed
import com.snainfotech.tagscout.ui.theme.InfoBg
import com.snainfotech.tagscout.ui.theme.InfoText
import com.snainfotech.tagscout.ui.theme.LightGray
import com.snainfotech.tagscout.ui.theme.MediumGray
import com.snainfotech.tagscout.ui.theme.Primary
import com.snainfotech.tagscout.ui.theme.SuccessGreen
import com.snainfotech.tagscout.ui.theme.WarningBg
import com.snainfotech.tagscout.ui.theme.WarningOrange
import com.snainfotech.tagscout.ui.theme.WarningText

/**
 * Scan-based enrollment for every product type.
 *
 * Readers:
 *   - Sled: NavGraph runs the scanner while state.isListening and calls vm.onTagScanned.
 *   - Desktop pad (USB): when its serial session is Ready, NavGraph reads it
 *     through app.desktopReader and calls vm.onTagScanned(epc, tid). Otherwise
 *     (keyboard mode) this screen captures the typed EPC while listening, so
 *     staff never need to tap a text box first.
 *   - Fixed reader: fed later through vm.onTagsReceived.
 *
 * Layout: content is capped at a comfortable width on tablets; in batch
 * details on a wide screen, the piece list sits beside the form.
 */
@Composable
fun UnitEnrollmentScreen(
    viewModel: UnitEnrollmentViewModel,
    sledConnected: Boolean,
    onBack: () -> Unit,
    onConnectSled: () -> Unit,
    showHeader: Boolean = true,             // false inside the Enroll screen's tabs
    desktopState: Dr90xState = Dr90xState.Closed,
    onRetryDesktop: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }

    // ── Desktop reader: keyboard-mode capture ──
    val desktopListening = state.reader == ReaderMode.DESKTOP && state.isListening
    val focusRequester = remember { FocusRequester() }
    val wedge = remember { KeyboardWedgeBuffer() }
    LaunchedEffect(desktopListening) {
        wedge.clear()
        if (desktopListening) runCatching { focusRequester.requestFocus() }
    }

    var confirmDiscard by remember { mutableStateOf(false) }

    // Setup → leave the screen. Scanning / details → back to setup (reader off),
    // asking first if tags would be lost. Result → setup.
    fun handleBack() {
        when (state.phase) {
            EnrollPhase.SETUP -> onBack()
            EnrollPhase.SAVING -> Unit
            EnrollPhase.RESULT -> viewModel.changeSetup()
            EnrollPhase.SCANNING, EnrollPhase.DETAILS -> {
                if (state.hasUnsavedPieces) {
                    confirmDiscard = true
                } else {
                    viewModel.backToSetup()
                }
            }
        }
    }
    BackHandler(enabled = state.phase != EnrollPhase.SETUP) { handleBack() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LightGray)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (!desktopListening || event.key == Key.Back) {
                    false
                } else {
                    wedge.onKey(event)?.let { viewModel.onTagScanned(it) }
                    true
                }
            }
    ) {
        if (showHeader) AppHeader(
            title = "Enroll stock",
            showBackButton = true,
            onBackClick = { handleBack() },
            showMenu = false
        )

        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Primary)
            }
            return@Column
        }

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            val wide = maxWidth >= 720.dp
            when (state.phase) {
                EnrollPhase.SETUP -> SetupPane(state, viewModel, sledConnected, onConnectSled, desktopState, onRetryDesktop)
                EnrollPhase.SCANNING -> ScanningPane(state, viewModel, sledConnected, onConnectSled)
                EnrollPhase.DETAILS, EnrollPhase.SAVING -> DetailsPane(state, viewModel, wide)
                EnrollPhase.RESULT -> ResultPane(state, viewModel)
            }
        }
    }
    // ── After a save: enroll another, or back to the menu ──
    if (state.allDone) {
        val enrolled = state.lastSummary?.enrolled ?: 0
        val skipped = state.pieces.count { it.status == PieceStatus.ALREADY_ENROLLED }
        AlertDialog(
            // A choice is required; tapping outside or Back does nothing.
            onDismissRequest = {},
            title = {
                Text(
                    when (enrolled) {
                        0 -> "Nothing new enrolled"
                        1 -> "Unit enrolled"
                        else -> "$enrolled units enrolled"
                    }
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (skipped > 0) {
                        Text(
                            "$skipped tag${if (skipped == 1) " was" else "s were"} already enrolled and skipped.",
                            fontSize = 13.sp,
                            color = MediumGray
                        )
                    }
                    Text("Do you want to enroll another unit or go back to the menu?")
                }
            },
            confirmButton = {
                Button(
                    onClick = viewModel::enrollMore,
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) { Text("Enroll another") }
            },
            dismissButton = {
                TextButton(onClick = onBack) { Text("Back to menu", color = Primary) }
            }
        )
    }

    // ── Back pressed with unsaved tags ──
    if (confirmDiscard) {
        val unsaved = state.pieces.count { it.isSaveable || it.status == PieceStatus.CHECKING }
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard scanned tags?") },
            text = {
                Text("$unsaved tag${if (unsaved == 1) " hasn't" else "s haven't"} been saved. Going back will discard ${if (unsaved == 1) "it" else "them"}.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    viewModel.discardAndSetup()
                }) { Text("Discard", color = ErrorRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep", color = Primary) }
            }
        )
    }
}

// ── Setup ─────────────────────────────────────────────────────────────────

@Composable
private fun SetupPane(
    state: UnitEnrollmentState,
    vm: UnitEnrollmentViewModel,
    sledConnected: Boolean,
    onConnectSled: () -> Unit,
    desktopState: Dr90xState,
    onRetryDesktop: () -> Unit
) {
    FormPane(
        bar = {
            PrimaryActionButton(
                label = "Start scanning",
                enabled = state.setupComplete && state.reader != ReaderMode.FIXED,
                onClick = vm::startScanning
            )
        }
    ) {
        ReaderSection(state, vm, sledConnected, onConnectSled, desktopState, onRetryDesktop)
        ProductSection(state, vm)
        LocationSection(state, vm)
        if (state.tracksBatches) BatchSection(state, vm)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSection(
    state: UnitEnrollmentState,
    vm: UnitEnrollmentViewModel,
    sledConnected: Boolean,
    onConnectSled: () -> Unit,
    desktopState: Dr90xState,
    onRetryDesktop: () -> Unit
) {
    FormSection("Reader") {
        // The handheld sled isn't used for enrollment; bulk goes by Excel.
        ReaderMode.entries.filter { it != ReaderMode.SLED }.forEach { mode ->
            ChoiceRow(
                title = mode.label,
                subtitle = mode.description,
                selected = state.reader == mode,
                onClick = { vm.setReader(mode) }
            )
        }

        if (state.reader.captureSelectable) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CaptureMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.capture == mode,
                        onClick = { vm.setCapture(mode) },
                        label = { Text(mode.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = InfoBg,
                            selectedLabelColor = InfoText
                        )
                    )
                }
            }
        }

        if (state.reader == ReaderMode.SLED && !sledConnected) {
            Notice(text = "The sled isn't connected.", actionLabel = "Connect", onAction = onConnectSled)
        }
        if (state.reader == ReaderMode.DESKTOP) {
            DesktopReaderStatus(desktopState, onRetryDesktop)
        }
        if (state.reader == ReaderMode.FIXED) {
            Notice(text = "Chamber connection is coming next. Use the desktop reader for now.")
        }
    }
}

/** What the desktop reader's serial session is doing, and what staff should do about it. */
@Composable
private fun DesktopReaderStatus(desktopState: Dr90xState, onRetry: () -> Unit) {
    when (desktopState) {
        is Dr90xState.Ready -> FormHint(
            "Desktop reader connected. Keep its power low so it reads only the piece on the pad."
        )
        is Dr90xState.Opening -> FormHint("Connecting to the desktop reader…")
        is Dr90xState.Failed -> Notice(
            text = "The desktop reader isn't answering, so it's being used in keyboard mode. " +
                    "Set it to keyboard output, or tap Retry.",
            actionLabel = "Retry",
            onAction = onRetry
        )
        is Dr90xState.Closed -> FormHint(
            "Plug the reader into this device's USB port, set to keyboard mode. " +
                    "Keep its power low so it reads only the piece on the pad."
        )
    }
}

@Composable
private fun ProductSection(state: UnitEnrollmentState, vm: UnitEnrollmentViewModel) {
    var picking by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val product = state.product

    FormSection("Product") {
        if (product != null && !picking) {
            ProductRow(product, state.config.typeDef(product.typeKey()).label, onClick = null)
            TextButton(onClick = { picking = true }) { Text("Change", color = Primary) }
            return@FormSection
        }

        FormTextField(
            value = query,
            onChange = { query = it },
            label = "Search by name or SKU",
            capitalization = KeyboardCapitalization.None
        )

        val q = query.trim().lowercase()
        val matches = state.products
            .filter { q.isEmpty() || it.title.lowercase().contains(q) || it.sku.lowercase().contains(q) }
            .take(MAX_PRODUCT_MATCHES)

        when {
            state.products.isEmpty() -> FormHint("No products yet. Add products first, then enroll their stock.")
            matches.isEmpty() -> FormHint("No product matches \"$query\"")
            else -> Unit
        }
        matches.forEach { p ->
            ProductRow(p, state.config.typeDef(p.typeKey()).label, onClick = {
                vm.selectProduct(p.id)
                picking = false
                query = ""
            })
        }
        if (product != null) {
            TextButton(onClick = { picking = false; query = "" }) { Text("Cancel", color = MediumGray) }
        }
    }
}

@Composable
private fun LocationSection(state: UnitEnrollmentState, vm: UnitEnrollmentViewModel) {
    val labels = state.config.labels
    FormSection("Location") {
        if (state.warehouses.isEmpty()) {
            FormHint(
                "No ${labels.plural(LabelKey.WAREHOUSE).lowercase()} set up yet. " +
                        "Add one in Warehouse Management first."
            )
        }
        LabeledDropdown(
            label = labels.singular(LabelKey.WAREHOUSE),
            options = state.warehouses,
            selected = state.warehouses.firstOrNull { it.id == state.warehouseId },
            optionText = { it.name },
            onSelect = { vm.selectWarehouse(it.id) }
        )
        LabeledDropdown(
            label = labels.singular(LabelKey.RACK),
            options = state.racksInWarehouse,
            selected = state.racksInWarehouse.firstOrNull { it.id == state.rackId },
            optionText = { it.name },
            onSelect = { vm.selectRack(it.id) }
        )
        LabeledDropdown(
            label = labels.singular(LabelKey.BIN),
            options = state.binsInRack,
            selected = state.bin,
            optionText = { b -> if (b.name.isNotBlank() && b.name != b.binCode) "${b.binCode} · ${b.name}" else b.binCode },
            onSelect = { vm.selectBin(it.id) }
        )
    }
}

@Composable
private fun BatchSection(state: UnitEnrollmentState, vm: UnitEnrollmentViewModel) {
    val errors = state.batchErrors
    val locked = state.existingBatch != null
    var hadFocus by remember { mutableStateOf(false) }

    FormSection("Batch") {
        FormTextField(
            value = state.batchNo,
            onChange = vm::onBatchNo,
            label = "Batch number",
            required = state.isRequired(CoreField.BATCH_NO),
            error = errors[CoreField.BATCH_NO.key],
            capitalization = KeyboardCapitalization.Characters,
            modifier = Modifier.onFocusChanged { f ->
                if (hadFocus && !f.isFocused) vm.checkBatch()
                hadFocus = f.isFocused
            }
        )
        DateField(
            label = "Manufacturing date",
            required = state.isRequired(CoreField.MFG_DATE),
            value = state.mfgDate,
            error = errors[CoreField.MFG_DATE.key],
            enabled = !locked,
            onChange = vm::onMfgDate
        )
        DateField(
            label = "Expiry date",
            required = state.isRequired(CoreField.EXPIRY_DATE),
            value = state.expiryDate,
            error = errors[CoreField.EXPIRY_DATE.key],
            enabled = !locked,
            onChange = vm::onExpiryDate
        )
        errors[UnitEnrollmentViewModel.KEY_BATCH_DATES]?.let { FormError(it) }
        when {
            state.checkingBatch -> FormHint("Checking batch…")
            locked -> FormHint("Existing batch. Its dates are fixed.")
            else -> Unit
        }
    }
}

// ── Scanning ──────────────────────────────────────────────────────────────

@Composable
private fun ScanningPane(
    state: UnitEnrollmentState,
    vm: UnitEnrollmentViewModel,
    sledConnected: Boolean,
    onConnectSled: () -> Unit
) {
    FormPane(
        bar = {
            if (state.capture == CaptureMode.BATCH) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryActionButton("Back", onClick = vm::backToSetup, modifier = Modifier.weight(1f))
                    PrimaryActionButton(
                        label = "Continue (${state.pieces.count { it.status == PieceStatus.READY }})",
                        enabled = state.canContinue,
                        onClick = vm::doneScanning,
                        modifier = Modifier.weight(2f)
                    )
                }
            } else {
                SecondaryActionButton("Change product or location", onClick = vm::backToSetup)
            }
        }
    ) {
        SetupSummary(state)
        ReaderPrompt(state, sledConnected, onConnectSled)
        if (state.sessionEnrolled > 0) {
            FormHint("${state.sessionEnrolled} enrolled this session")
        }
        if (state.capture == CaptureMode.BATCH && state.pieces.isNotEmpty()) {
            FormSection("Scanned (${state.pieces.size})") {
                state.pieces.forEach { p ->
                    PieceRow(p, selected = false, onClick = null, onRemove = { vm.removePiece(p.epc) })
                }
            }
        }
    }
}

@Composable
private fun ReaderPrompt(state: UnitEnrollmentState, sledConnected: Boolean, onConnectSled: () -> Unit) {
    val single = state.capture == CaptureMode.SINGLE
    val (title, detail) = when (state.reader) {
        ReaderMode.SLED ->
            if (!sledConnected) "Sled not connected" to "Connect it to start reading tags."
            else if (single) "Read one piece" to "Hold the sled close to a single piece."
            else "Read the pieces" to "Tags appear below as they're read. Tap Continue when done."
        ReaderMode.DESKTOP -> "Place a piece on the reader" to "Its details open as soon as the tag is read."
        ReaderMode.FIXED -> "Chamber not connected yet" to "Use the desktop reader for now."
    }
    val waiting = state.isListening && (state.reader != ReaderMode.SLED || sledConnected) &&
            state.reader != ReaderMode.FIXED

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, BorderGray),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 28.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (waiting) {
                CircularProgressIndicator(color = Primary, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
            }
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = DarkText)
            Text(detail, fontSize = 13.sp, color = MediumGray)
            if (state.reader == ReaderMode.SLED && !sledConnected) {
                PrimaryActionButton("Connect sled", onClick = onConnectSled, modifier = Modifier.widthIn(min = 180.dp))
            }
            if (state.pieces.size >= UnitEnrollmentState.MAX_PIECES) {
                Text("Limit of ${UnitEnrollmentState.MAX_PIECES} tags reached. Continue to save these first.",
                    fontSize = 12.sp, color = ErrorRed)
            }
        }
    }
}

// ── Details ───────────────────────────────────────────────────────────────

@Composable
private fun DetailsPane(state: UnitEnrollmentState, vm: UnitEnrollmentViewModel, wide: Boolean) {
    val batch = state.capture == CaptureMode.BATCH
    if (wide && batch) {
        Row(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(0.4f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                PieceList(state, vm)
            }
            Box(Modifier.weight(0.6f)) {
                DetailsForm(state, vm, showNavigator = false)
            }
        }
    } else {
        DetailsForm(state, vm, showNavigator = batch)
    }
}

@Composable
private fun DetailsForm(state: UnitEnrollmentState, vm: UnitEnrollmentViewModel, showNavigator: Boolean) {
    val batch = state.capture == CaptureMode.BATCH
    val saving = state.phase == EnrollPhase.SAVING
    val piece = state.selectedPiece

    FormPane(
        bar = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (batch) {
                    SecondaryActionButton("Scan more", enabled = !saving, onClick = vm::scanMore, modifier = Modifier.weight(1f))
                }
                PrimaryActionButton(
                    label = if (batch) "Save all (${state.saveablePieces.size})" else "Save piece",
                    enabled = state.canSave,
                    loading = saving,
                    onClick = vm::save,
                    modifier = Modifier.weight(2f)
                )
            }
        }
    ) {
        SetupSummary(state)
        if (showNavigator) PieceNavigator(state, vm)
        if (piece == null) {
            FormHint("Pick a piece to fill in its details.")
        } else {
            PieceForm(state, vm, piece, showApplyAll = batch && state.saveablePieces.size > 1)
        }
        if (state.tracksBatches) BatchSection(state, vm)
    }
}

@Composable
private fun PieceForm(
    state: UnitEnrollmentState,
    vm: UnitEnrollmentViewModel,
    piece: PieceDraft,
    showApplyAll: Boolean
) {
    FormSection("Piece") {
        Column {
            Text("EPC", fontSize = 11.sp, color = MediumGray)
            Text(piece.epc, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = DarkText)
        }
        if (piece.status == PieceStatus.CHECKING) FormHint("Checking the tag…")
        if (piece.note.isNotBlank()) FormHint(piece.note)

        if (state.showSerial) {
            FormTextField(
                value = piece.serialNumber,
                onChange = vm::onSerial,
                label = "Serial number",
                required = state.isRequired(CoreField.SERIAL_NUMBER),
                error = piece.errors[CoreField.SERIAL_NUMBER.key],
                capitalization = KeyboardCapitalization.Characters
            )
        }

        if (state.showPrice) {
            WithApplyAll(showApplyAll, onApply = { vm.applyToAll(CoreField.PRICE.key) }) {
                FormTextField(
                    value = piece.price,
                    onChange = vm::onPrice,
                    label = "Tag price",
                    required = state.isRequired(CoreField.PRICE),
                    error = piece.errors[CoreField.PRICE.key],
                    keyboardType = KeyboardType.Decimal,
                    suffix = "₹"
                )
            }
        }

        state.unitFields.forEach { def ->
            WithApplyAll(showApplyAll, onApply = { vm.applyToAll(def.key) }) {
                CustomFieldInput(
                    def = def,
                    value = piece.customValues[def.key] ?: "",
                    error = piece.errors[def.key],
                    onChange = { vm.onCustomValue(def.key, it) }
                )
            }
        }

        if (!state.showSerial && !state.showPrice && state.unitFields.isEmpty()) {
            FormHint("No per-piece details for this product. Just save.")
        }

        TextButton(onClick = { vm.removePiece(piece.epc) }) {
            Text("Remove this tag", color = ErrorRed)
        }
    }
}

@Composable
private fun PieceNavigator(state: UnitEnrollmentState, vm: UnitEnrollmentViewModel) {
    val list = state.saveablePieces
    if (list.size <= 1) return
    val index = list.indexOfFirst { it.epc == state.selectedEpc }.coerceAtLeast(0)
    val withErrors = list.count { it.errors.isNotEmpty() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, BorderGray),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            TextButton(enabled = index > 0, onClick = { vm.selectPiece(list[index - 1].epc) }) {
                Text("‹ Prev", color = if (index > 0) Primary else MediumGray)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Piece ${index + 1} of ${list.size}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DarkText)
                if (withErrors > 0) Text("$withErrors to fix", fontSize = 11.sp, color = ErrorRed)
            }
            TextButton(enabled = index < list.lastIndex, onClick = { vm.selectPiece(list[index + 1].epc) }) {
                Text("Next ›", color = if (index < list.lastIndex) Primary else MediumGray)
            }
        }
    }
}

@Composable
private fun PieceList(state: UnitEnrollmentState, vm: UnitEnrollmentViewModel) {
    FormSection("Pieces (${state.pieces.size})") {
        state.pieces.forEach { p ->
            PieceRow(
                piece = p,
                selected = p.epc == state.selectedEpc,
                onClick = if (p.isSaveable) ({ vm.selectPiece(p.epc) }) else null,
                onRemove = if (state.phase == EnrollPhase.SAVING) null else ({ vm.removePiece(p.epc) })
            )
        }
    }
}

// ── Result ────────────────────────────────────────────────────────────────

@Composable
private fun ResultPane(state: UnitEnrollmentState, vm: UnitEnrollmentViewModel) {
    val summary = state.lastSummary
    val failed = state.pieces.any { it.status == PieceStatus.FAILED }
    val notSaved = state.pieces.filter { it.status != PieceStatus.SAVED }

    FormPane(
        bar = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (failed) PrimaryActionButton("Fix and retry failed", onClick = vm::retryFailed)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryActionButton("Change product", onClick = vm::changeSetup, modifier = Modifier.weight(1f))
                    PrimaryActionButton("Enroll more", onClick = vm::enrollMore, modifier = Modifier.weight(1f))
                }
            }
        }
    ) {
        SetupSummary(state)
        FormSection("Saved") {
            Text(
                "${summary?.enrolled ?: 0} enrolled",
                fontSize = 22.sp, fontWeight = FontWeight.Bold, color = SuccessGreen
            )
            val skipped = summary?.skipped ?: 0
            if (skipped > 0) Text("$skipped not enrolled", fontSize = 14.sp, color = ErrorRed)
            FormHint("${state.sessionEnrolled} enrolled this session")
        }
        if (notSaved.isNotEmpty()) {
            FormSection("Not enrolled") {
                notSaved.forEach { PieceRow(it, selected = false, onClick = null, onRemove = null) }
            }
        }
    }
}

// ── Shared pieces of this screen ──────────────────────────────────────────

@Composable
private fun SetupSummary(state: UnitEnrollmentState) {
    val product = state.product ?: return
    val labels = state.config.labels
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = InfoBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(product.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = InfoText)
            Text(product.sku, fontSize = 12.sp, color = InfoText)
            state.bin?.let {
                Text("${labels.singular(LabelKey.BIN)}: ${it.binCode}", fontSize = 12.sp, color = InfoText)
            }
            if (state.tracksBatches && state.batchNo.isNotBlank()) {
                Text("Batch: ${state.batchNo.trim()}", fontSize = 12.sp, color = InfoText)
            }
            Text("${state.reader.label} · ${state.capture.label}", fontSize = 11.sp, color = InfoText)
        }
    }
}

@Composable
private fun ProductRow(product: Product, typeLabel: String, onClick: (() -> Unit)?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp)
    ) {
        Text(product.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = DarkText)
        Text("${product.sku} · $typeLabel", fontSize = 12.sp, color = MediumGray)
    }
}

@Composable
private fun PieceRow(
    piece: PieceDraft,
    selected: Boolean,
    onClick: (() -> Unit)?,
    onRemove: (() -> Unit)?
) {
    val (color, label) = when {
        piece.status == PieceStatus.CHECKING -> MediumGray to "Checking…"
        piece.errors.isNotEmpty() -> ErrorRed to "${piece.errors.size} to fix"
        piece.status == PieceStatus.READY -> Primary to "Ready"
        piece.status == PieceStatus.ALREADY_ENROLLED -> WarningOrange to "Already enrolled"
        piece.status == PieceStatus.SAVED -> SuccessGreen to "Enrolled"
        else -> ErrorRed to "Failed"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) InfoBg else Color.Transparent, RoundedCornerShape(8.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Box(
            Modifier
                .size(10.dp)
                .background(color, CircleShape)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("…${piece.epc.takeLast(12)}", fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = DarkText)
            Text(
                if (piece.note.isNotBlank() && piece.status != PieceStatus.READY) "$label · ${piece.note}" else label,
                fontSize = 11.sp,
                color = color
            )
        }
        if (onRemove != null && piece.status != PieceStatus.SAVED) {
            TextButton(onClick = onRemove) { Text("✕", color = MediumGray) }
        }
    }
}

@Composable
private fun ChoiceRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp)
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = Primary)
        )
        Column {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = DarkText)
            Text(subtitle, fontSize = 12.sp, color = MediumGray)
        }
    }
}

@Composable
private fun Notice(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(WarningBg, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text, fontSize = 13.sp, color = WarningText, modifier = Modifier.weight(1f))
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel, color = Primary) }
        }
    }
}

@Composable
private fun WithApplyAll(show: Boolean, onApply: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        content()
        if (show) {
            TextButton(onClick = onApply, modifier = Modifier.align(Alignment.End)) {
                Text("Apply to all", fontSize = 12.sp, color = Primary)
            }
        }
    }
}

private const val MAX_PRODUCT_MATCHES = 20