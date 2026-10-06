package com.snainfotech.tagscout.ui.screens.jewellery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.snainfotech.tagscout.data.wms.InventoryUnit
import com.snainfotech.tagscout.data.wms.Movement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * State for the Jewellery Enrollment screen.
 *
 * scannedEpc  — populated when the user triggers a scan; empty before any scan.
 * itemCode    — free-text input, customer's own item code (e.g. "DR-2451").
 * price       — free-text input, parsed to Double on save.
 * isSaving    — true while the Firestore batch write is in flight.
 * errorMessage — one-shot error for display (cleared after dismiss).
 * successMessage — one-shot success for display (cleared after dismiss or next scan).
 */
data class JewelleryEnrollmentState(
    val scannedEpc: String = "",
    val itemCode: String = "",
    val price: String = "",
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

/**
 * Jewellery enrollment ViewModel.
 *
 * Flow:
 *   1. User triggers a scan — scannedEpc populated by the screen's RFID callback.
 *   2. User enters itemCode and price.
 *   3. User taps Save — validates, checks EPC uniqueness, writes InventoryUnit + Movement in one batch.
 *   4. On success, form resets so the next piece can be scanned immediately.
 *
 * All writes land under /companies/{companyId}/... where companyId = userId
 * (single-tenant until Feature 1 admin console lands).
 */
class JewelleryEnrollmentViewModel : ViewModel() {

    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()

    private val _state = MutableStateFlow(JewelleryEnrollmentState())
    val state: StateFlow<JewelleryEnrollmentState> = _state.asStateFlow()

    /** Called by the screen when a tag has been scanned by the handheld sled. */
    fun onEpcScanned(epc: String) {
        _state.update {
            it.copy(
                scannedEpc = epc.uppercase().trim(),
                errorMessage = null,
                successMessage = null
            )
        }
    }

    /** User edited the item code field. */
    fun onItemCodeChanged(itemCode: String) {
        _state.update { it.copy(itemCode = itemCode, errorMessage = null) }
    }

    /** User edited the price field. */
    fun onPriceChanged(price: String) {
        // Only accept digits and single decimal point — defensive input handling.
        val sanitized = price.filterIndexed { index, ch ->
            ch.isDigit() || (ch == '.' && !price.substring(0, index).contains('.'))
        }
        _state.update { it.copy(price = sanitized, errorMessage = null) }
    }

    /** Clears the form after a successful save or if user wants to start over. */
    fun clearForm() {
        _state.update {
            JewelleryEnrollmentState()
        }
    }

    /** Dismisses the current error or success message without clearing form. */
    fun dismissMessage() {
        _state.update { it.copy(errorMessage = null, successMessage = null) }
    }

    /**
     * Validates input, checks EPC uniqueness, writes InventoryUnit + Movement
     * in a single Firestore batch. On success, resets the form for the next piece.
     */
    fun savePiece() {
        val current = _state.value

        // ── Validation ────────────────────────────────────────────────────
        if (current.scannedEpc.isBlank()) {
            _state.update { it.copy(errorMessage = "Scan a tag first.") }
            return
        }
        if (current.itemCode.isBlank()) {
            _state.update { it.copy(errorMessage = "Item code is required.") }
            return
        }
        val priceValue = current.price.toDoubleOrNull()
        if (priceValue == null || priceValue <= 0.0) {
            _state.update { it.copy(errorMessage = "Enter a valid price.") }
            return
        }

        val userId = auth.currentUser?.uid
        if (userId == null) {
            _state.update { it.copy(errorMessage = "Not signed in — please log in again.") }
            return
        }

        _state.update { it.copy(isSaving = true, errorMessage = null) }

        viewModelScope.launch {
            try {
                val companyRef = db.collection("companies").document(userId)
                val unitRef = companyRef.collection("inventory_units").document(current.scannedEpc)

                // ── Uniqueness check — EPC must not already exist ─────────
                val existing = unitRef.get().await()
                if (existing.exists()) {
                    _state.update {
                        it.copy(
                            isSaving = false,
                            errorMessage = "Tag ${current.scannedEpc} is already enrolled."
                        )
                    }
                    return@launch
                }

                // ── Build the InventoryUnit ───────────────────────────────
                val now = Timestamp.now()
                val unit = InventoryUnit(
                    epc = current.scannedEpc,
                    productId = "",                 // no SKU model for jewellery pieces
                    sku = "",
                    serialNumber = "",
                    currentWarehouseId = "",
                    currentRackId = "",
                    currentBinId = "",
                    currentBinCode = "",
                    status = "in_stock",
                    inwardedAt = now,
                    lastMovedAt = now,
                    dispatchedAt = null,
                    category = "jewellery",
                    itemCode = current.itemCode.trim(),
                    price = priceValue,
                    soldAt = null,
                    soldBy = null
                )

                // ── Build the Movement (append-only ledger entry) ─────────
                val movementRef = companyRef.collection("movements").document()
                val movement = Movement(
                    id = movementRef.id,
                    type = "inward",
                    productId = "",
                    sku = "",
                    epc = current.scannedEpc,
                    quantity = 1,
                    referenceType = "jewellery_enrollment",
                    referenceId = current.itemCode.trim(),
                    userId = userId,
                    timestamp = now,
                    notes = "Enrolled via Jewellery flow"
                )

                // ── Write both in one atomic batch ────────────────────────
                db.runBatch { batch ->
                    batch.set(unitRef, unit)
                    batch.set(movementRef, movement)
                }.await()

                _state.update {
                    JewelleryEnrollmentState(
                        successMessage = "Enrolled ${current.itemCode.trim()} (₹${priceValue.toInt()})"
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = "Failed to save: ${e.message ?: "unknown error"}"
                    )
                }
            }
        }
    }
}

/** Factory needed because the ViewModel has no args — kept for consistency with other WMS factories. */
class JewelleryEnrollmentViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(JewelleryEnrollmentViewModel::class.java)) {
            return JewelleryEnrollmentViewModel() as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}