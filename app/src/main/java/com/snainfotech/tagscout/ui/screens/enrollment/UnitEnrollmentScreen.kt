package com.snainfotech.tagscout.ui.screens.enrollment

import android.os.SystemClock
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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snainfotech.tagscout.data.config.CoreField
import com.snainfotech.tagscout.data.config.FieldDef
import com.snainfotech.tagscout.data.config.FieldType
import com.snainfotech.tagscout.data.config.LabelKey
import com.snainfotech.tagscout.data.wms.Product
import com.snainfotech.tagscout.data.wms.type as typeKey
import com.snainfotech.tagscout.ui.components.AppHeader
import com.snainfotech.tagscout.ui.components.LabeledDropdown
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Scan-based enrollment for every product type.
 *
 * Readers:
 *   - Sled: NavGraph runs the scanner while state.isListening and calls vm.onTagScanned.
 *   - Desktop pad (USB, keyboard mode): this screen captures the typed EPC
 *     while listening, so staff never need to tap a text box first.
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
    onConnectSled: () -> Unit
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

    val handleBack: () -> Unit = {
        when (state.phase) {
            EnrollPhase.SETUP -> onBack()
            EnrollPhase.SCANNING -> viewModel.backToSetup()
            EnrollPhase.DETAILS ->
                if (state.capture == CaptureMode.SINGLE) state.selectedEpc?.let(viewModel::removePiece)
                else viewModel.scanMore()
            EnrollPhase.SAVING -> Unit
            EnrollPhase.RESULT -> viewModel.changeSetup()
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
        AppHeader(
            title = "Enroll stock",
            showBackButton = true,
            onBackClick = handleBack,
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
                EnrollPhase.SETUP -> SetupPane(state, viewModel, sledConnected, onConnectSled)
                EnrollPhase.SCANNING -> ScanningPane(state, viewModel, sledConnected, onConnectSled)
                EnrollPhase.DETAILS, EnrollPhase.SAVING -> DetailsPane(state, viewModel, wide)
                EnrollPhase.RESULT -> ResultPane(state, viewModel)
            }
        }
    }
}

// ── Setup ─────────────────────────────────────────────────────────────────

@Composable
private fun SetupPane(
    state: UnitEnrollmentState,
    vm: UnitEnrollmentViewModel,
    sledConnected: Boolean,
    onConnectSled: () -> Unit
) {
    PaneWithBar(
        bar = {
            PrimaryButton(
                label = "Start scanning",
                enabled = state.setupComplete && state.reader != ReaderMode.FIXED,
                onClick = vm::startScanning
            )
        }
    ) {
        ReaderSection(state, vm, sledConnected, onConnectSled)
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
    onConnectSled: () -> Unit
) {
    SectionCard("Reader") {
        ReaderMode.entries.forEach { mode ->
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
            HintText(
                "Plug the reader into this device's USB port, set to keyboard mode. " +
                        "Keep its power low so it reads only the piece on the pad."
            )
        }
        if (state.reader == ReaderMode.FIXED) {
            Notice(text = "Chamber connection is coming next. Use the sled or desktop reader for now.")
        }
    }
}

@Composable
private fun ProductSection(state: UnitEnrollmentState, vm: UnitEnrollmentViewModel) {
    var picking by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val product = state.product

    SectionCard(state.config.labels.singular(LabelKey.PRODUCT)) {
        if (product != null && !picking) {
            ProductRow(product, state.config.typeDef(product.typeKey()).label, onClick = null)
            TextButton(onClick = { picking = true }) { Text("Change", color = Primary) }
            return@SectionCard
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
            state.products.isEmpty() -> HintText("No products yet. Add products first, then enroll their stock.")
            matches.isEmpty() -> HintText("No product matches \"$query\"")
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
    SectionCard("Location") {
        if (state.warehouses.isEmpty()) {
            HintText(
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

    SectionCard("Batch") {
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
        DateInput(
            label = "Manufacturing date",
            required = state.isRequired(CoreField.MFG_DATE),
            value = state.mfgDate,
            error = errors[CoreField.MFG_DATE.key],
            enabled = !locked,
            onChange = vm::onMfgDate
        )
        DateInput(
            label = "Expiry date",
            required = state.isRequired(CoreField.EXPIRY_DATE),
            value = state.expiryDate,
            error = errors[CoreField.EXPIRY_DATE.key],
            enabled = !locked,
            onChange = vm::onExpiryDate
        )
        errors[UnitEnrollmentViewModel.KEY_BATCH_DATES]?.let { ErrorText(it) }
        when {
            state.checkingBatch -> HintText("Checking batch…")
            locked -> HintText("Existing batch. Its dates are fixed.")
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
    PaneWithBar(
        bar = {
            if (state.capture == CaptureMode.BATCH) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Back", onClick = vm::backToSetup, modifier = Modifier.weight(1f))
                    PrimaryButton(
                        label = "Continue (${state.pieces.count { it.status == PieceStatus.READY }})",
                        enabled = state.canContinue,
                        onClick = vm::doneScanning,
                        modifier = Modifier.weight(2f)
                    )
                }
            } else {
                SecondaryButton("Change product or location", onClick = vm::backToSetup)
            }
        }
    ) {
        SetupSummary(state)
        ReaderPrompt(state, sledConnected, onConnectSled)
        if (state.sessionEnrolled > 0) {
            HintText("${state.sessionEnrolled} enrolled this session")
        }
        if (state.capture == CaptureMode.BATCH && state.pieces.isNotEmpty()) {
            SectionCard("Scanned (${state.pieces.size})") {
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
        ReaderMode.FIXED -> "Chamber not connected yet" to "Use the sled or desktop reader for now."
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
                PrimaryButton("Connect sled", onClick = onConnectSled, modifier = Modifier.widthIn(min = 180.dp))
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

    PaneWithBar(
        bar = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (batch) {
                    SecondaryButton("Scan more", enabled = !saving, onClick = vm::scanMore, modifier = Modifier.weight(1f))
                }
                PrimaryButton(
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
            HintText("Pick a piece to fill in its details.")
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
    SectionCard("Piece") {
        Column {
            Text("EPC", fontSize = 11.sp, color = MediumGray)
            Text(piece.epc, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = DarkText)
        }
        if (piece.status == PieceStatus.CHECKING) HintText("Checking the tag…")
        if (piece.note.isNotBlank()) HintText(piece.note)

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
            HintText("No per-piece details for this product. Just save.")
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
    SectionCard("Pieces (${state.pieces.size})") {
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

    PaneWithBar(
        bar = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (failed) PrimaryButton("Fix and retry failed", onClick = vm::retryFailed)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Change product", onClick = vm::changeSetup, modifier = Modifier.weight(1f))
                    PrimaryButton("Enroll more", onClick = vm::enrollMore, modifier = Modifier.weight(1f))
                }
            }
        }
    ) {
        SetupSummary(state)
        SectionCard("Saved") {
            Text(
                "${summary?.enrolled ?: 0} enrolled",
                fontSize = 22.sp, fontWeight = FontWeight.Bold, color = SuccessGreen
            )
            val skipped = summary?.skipped ?: 0
            if (skipped > 0) Text("$skipped not enrolled", fontSize = 14.sp, color = ErrorRed)
            HintText("${state.sessionEnrolled} enrolled this session")
        }
        if (notSaved.isNotEmpty()) {
            SectionCard("Not enrolled") {
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

// ── Custom field inputs ───────────────────────────────────────────────────
// Same behaviour as the product form. To be moved into a shared file in the clean-up phase.

@Composable
private fun CustomFieldInput(def: FieldDef, value: String, error: String?, onChange: (String) -> Unit) {
    when (def.type) {
        FieldType.TEXT -> FormTextField(
            value = value, onChange = onChange, label = def.label,
            required = def.required, error = error
        )

        FieldType.NUMBER -> FormTextField(
            value = value, onChange = onChange, label = def.label,
            required = def.required, error = error,
            keyboardType = KeyboardType.Decimal,
            suffix = def.unit.ifEmpty { null }
        )

        FieldType.DROPDOWN -> {
            val legacy = value.takeIf { it.isNotEmpty() && def.options.none { o -> o == it } }
            val options = buildList {
                if (!def.required) add("")
                addAll(def.options)
                legacy?.let { add(it) }
            }
            Column {
                LabeledDropdown(
                    label = if (def.required) "${def.label} *" else def.label,
                    options = options,
                    selected = value.takeIf { it.isNotEmpty() },
                    optionText = { if (it.isEmpty()) "—" else it },
                    onSelect = onChange
                )
                error?.let { ErrorText(it) }
            }
        }

        FieldType.BOOLEAN -> Column {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (def.required) "${def.label} *" else def.label,
                    fontSize = 14.sp,
                    color = DarkText,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = value == "true",
                    onCheckedChange = { onChange(it.toString()) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Primary)
                )
            }
            error?.let { ErrorText(it) }
        }

        FieldType.DATE -> DateInput(
            label = def.label,
            required = def.required,
            value = value,
            error = error,
            onChange = onChange
        )
    }
}

/** Read-only box showing "31 Mar 2027"; tapping opens a date picker. Value is "YYYY-MM-DD". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateInput(
    label: String,
    required: Boolean,
    value: String,
    error: String?,
    enabled: Boolean = true,
    onChange: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.weight(1f)) {
            FormTextField(
                value = displayDate(value),
                onChange = {},
                label = label,
                required = required,
                error = error,
                readOnly = true,
                enabled = enabled,
                supporting = if (value.isBlank() && enabled) "Tap to pick a date" else null
            )
            if (enabled) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable { open = true }
                )
            }
        }
        if (enabled && value.isNotBlank() && !required) {
            TextButton(onClick = { onChange("") }) { Text("Clear", color = Primary) }
        }
    }

    if (open) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = isoToUtcMillis(value))
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { onChange(utcMillisToIso(it)) }
                    open = false
                }) { Text("OK", color = Primary) }
            },
            dismissButton = {
                TextButton(onClick = { open = false }) { Text("Cancel", color = MediumGray) }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }
}

private val DISPLAY_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)

private fun displayDate(iso: String): String =
    if (iso.isBlank()) "" else runCatching { LocalDate.parse(iso).format(DISPLAY_DATE) }.getOrDefault(iso)

/** The date picker works in UTC midnight millis; stored dates are plain "YYYY-MM-DD". */
private fun isoToUtcMillis(iso: String): Long? = runCatching {
    LocalDate.parse(iso).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}.getOrNull()

private fun utcMillisToIso(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()

// ── Building blocks ───────────────────────────────────────────────────────

/** Scrolling content capped at 640dp (centred on tablets) above a fixed action bar. */
@Composable
private fun PaneWithBar(bar: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = CONTENT_MAX_WIDTH)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardBg)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .widthIn(max = CONTENT_MAX_WIDTH)
                    .fillMaxWidth()
            ) { bar() }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, BorderGray),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title.uppercase(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MediumGray
            )
            content()
        }
    }
}

@Composable
private fun FormTextField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    required: Boolean = false,
    error: String? = null,
    supporting: String? = null,
    readOnly: Boolean = false,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    suffix: String? = null,
    modifier: Modifier = Modifier
) {
    val helper = error ?: supporting
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(if (required) "$label *" else label) },
        isError = error != null,
        supportingText = helper?.let { { Text(it) } },
        suffix = suffix?.let { { Text(it, color = MediumGray) } },
        singleLine = true,
        readOnly = readOnly,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, capitalization = capitalization),
        shape = RoundedCornerShape(8.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Primary,
            focusedLabelColor = Primary,
            cursorColor = Primary
        ),
        modifier = Modifier
            .fillMaxWidth()
            .then(modifier)
    )
}

@Composable
private fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true,
    loading: Boolean = false
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Primary),
        modifier = modifier.height(48.dp)
    ) {
        if (loading) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        } else {
            Text(label, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (enabled) Primary else BorderGray),
        modifier = modifier.height(48.dp)
    ) {
        Text(label, color = if (enabled) Primary else MediumGray, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HintText(text: String) {
    Text(text = text, fontSize = 12.sp, color = MediumGray)
}

@Composable
private fun ErrorText(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = ErrorRed,
        modifier = Modifier.padding(start = 16.dp, top = 2.dp)
    )
}

// ── Desktop reader (keyboard mode) ────────────────────────────────────────

/**
 * Collects characters typed by a USB reader in keyboard mode. The reader
 * "types" the EPC and ends with Enter (or Tab on some models). A pause
 * longer than [GAP_MS] starts a fresh code, so a half-typed read or a
 * stray key press is dropped. The ViewModel still checks the EPC format.
 */
private class KeyboardWedgeBuffer {
    private val buffer = StringBuilder()
    private var lastKeyAt = 0L

    fun clear() {
        buffer.setLength(0)
    }

    /** Returns a complete code when Enter/Tab arrives, else null. */
    fun onKey(event: KeyEvent): String? {
        if (event.type != KeyEventType.KeyDown) return null
        val now = SystemClock.uptimeMillis()
        if (now - lastKeyAt > GAP_MS) buffer.setLength(0)
        lastKeyAt = now

        if (event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.Tab) {
            val code = buffer.toString()
            buffer.setLength(0)
            return code.ifEmpty { null }
        }
        val ch = event.utf16CodePoint.toChar()
        if (ch.isLetterOrDigit()) buffer.append(ch)
        if (buffer.length > MAX_CODE_LENGTH) buffer.setLength(0)
        return null
    }

    private companion object {
        const val GAP_MS = 1000L
        const val MAX_CODE_LENGTH = 256
    }
}

private val CONTENT_MAX_WIDTH = 640.dp
private const val MAX_PRODUCT_MATCHES = 20