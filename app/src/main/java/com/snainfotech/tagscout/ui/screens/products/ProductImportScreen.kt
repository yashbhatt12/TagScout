package com.snainfotech.tagscout.ui.screens.products

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snainfotech.tagscout.data.file.ProductExcelImporter
import com.snainfotech.tagscout.ui.components.AppHeader
import com.snainfotech.tagscout.ui.components.FormHint
import com.snainfotech.tagscout.ui.components.FormPane
import com.snainfotech.tagscout.ui.components.FormSection
import com.snainfotech.tagscout.ui.components.PrimaryActionButton
import com.snainfotech.tagscout.ui.components.SecondaryActionButton
import com.snainfotech.tagscout.ui.theme.DarkText
import com.snainfotech.tagscout.ui.theme.ErrorRed
import com.snainfotech.tagscout.ui.theme.InfoBg
import com.snainfotech.tagscout.ui.theme.InfoText
import com.snainfotech.tagscout.ui.theme.LightGray
import com.snainfotech.tagscout.ui.theme.MediumGray
import com.snainfotech.tagscout.ui.theme.Primary
import com.snainfotech.tagscout.ui.theme.SuccessGreen
import com.snainfotech.tagscout.ui.theme.WarningBg
import com.snainfotech.tagscout.ui.theme.WarningText

private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
private const val SAMPLE_FILE_NAME = "TagScout products sample.xlsx"

/** Rows drawn at once; the counts above always cover the whole file. */
private const val MAX_ROWS_SHOWN = 200

/**
 * Import products from Excel: choose a file → preview every row (new /
 * update / error) → import → results. Nothing is written before Import.
 */
@Composable
fun ProductImportScreen(
    viewModel: ProductImportViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearMessage()
        }
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            viewModel.onFilePicked(displayName(context, uri)) { context.contentResolver.openInputStream(uri) }
        }
    }
    val saveSample = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(XLSX_MIME)) { uri: Uri? ->
        if (uri != null) viewModel.writeSample { context.contentResolver.openOutputStream(uri) }
    }

    val saving = state.phase == ImportPhase.SAVING
    BackHandler(enabled = saving) { /* stay until the save finishes */ }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LightGray)
    ) {
        AppHeader(
            title = "Import products",
            showBackButton = true,
            onBackClick = { if (!saving) onBack() },
            showMenu = false
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (state.phase) {
                ImportPhase.CHOOSE -> ChoosePane(
                    onChoose = { pickFile.launch(arrayOf(XLSX_MIME, "application/octet-stream")) },
                    onSample = { saveSample.launch(SAMPLE_FILE_NAME) }
                )
                ImportPhase.READING -> Busy("Reading ${state.fileName.ifBlank { "the file" }}…")
                ImportPhase.PREVIEW -> PreviewPane(state, viewModel)
                ImportPhase.SAVING -> Busy("Saving ${state.progressDone} of ${state.progressTotal}…")
                ImportPhase.DONE -> DonePane(state, viewModel, onBack)
            }
        }
    }
}

// ── Choose ────────────────────────────────────────────────────────────────

@Composable
private fun ChoosePane(onChoose: () -> Unit, onSample: () -> Unit) {
    FormPane(
        bar = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryActionButton("Choose Excel file", onClick = onChoose)
                SecondaryActionButton("Download sample", onClick = onSample)
            }
        }
    ) {
        FormSection("Import from Excel") {
            Text(
                "Add or update many products at once from an .xlsx file.",
                fontSize = 14.sp,
                color = DarkText
            )
            FormHint("• One sheet per product type. Each sheet has only the columns that type uses.")
            FormHint("• Columns marked * in the sample are required.")
            FormHint("• You'll see every row checked before anything is saved.")
            FormHint("• To change existing products, export the catalog, edit it and import it back.")
        }
        FormSection("Not sure of the format?") {
            FormHint("Download the sample. It has an Instructions sheet listing every column, whether it's required and what to enter.")
        }
    }
}

// ── Preview ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewPane(state: ProductImportState, vm: ProductImportViewModel) {
    val preview = state.preview ?: return
    FormPane(
        bar = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryActionButton("Choose another", onClick = vm::reset, modifier = Modifier.weight(1f))
                PrimaryActionButton(
                    label = if (state.toSave == 1) "Import 1 product" else "Import ${state.toSave} products",
                    enabled = state.canImport,
                    onClick = vm::commit,
                    modifier = Modifier.weight(1.4f)
                )
            }
        }
    ) {
        FormSection(state.fileName.ifBlank { "Preview" }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("New", preview.creates, SuccessGreen, Modifier.weight(1f))
                Stat("Updates", preview.updates, Primary, Modifier.weight(1f))
                Stat("Errors", preview.errors, ErrorRed, Modifier.weight(1f))
            }
            if (preview.updates > 0) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Update existing products", fontSize = 14.sp, color = DarkText)
                        FormHint("${preview.updates} SKU${if (preview.updates == 1) "" else "s"} already exist. Blank cells keep their current values.")
                    }
                    Switch(
                        checked = state.updateExisting,
                        onCheckedChange = vm::setUpdateExisting,
                        colors = SwitchDefaults.colors(checkedTrackColor = Primary)
                    )
                }
            }
            if (preview.errors > 0) {
                FormHint("Rows with errors are skipped. Fix them in the file and import it again, or import the rest now.")
            }
        }

        preview.sheetNotes.forEach { Notice(it) }

        if (preview.rows.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ImportFilter.entries.forEach { f ->
                    FilterChip(
                        selected = state.filter == f,
                        onClick = { vm.setFilter(f) },
                        label = { Text(f.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = InfoBg,
                            selectedLabelColor = InfoText
                        )
                    )
                }
            }

            val rows = state.visibleRows
            FormSection("Rows (${rows.size})") {
                if (rows.isEmpty()) FormHint("Nothing in this view.")
                rows.take(MAX_ROWS_SHOWN).forEach { RowItem(it) }
                if (rows.size > MAX_ROWS_SHOWN) {
                    FormHint("Showing the first $MAX_ROWS_SHOWN of ${rows.size}. The counts above cover every row.")
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: Int, color: Color, modifier: Modifier) {
    Column(
        modifier = modifier
            .background(LightGray, RoundedCornerShape(8.dp))
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("$value", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (value > 0) color else MediumGray)
        Text(label, fontSize = 12.sp, color = MediumGray)
    }
}

@Composable
private fun RowItem(row: ProductExcelImporter.ImportRow, failure: String? = null) {
    val (badge, color) = when {
        failure != null -> "Failed" to ErrorRed
        row.action == ProductExcelImporter.Action.CREATE -> "New" to SuccessGreen
        row.action == ProductExcelImporter.Action.UPDATE -> "Update" to Primary
        else -> "Error" to ErrorRed
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOf(row.sku.ifBlank { "(no SKU)" }, row.title).filter { it.isNotBlank() }.joinToString(" · "),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = DarkText,
                modifier = Modifier.weight(1f)
            )
            Text(
                badge,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = color,
                modifier = Modifier
                    .background(color.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
        Text("${row.sheet}, row ${row.rowNumber}", fontSize = 11.sp, color = MediumGray)
        failure?.let { Text(it, fontSize = 12.sp, color = ErrorRed) }
        row.errors.forEach { Text(it, fontSize = 12.sp, color = ErrorRed) }
        row.warnings.forEach { Text(it, fontSize = 12.sp, color = WarningText) }
    }
}

// ── Done ──────────────────────────────────────────────────────────────────

@Composable
private fun DonePane(state: ProductImportState, vm: ProductImportViewModel, onBack: () -> Unit) {
    val outcome = state.outcome ?: return
    FormPane(
        bar = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryActionButton("Import another file", onClick = vm::reset, modifier = Modifier.weight(1f))
                PrimaryActionButton("Done", onClick = onBack, modifier = Modifier.weight(1f))
            }
        }
    ) {
        FormSection("Import finished") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("Created", outcome.created, SuccessGreen, Modifier.weight(1f))
                Stat("Updated", outcome.updated, Primary, Modifier.weight(1f))
                Stat("Failed", outcome.failed.size, ErrorRed, Modifier.weight(1f))
            }
            val skipped = state.preview?.let { it.errors + if (state.updateExisting) 0 else it.updates } ?: 0
            if (skipped > 0) FormHint("$skipped row${if (skipped == 1) " was" else "s were"} not imported (errors, or existing products left unchanged).")
        }
        if (outcome.failed.isNotEmpty()) {
            FormSection("Failed rows") {
                outcome.failed.take(MAX_ROWS_SHOWN).forEach { (row, reason) -> RowItem(row, failure = reason) }
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────

@Composable
private fun Busy(text: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = Primary, modifier = Modifier.size(36.dp))
        Text(text, fontSize = 14.sp, color = MediumGray, modifier = Modifier.padding(top = 12.dp))
    }
}

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

/** The picked file's name, for the preview header. */
private fun displayName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: "Selected file"