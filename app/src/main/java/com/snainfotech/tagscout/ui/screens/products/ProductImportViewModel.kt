package com.snainfotech.tagscout.ui.screens.products

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.snainfotech.tagscout.data.config.ConfigRepository
import com.snainfotech.tagscout.data.file.ProductExcelExporter
import com.snainfotech.tagscout.data.file.ProductExcelImporter
import com.snainfotech.tagscout.data.file.ProductExcelSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

enum class ImportPhase { CHOOSE, READING, PREVIEW, SAVING, DONE }

enum class ImportFilter(val label: String) {
    ALL("All"), ERRORS("Errors"), NEW("New"), UPDATES("Updates")
}

data class ProductImportState(
    val phase: ImportPhase = ImportPhase.CHOOSE,
    val fileName: String = "",
    val preview: ProductExcelImporter.Preview? = null,
    val updateExisting: Boolean = false,
    val filter: ImportFilter = ImportFilter.ALL,
    val progressDone: Int = 0,
    val progressTotal: Int = 0,
    val outcome: ProductExcelImporter.Outcome? = null,
    val exporting: Boolean = false,
    val message: String? = null
) {
    /** Rows that Import will save with the current choice. */
    val toSave: Int
        get() = preview?.let { it.creates + if (updateExisting) it.updates else 0 } ?: 0

    val visibleRows: List<ProductExcelImporter.ImportRow>
        get() {
            val rows = preview?.rows.orEmpty()
            return when (filter) {
                ImportFilter.ALL -> rows
                ImportFilter.ERRORS -> rows.filter { it.action == ProductExcelImporter.Action.ERROR }
                ImportFilter.NEW -> rows.filter { it.action == ProductExcelImporter.Action.CREATE }
                ImportFilter.UPDATES -> rows.filter { it.action == ProductExcelImporter.Action.UPDATE }
            }
        }

    val canImport: Boolean get() = phase == ImportPhase.PREVIEW && toSave > 0
}

class ProductImportViewModel(
    private val importer: ProductExcelImporter = ProductExcelImporter()
) : ViewModel() {

    private val _state = MutableStateFlow(ProductImportState())
    val state: StateFlow<ProductImportState> = _state.asStateFlow()

    /** [open] opens the picked file; called off the main thread. */
    fun onFilePicked(fileName: String, open: () -> InputStream?) {
        if (_state.value.phase == ImportPhase.READING || _state.value.phase == ImportPhase.SAVING) return
        _state.update {
            ProductImportState(phase = ImportPhase.READING, fileName = fileName, updateExisting = it.updateExisting)
        }
        viewModelScope.launch {
            val config = ConfigRepository.current
            val result = runCatching {
                withContext(Dispatchers.IO) { open() } ?: error("Could not open the file")
            }.mapCatching { stream ->
                stream.use { importer.prepare(it, config).getOrThrow() }
            }
            result.fold(
                onSuccess = { preview ->
                    _state.update {
                        it.copy(
                            phase = ImportPhase.PREVIEW,
                            preview = preview,
                            filter = if (preview.errors > 0) ImportFilter.ERRORS else ImportFilter.ALL
                        )
                    }
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(phase = ImportPhase.CHOOSE, message = "Couldn't read the file: ${e.message ?: "unknown error"}")
                    }
                }
            )
        }
    }

    fun setUpdateExisting(on: Boolean) = _state.update { it.copy(updateExisting = on) }

    fun setFilter(f: ImportFilter) = _state.update { it.copy(filter = f) }

    fun commit() {
        val st = _state.value
        val preview = st.preview ?: return
        if (!st.canImport) return
        _state.update { it.copy(phase = ImportPhase.SAVING, progressDone = 0, progressTotal = st.toSave) }
        viewModelScope.launch {
            val outcome = importer.commit(preview, st.updateExisting) { done, total ->
                _state.update { it.copy(progressDone = done, progressTotal = total) }
            }
            _state.update { it.copy(phase = ImportPhase.DONE, outcome = outcome) }
        }
    }

    /** Back to choosing a file (keeps the update-existing choice). */
    fun reset() = _state.update { ProductImportState(updateExisting = it.updateExisting, exporting = it.exporting) }

    /** Writes the "Download sample" workbook to [open]'s stream. */
    fun writeSample(open: () -> OutputStream?) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    (open() ?: error("Could not create the file")).use { out ->
                        ProductExcelSchema.writeSample(out, ConfigRepository.current)
                    }
                }
            }
            _state.update {
                it.copy(message = result.fold({ "Sample saved" }, { e -> "Couldn't save the sample: ${e.message}" }))
            }
        }
    }

    /** Writes the whole catalog to [open]'s stream, one sheet per product type. */
    fun exportTo(open: () -> OutputStream?) {
        if (_state.value.exporting) return
        _state.update { it.copy(exporting = true) }
        viewModelScope.launch {
            val result = runCatching {
                val out = withContext(Dispatchers.IO) { open() } ?: error("Could not create the file")
                out.use { ProductExcelExporter().export(it, ConfigRepository.current).getOrThrow() }
            }
            _state.update {
                it.copy(
                    exporting = false,
                    message = result.fold(
                        { s -> "Exported ${s.products} product${if (s.products == 1) "" else "s"}" },
                        { e -> "Export failed: ${e.message}" }
                    )
                )
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}

class ProductImportViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = ProductImportViewModel() as T
}