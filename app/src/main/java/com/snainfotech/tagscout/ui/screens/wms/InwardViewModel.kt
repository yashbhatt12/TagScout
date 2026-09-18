package com.snainfotech.tagscout.ui.screens.wms

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.snainfotech.tagscout.data.file.GrnExcelParser
import com.snainfotech.tagscout.data.wms.GrnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Which step of the Inward workflow the user is currently on.
 */
enum class InwardStep {
    ENTER_GRN,          // enter GRN reference + pick file
    VALIDATING,         // parsing + validating file (spinner)
    REVIEW_VALIDATION,  // show per-row results — either all clean (Confirm button)
    // or errors (rejected, back button only)
    COMMITTING,         // writing to Firestore (spinner)
    DONE                // success or partial failure summary
}

data class InwardState(
    val step: InwardStep = InwardStep.ENTER_GRN,
    val grnReference: String = "",
    val fileName: String = "",
    val message: String? = null,        // for transient errors / info

    // Validation phase
    val validatedRows: List<GrnService.ValidatedRow> = emptyList(),
    val validationHasErrors: Boolean = false,
    val errorRowCount: Int = 0,
    val totalRowCount: Int = 0,

    // Commit phase
    val commitSuccess: Boolean = false,
    val inwardedCount: Int = 0,
    val commitFailedAtRow: Int? = null,
    val commitError: String? = null
)

class InwardViewModel(
    private val service: GrnService = GrnService()
) : ViewModel() {

    private val _state = MutableStateFlow(InwardState())
    val state: StateFlow<InwardState> = _state.asStateFlow()

    fun updateGrnReference(value: String) {
        _state.value = _state.value.copy(grnReference = value)
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    /**
     * User picked a file — parse it, then run validation.
     * Both parsing and validation run off the main thread.
     */
    fun onFilePicked(context: Context, uri: Uri, fileName: String) {
        if (_state.value.grnReference.isBlank()) {
            _state.value = _state.value.copy(message = "Enter a GRN reference number first")
            return
        }

        _state.value = _state.value.copy(
            step = InwardStep.VALIDATING,
            fileName = fileName,
            message = null
        )

        viewModelScope.launch {
            try {
                val parseResult = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri).use { stream ->
                        if (stream == null) {
                            GrnExcelParser.ParseResult.Error("Could not read the file")
                        } else {
                            GrnExcelParser.parse(stream)
                        }
                    }
                }

                when (parseResult) {
                    is GrnExcelParser.ParseResult.Error -> {
                        _state.value = _state.value.copy(
                            step = InwardStep.ENTER_GRN,
                            message = parseResult.message,
                            fileName = ""
                        )
                    }
                    is GrnExcelParser.ParseResult.Success -> {
                        // Parsing succeeded — now validate against the database
                        val validation = service.validate(parseResult.rows)
                        when (validation) {
                            is GrnService.ValidationResult.AllClean -> {
                                _state.value = _state.value.copy(
                                    step = InwardStep.REVIEW_VALIDATION,
                                    validatedRows = validation.rows,
                                    validationHasErrors = false,
                                    errorRowCount = 0,
                                    totalRowCount = validation.rows.size
                                )
                            }
                            is GrnService.ValidationResult.HasErrors -> {
                                _state.value = _state.value.copy(
                                    step = InwardStep.REVIEW_VALIDATION,
                                    validatedRows = validation.rows,
                                    validationHasErrors = true,
                                    errorRowCount = validation.errorRowCount,
                                    totalRowCount = validation.totalRowCount
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    step = InwardStep.ENTER_GRN,
                    message = "Failed to process the file: ${e.message}",
                    fileName = ""
                )
            }
        }
    }

    /**
     * User confirmed the validated file — commit all rows to Firestore.
     * Only callable when validation reported AllClean.
     */
    fun confirmInward() {
        val s = _state.value
        if (s.validationHasErrors || s.validatedRows.isEmpty()) {
            _state.value = s.copy(message = "Cannot commit — file has errors or is empty")
            return
        }

        _state.value = s.copy(step = InwardStep.COMMITTING, message = null)

        viewModelScope.launch {
            val result = service.commit(s.validatedRows, s.grnReference)
            _state.value = when (result) {
                is GrnService.CommitResult.Success -> _state.value.copy(
                    step = InwardStep.DONE,
                    commitSuccess = true,
                    inwardedCount = result.inwardedCount
                )
                is GrnService.CommitResult.PartialFailure -> _state.value.copy(
                    step = InwardStep.DONE,
                    commitSuccess = false,
                    inwardedCount = result.inwardedCount,
                    commitFailedAtRow = result.failedRowNumber,
                    commitError = result.error
                )
                is GrnService.CommitResult.Failure -> _state.value.copy(
                    step = InwardStep.DONE,
                    commitSuccess = false,
                    commitError = result.error
                )
            }
        }
    }

    /**
     * Reset back to the initial state — used by "Do another GRN" and by
     * "Reject file, try again" after a validation failure.
     */
    fun reset() {
        _state.value = InwardState()
    }
}

class InwardViewModelFactory(
    private val service: GrnService = GrnService()
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return InwardViewModel(service) as T
    }
}