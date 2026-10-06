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
 * State for the Jewellery Checkout / Mark Sold screen.
 *
 * scannedEpc    — populated by a successful scan.
 * lookupStatus  — what happened when we tried to resolve the EPC in Firestore.
 * foundPiece    — the InventoryUnit when lookupStatus is FOUND, null otherwise.
 * isSaving      — true while the sold batch is in flight.
 * errorMessage  — one-shot error for display.
 * successMessage — one-shot success for display.
 */
data class JewelleryCheckoutState(
    val scannedEpc: String = "",
    val lookupStatus: LookupStatus = LookupStatus.IDLE,
    val foundPiece: InventoryUnit? = null,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

/** What happened when we tried to look up the scanned EPC. */
enum class LookupStatus {
    IDLE,           // Nothing scanned yet
    LOOKING_UP,     // Query in flight
    FOUND,          // Found a valid in_stock jewellery piece — ready to confirm
    NOT_FOUND,      // EPC not in inventory_units — unenrolled tag
    NOT_JEWELLERY,  // EPC exists but is not category=jewellery (it's a WMS unit)
    ALREADY_SOLD,   // EPC exists, is jewellery, but status already "sold"
    WRONG_STATUS    // EPC exists, is jewellery, but status is something unexpected (picked/dispatched/etc.)
}

/**
 * Jewellery Checkout ViewModel.
 *
 * Flow:
 *   1. Cashier scans the piece at the counter.
 *   2. ViewModel looks up the EPC in inventory_units/{epc}.
 *   3. Based on what's there, we show one of: piece details (ready to confirm),
 *      "already sold", "not enrolled", or "wrong type".
 *   4. On Confirm, we mark the piece sold + write a movement in one batch.
 *   5. On success, screen clears for the next piece.
 */
class JewelleryCheckoutViewModel : ViewModel() {

    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()

    private val _state = MutableStateFlow(JewelleryCheckoutState())
    val state: StateFlow<JewelleryCheckoutState> = _state.asStateFlow()

    /** Called by the screen when a tag has been scanned by the handheld sled. */
    fun onEpcScanned(epc: String) {
        val cleaned = epc.uppercase().trim()
        _state.update {
            JewelleryCheckoutState(
                scannedEpc = cleaned,
                lookupStatus = LookupStatus.LOOKING_UP
            )
        }
        lookupPiece(cleaned)
    }

    /** User tapped Cancel / Scan Different / Clear — reset to idle. */
    fun clearScan() {
        _state.update { JewelleryCheckoutState() }
    }

    /** Dismisses error/success messages without affecting other state. */
    fun dismissMessage() {
        _state.update { it.copy(errorMessage = null, successMessage = null) }
    }

    /** Private: fetch the InventoryUnit document and classify the result. */
    private fun lookupPiece(epc: String) {
        val userId = auth.currentUser?.uid
        if (userId == null) {
            _state.update {
                it.copy(
                    lookupStatus = LookupStatus.IDLE,
                    errorMessage = "Not signed in — please log in again."
                )
            }
            return
        }

        viewModelScope.launch {
            try {
                val doc = db.collection("companies").document(userId)
                    .collection("inventory_units").document(epc)
                    .get().await()

                if (!doc.exists()) {
                    _state.update {
                        it.copy(lookupStatus = LookupStatus.NOT_FOUND, foundPiece = null)
                    }
                    return@launch
                }

                val unit = doc.toObject(InventoryUnit::class.java)
                if (unit == null) {
                    _state.update {
                        it.copy(
                            lookupStatus = LookupStatus.IDLE,
                            errorMessage = "Could not parse piece data."
                        )
                    }
                    return@launch
                }

                val status = when {
                    unit.category != "jewellery" -> LookupStatus.NOT_JEWELLERY
                    unit.status == "sold" -> LookupStatus.ALREADY_SOLD
                    unit.status == "in_stock" -> LookupStatus.FOUND
                    else -> LookupStatus.WRONG_STATUS
                }

                _state.update {
                    it.copy(lookupStatus = status, foundPiece = unit)
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        lookupStatus = LookupStatus.IDLE,
                        errorMessage = "Lookup failed: ${e.message ?: "unknown error"}"
                    )
                }
            }
        }
    }

    /**
     * Marks the currently-shown piece as sold.
     * Writes status change + outward movement in a single batch.
     */
    fun confirmSold() {
        val current = _state.value
        val piece = current.foundPiece
        if (piece == null || current.lookupStatus != LookupStatus.FOUND) {
            _state.update { it.copy(errorMessage = "No piece ready to mark sold.") }
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
                val unitRef = companyRef.collection("inventory_units").document(piece.epc)
                val movementRef = companyRef.collection("movements").document()

                val now = Timestamp.now()

                // Partial update to the InventoryUnit — only the sold-related fields change.
                val unitUpdate: Map<String, Any> = mapOf(
                    "status" to "sold",
                    "soldAt" to now,
                    "soldBy" to userId,
                    "lastMovedAt" to now
                )

                val movement = Movement(
                    id = movementRef.id,
                    type = "outward",
                    productId = "",
                    sku = "",
                    epc = piece.epc,
                    quantity = -1,
                    fromBinCode = piece.currentBinCode,
                    referenceType = "jewellery_sale",
                    referenceId = piece.itemCode ?: "",
                    userId = userId,
                    timestamp = now,
                    notes = "Sold via Jewellery checkout"
                )

                db.runBatch { batch ->
                    batch.update(unitRef, unitUpdate)
                    batch.set(movementRef, movement)
                }.await()

                _state.update {
                    JewelleryCheckoutState(
                        successMessage = "Sold ${piece.itemCode} (₹${piece.price?.toInt() ?: 0})"
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = "Failed to mark sold: ${e.message ?: "unknown error"}"
                    )
                }
            }
        }
    }
}

/** Factory kept for consistency with other WMS ViewModels. */
class JewelleryCheckoutViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(JewelleryCheckoutViewModel::class.java)) {
            return JewelleryCheckoutViewModel() as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}