package com.snainfotech.tagscout.ui.screens.wms

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.snainfotech.tagscout.data.file.GrnExcelParser
import com.snainfotech.tagscout.data.wms.GrnService
import com.snainfotech.tagscout.data.wms.ReferenceGenerator
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

    init {
        suggestNextGrn()
    }

    /**
     * Ask the reference generator for the next GRN number for the current
     * year and drop it into state as the pre-filled default. The user is
     * free to edit or replace it via updateGrnReference().
     *
     * Failures here are non-fatal: if the suggestion can't be fetched
     * (network, auth still settling), the field simply stays blank and the
     * user can type their own reference. No snackbar noise for this — the
     * placeholder text still shows an example format.
     *
     * Only overwrites the current value if it's blank, so calling this
     * repeatedly (init + after reset) never clobbers something the user is
     * mid-typing.
     */
    private fun suggestNextGrn() {
        viewModelScope.launch {
            ReferenceGenerator.suggestNextGrn().fold(
                onSuccess = { suggestion ->
                    if (_state.value.grnReference.isBlank()) {
                        _state.value = _state.value.copy(grnReference = suggestion)
                    }
                },
                onFailure = { /* silent — leave the field blank */ }
            )
        }
    }

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

            // Advance the reference-number counter on a fully successful commit
            // only. Partial or failed commits leave the counter alone, so a
            // retry can reuse the same number without gaps.
            //
            // Bump is fire-and-forget: any failure inside ReferenceGenerator
            // is swallowed there. Worst case, the next suggestion is off by
            // one and self-corrects on the following bump.
            if (result is GrnService.CommitResult.Success) {
                ReferenceGenerator.bumpGrnCounter(s.grnReference)
            }
        }
    }

    /**
     * Reset back to the initial state — used by "Do another GRN" and by
     * "Reject file, try again" after a validation failure. Also re-runs the
     * reference suggestion so the field is pre-filled for the next GRN.
     */
    fun reset() {
        _state.value = InwardState()
        suggestNextGrn()
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