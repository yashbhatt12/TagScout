package com.snainfotech.tagscout.ui.screens.units

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import com.google.firebase.Timestamp
import com.snainfotech.tagscout.data.config.CoreField
import com.snainfotech.tagscout.data.config.FieldValidator
import com.snainfotech.tagscout.data.config.LabelKey
import com.snainfotech.tagscout.data.config.UnitStatus
import com.snainfotech.tagscout.data.wms.InventoryUnit
import com.snainfotech.tagscout.data.wms.STOCK_STATUSES
import com.snainfotech.tagscout.data.wms.batchStatus
import com.snainfotech.tagscout.ui.components.AppHeader
import com.snainfotech.tagscout.ui.components.CustomFieldInput
import com.snainfotech.tagscout.ui.components.DetailRow
import com.snainfotech.tagscout.ui.components.FormError
import com.snainfotech.tagscout.ui.components.FormHint
import com.snainfotech.tagscout.ui.components.FormPane
import com.snainfotech.tagscout.ui.components.FormSection
import com.snainfotech.tagscout.ui.components.FormTextField
import com.snainfotech.tagscout.data.reader.DesktopReaderUsb
import com.snainfotech.tagscout.sdk.Dr90xState
import com.snainfotech.tagscout.ui.components.KeyboardWedgeBuffer
import com.snainfotech.tagscout.ui.components.LabeledDropdown
import com.snainfotech.tagscout.ui.components.PrimaryActionButton
import com.snainfotech.tagscout.ui.components.SecondaryActionButton
import com.snainfotech.tagscout.ui.components.displayIsoDate
import com.snainfotech.tagscout.ui.screens.enrollment.ReaderMode
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
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Unit details: read a tag (sled or desktop reader) or search by EPC /
 * serial number, see everything about the unit, edit its own details and
 * change its status.
 *
 * Readers: NavGraph runs the sled while state.isListening; the desktop pad
 * is captured here, except while the search box has focus (so typing into
 * it from a hardware keyboard isn't swallowed).
 */
@Composable
fun UnitDetailScreen(
    viewModel: UnitDetailViewModel,
    sledConnected: Boolean,
    onBack: () -> Unit,
    onConnectSled: () -> Unit,
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

    var searchFocused by remember { mutableStateOf(false) }
    val desktopListening = state.reader == ReaderMode.DESKTOP && state.isListening && !searchFocused
    val focusRequester = remember { FocusRequester() }
    val wedge = remember { KeyboardWedgeBuffer() }
    LaunchedEffect(desktopListening) {
        wedge.clear()
        if (desktopListening) runCatching { focusRequester.requestFocus() }
    }

    fun handleBack() {
        when {
            state.editing -> viewModel.cancelEdit()
            state.pendingStatus != null -> viewModel.chooseStatus(null)
            state.phase == UnitLookupPhase.FOUND || state.phase == UnitLookupPhase.PICK -> viewModel.clear()
            else -> onBack()
        }
    }
    val inLookup = state.phase == UnitLookupPhase.LOOKUP || state.phase == UnitLookupPhase.NOT_FOUND
    BackHandler(enabled = !inLookup || state.editing) { handleBack() }

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
            title = "Unit details",
            showBackButton = true,
            onBackClick = { handleBack() },
            showMenu = false
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (state.phase) {
                UnitLookupPhase.FOUND -> FoundPane(state, viewModel)
                else -> LookupPane(
                    state = state,
                    vm = viewModel,
                    sledConnected = sledConnected,
                    onConnectSled = onConnectSled,
                    onSearchFocus = { searchFocused = it },
                    desktopState = desktopState,
                    onRetryDesktop = onRetryDesktop
                )
            }
        }
    }
}

// ── Lookup ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LookupPane(
    state: UnitDetailState,
    vm: UnitDetailViewModel,
    sledConnected: Boolean,
    onConnectSled: () -> Unit,
    onSearchFocus: (Boolean) -> Unit,
    desktopState: Dr90xState,
    onRetryDesktop: () -> Unit
) {
    FormPane {
        FormSection("Read a tag") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ReaderMode.SLED, ReaderMode.DESKTOP).forEach { mode ->
                    FilterChip(
                        selected = state.reader == mode,
                        onClick = { vm.setReader(mode) },
                        label = { Text(mode.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = InfoBg,
                            selectedLabelColor = InfoText
                        )
                    )
                }
            }
            ReaderPrompt(state, sledConnected, onConnectSled, desktopState, onRetryDesktop)
        }

        FormSection("Or search") {
            FormTextField(
                value = state.query,
                onChange = vm::onQuery,
                label = "EPC or serial number",
                capitalization = KeyboardCapitalization.Characters,
                modifier = Modifier.onFocusChanged { onSearchFocus(it.isFocused) }
            )
            PrimaryActionButton(
                label = "Search",
                enabled = state.query.isNotBlank() && state.phase != UnitLookupPhase.LOADING,
                onClick = vm::search
            )
        }

        when (state.phase) {
            UnitLookupPhase.LOADING -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = Primary, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Text("  Looking up…", fontSize = 13.sp, color = MediumGray)
            }

            UnitLookupPhase.NOT_FOUND -> Notice(
                "Nothing found for ${state.lastSearched.takeLast(24)}. " +
                        "The tag may not be enrolled yet, or the serial number may differ."
            )

            UnitLookupPhase.PICK -> FormSection("${state.matches.size} units match \"${state.lastSearched}\"") {
                state.matches.forEach { u -> MatchRow(u, onClick = { vm.pickMatch(u.epc) }) }
            }

            else -> Unit
        }
    }
}

@Composable
private fun ReaderPrompt(
    state: UnitDetailState,
    sledConnected: Boolean,
    onConnectSled: () -> Unit,
    desktopState: Dr90xState,
    onRetryDesktop: () -> Unit
) {
    val waiting = state.isListening && (state.reader != ReaderMode.SLED || sledConnected)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(InfoBg, RoundedCornerShape(10.dp))
            .padding(vertical = 20.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (state.reader == ReaderMode.SLED && !sledConnected) {
            Text("Sled not connected", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = InfoText)
            PrimaryActionButton("Connect sled", onClick = onConnectSled, modifier = Modifier.widthIn(min = 180.dp))
        } else {
            if (waiting) {
                CircularProgressIndicator(color = InfoText, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            }
            Text(
                if (state.reader == ReaderMode.DESKTOP) "Place the piece on the reader" else "Hold the sled close to one piece",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = InfoText
            )
            if (state.reader == ReaderMode.DESKTOP) {
                DesktopReaderLine(desktopState, onRetryDesktop)
            }
        }
    }
}

/** One line under the desktop prompt: is the reader talking to us, or typing (keyboard mode)? */
@Composable
private fun DesktopReaderLine(desktopState: Dr90xState, onRetry: () -> Unit) {
    when (desktopState) {
        is Dr90xState.Ready -> Text("Desktop reader connected", fontSize = 12.sp, color = InfoText)
        is Dr90xState.Opening -> Text("Connecting to the desktop reader…", fontSize = 12.sp, color = InfoText)
        is Dr90xState.Failed -> {
            Text("Reader not answering; using keyboard mode", fontSize = 12.sp, color = WarningText)
            TextButton(onClick = onRetry) { Text("Retry", color = Primary) }
        }
        is Dr90xState.Closed -> {
            // Plugged in but no session: usually USB access hasn't been allowed.
            val usb by DesktopReaderUsb.status.collectAsState()
            when (usb.state) {
                DesktopReaderUsb.State.PERMISSION_DENIED -> {
                    Text("Reader not allowed; using keyboard mode", fontSize = 12.sp, color = WarningText)
                    TextButton(onClick = DesktopReaderUsb::requestPermissionAgain) { Text("Allow", color = Primary) }
                }
                DesktopReaderUsb.State.NEEDS_PERMISSION ->
                    Text("Allow TagScout to use the reader when Android asks", fontSize = 12.sp, color = InfoText)
                else ->
                    Text("Keyboard mode: set the reader to keyboard output", fontSize = 12.sp, color = InfoText)
            }
        }
    }
}

@Composable
private fun MatchRow(unit: InventoryUnit, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
    ) {
        Text(unit.sku.ifBlank { "No product" }, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = DarkText)
        Text(
            "…${unit.epc.takeLast(12)} · ${UnitStatus.fromKey(unit.status)?.displayName ?: unit.status} · ${unit.currentBinCode.ifBlank { "—" }}",
            fontSize = 12.sp,
            color = MediumGray,
            fontFamily = FontFamily.Monospace
        )
    }
}

// ── Found ─────────────────────────────────────────────────────────────────

@Composable
private fun FoundPane(state: UnitDetailState, vm: UnitDetailViewModel) {
    val unit = state.unit ?: return
    FormPane(
        bar = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.editing) {
                    SecondaryActionButton("Cancel", onClick = vm::cancelEdit, modifier = Modifier.weight(1f))
                    PrimaryActionButton(
                        "Save details",
                        onClick = vm::saveDetails,
                        loading = state.savingDetails,
                        modifier = Modifier.weight(2f)
                    )
                } else {
                    SecondaryActionButton("Look up another", onClick = vm::clear, modifier = Modifier.weight(1f))
                    PrimaryActionButton(
                        "Edit details",
                        onClick = vm::startEdit,
                        enabled = state.canEdit && state.pendingStatus == null,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    ) {
        SummaryCard(state, unit)
        if (!state.editing) StatusSection(state, vm)
        if (state.editing) EditSection(state, vm) else DetailsSection(state, unit)
        LocationSection(state, unit)
    }
}

@Composable
private fun SummaryCard(state: UnitDetailState, unit: InventoryUnit) {
    val title = state.product?.title?.takeIf { it.isNotBlank() }
        ?: unit.itemCode?.takeIf { it.isNotBlank() }?.let { "Item $it" }
        ?: "Unit"
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderGray),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DarkText,
                    modifier = Modifier.weight(1f)
                )
                state.status?.let { StatusChip(it) }
            }
            val sku = state.product?.sku ?: unit.sku
            Text(
                listOf(sku, state.typeLabel).filter { it.isNotBlank() }.joinToString(" · "),
                fontSize = 13.sp,
                color = MediumGray
            )
            if (state.product == null && unit.productId.isBlank()) {
                FormHint("Enrolled before products existed. Not linked to a product.")
            } else if (state.product == null) {
                FormHint("Its product could not be found. It may have been deleted.")
            }
        }
    }
}

@Composable
private fun StatusChip(status: UnitStatus) {
    val (bg, fg) = when (status) {
        UnitStatus.IN_STOCK -> Color(0xFFEAF6EF) to SuccessGreen
        UnitStatus.SOLD, UnitStatus.DISPATCHED -> InfoBg to InfoText
        UnitStatus.LOST, UnitStatus.DISPOSED, UnitStatus.EXPIRED -> Color(0xFFFDF3F3) to ErrorRed
        else -> WarningBg to WarningOrange
    }
    Text(
        status.displayName,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = fg,
        modifier = Modifier
            .background(bg, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
private fun StatusSection(state: UnitDetailState, vm: UnitDetailViewModel) {
    val choices = state.statusChoices
    val current = state.status ?: return
    if (choices.isEmpty()) {
        if (current == UnitStatus.RETAGGED) {
            FormSection("Status") {
                FormHint("This tag was replaced${state.unit?.replacedBy?.takeIf { it.isNotBlank() }?.let { " by …${it.takeLast(12)}" } ?: ""}. Its details are read-only.")
            }
        }
        return
    }

    FormSection("Status") {
        LabeledDropdown(
            label = "Change status to",
            options = choices,
            selected = state.pendingStatus,
            optionText = { it.displayName },
            onSelect = { vm.chooseStatus(it) }
        )
        val target = state.pendingStatus
        if (target != null) {
            val wasCounted = current in STOCK_STATUSES
            val isCounted = target in STOCK_STATUSES
            when {
                wasCounted && !isCounted -> Notice("This takes the unit out of stock counts.")
                !wasCounted && isCounted -> Notice("This puts the unit back into stock counts.")
                else -> Unit
            }
            if (current == UnitStatus.SOLD) Notice("This reverses the sale. The sold date will be cleared.")
            FormTextField(
                value = state.statusNote,
                onChange = vm::onStatusNote,
                label = "Note (optional)",
                singleLine = false,
                minLines = 2
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryActionButton("Cancel", onClick = { vm.chooseStatus(null) }, modifier = Modifier.weight(1f))
                PrimaryActionButton(
                    "Change to ${target.displayName}",
                    onClick = vm::confirmStatusChange,
                    loading = state.savingStatus,
                    modifier = Modifier.weight(2f)
                )
            }
        }
    }
}

@Composable
private fun DetailsSection(state: UnitDetailState, unit: InventoryUnit) {
    FormSection("Details") {
        DetailRow("EPC", unit.epc, mono = true)
        if (unit.tid.isNotBlank()) DetailRow("TID", unit.tid, mono = true)
        if (state.showSerial) DetailRow("Serial number", unit.serialNumber)
        if (state.showPrice) DetailRow("Tag price", unit.price?.let { formatRupees(it) } ?: "")
        unit.itemCode?.takeIf { it.isNotBlank() }?.let { DetailRow("Item code", it) }

        state.unitFields.forEach { def ->
            DetailRow(def.label, FieldValidator.display(def, unit.customFields[def.key]))
        }
        if (state.unitFields.isEmpty() && !state.showSerial && !state.showPrice) {
            FormHint("No per-piece details are defined for this product type.")
        }
    }

    val others = state.otherValues
    if (others.isNotEmpty()) {
        FormSection("Other details") {
            FormHint("Stored on this unit from fields that are no longer in use.")
            others.forEach { (k, v) -> DetailRow(k, v) }
        }
    }
}

@Composable
private fun EditSection(state: UnitDetailState, vm: UnitDetailViewModel) {
    FormSection("Edit details") {
        if (state.showSerial) {
            FormTextField(
                value = state.serialNumber,
                onChange = vm::onSerial,
                label = "Serial number",
                required = state.isRequired(CoreField.SERIAL_NUMBER),
                error = state.errors[CoreField.SERIAL_NUMBER.key],
                capitalization = KeyboardCapitalization.Characters
            )
        }
        if (state.showPrice) {
            FormTextField(
                value = state.price,
                onChange = vm::onPrice,
                label = "Tag price",
                required = state.isRequired(CoreField.PRICE),
                error = state.errors[CoreField.PRICE.key],
                keyboardType = KeyboardType.Decimal,
                suffix = "₹"
            )
        }
        state.unitFields.forEach { def ->
            CustomFieldInput(
                def = def,
                value = state.customValues[def.key] ?: "",
                error = state.errors[def.key],
                onChange = { vm.onCustomValue(def.key, it) }
            )
        }
        if (!state.showSerial && !state.showPrice && state.unitFields.isEmpty()) {
            FormHint("Nothing to edit for this product type.")
        }
        state.errors.filterKeys { k ->
            k != CoreField.SERIAL_NUMBER.key && k != CoreField.PRICE.key && state.unitFields.none { it.key == k }
        }.values.forEach { FormError(it) }
    }
}

@Composable
private fun LocationSection(state: UnitDetailState, unit: InventoryUnit) {
    val labels = state.config.labels
    FormSection("Location and history") {
        DetailRow(labels.singular(LabelKey.BIN), unit.currentBinCode)

        val batch = state.batch
        if (unit.batchNo.isNotBlank() || batch != null) {
            DetailRow("Batch", unit.batchNo.ifBlank { batch?.batchNo.orEmpty() })
            val expiry = unit.expiryDate.ifBlank { batch?.expiryDate.orEmpty() }
            if (expiry.isNotBlank()) DetailRow("Expiry", displayIsoDate(expiry))
            batch?.let { DetailRow("Batch status", it.batchStatus().displayName) }
        }

        DetailRow("Enrolled", formatTimestamp(unit.enrolledAt ?: unit.inwardedAt))
        DetailRow("Last moved", formatTimestamp(unit.lastMovedAt))
        unit.soldAt?.let { DetailRow("Sold", formatTimestamp(it)) }
        unit.dispatchedAt?.let { DetailRow("Dispatched", formatTimestamp(it)) }
        unit.lastSeenAt?.let { DetailRow("Last counted", formatTimestamp(it)) }
        if (unit.previousEpcs.isNotEmpty()) {
            DetailRow("Previous tags", unit.previousEpcs.joinToString(", ") { "…${it.takeLast(8)}" }, mono = true)
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────

@Composable
private fun Notice(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        color = WarningText,
        modifier = Modifier
            .fillMaxWidth()
            .background(WarningBg, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

private fun formatTimestamp(ts: Timestamp?): String =
    ts?.toDate()?.let { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.ENGLISH).format(it) } ?: ""

/** ₹ with Indian digit grouping: 1234567 → ₹12,34,567. */
private fun formatRupees(value: Double): String {
    val whole = value % 1.0 == 0.0
    val number = if (whole) value.toLong().toString() else String.format(Locale.ENGLISH, "%.2f", value)
    val intPart = number.substringBefore('.')
    val decPart = number.substringAfter('.', "")
    val grouped = if (intPart.length <= 3) intPart else {
        val last3 = intPart.takeLast(3)
        val rest = intPart.dropLast(3).reversed().chunked(2).joinToString(",").reversed()
        "$rest,$last3"
    }
    return "₹" + grouped + if (decPart.isNotEmpty()) ".$decPart" else ""
}