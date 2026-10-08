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
 * Checkout flow phases.
 *
 * Scanner lifecycle is driven off this: the NavGraph wrapper keeps the RFID
 * scanner running whenever phase == WAITING and stops it otherwise. The
 * screen itself doesn't talk to the scanner at all — it only observes phase
 * and shows the appropriate UI.
 */
enum class CheckoutPhase {
    WAITING,         // Scanner is running on low antenna power, waiting for a tag.
    LOOKING_UP,      // Firestore query in flight.
    FOUND,           // Valid in_stock jewellery piece ready to sell.
    NOT_FOUND,       // EPC not in inventory_units.
    NOT_JEWELLERY,   // EPC exists but not category=jewellery.
    ALREADY_SOLD,    // Jewellery piece but status already "sold".
    WRONG_STATUS,    // Jewellery piece but status is picked/dispatched/etc.
    SAVING,          // Mark-sold batch is in flight.
    SOLD             // Success — big confirmation shown to cashier.
}

/**
 * State for the Jewellery Checkout / Mark Sold screen.
 *
 * phase           — drives both the UI and the scanner lifecycle.
 * scannedEpc      — populated by a successful scan, cleared on reset.
 * foundPiece      — the InventoryUnit when phase == FOUND or SOLD.
 * errorMessage    — one-shot error for the Snackbar.
 */
data class JewelleryCheckoutState(
    val phase: CheckoutPhase = CheckoutPhase.WAITING,
    val scannedEpc: String = "",
    val foundPiece: InventoryUnit? = null,
    val errorMessage: String? = null
) {
    /** True while the scanner should be running on low antenna power. */
    val isWaitingForTag: Boolean get() = phase == CheckoutPhase.WAITING

    /** True while a mark-sold network call is in flight. */
    val isSaving: Boolean get() = phase == CheckoutPhase.SAVING
}

/**
 * Jewellery Checkout ViewModel.
 *
 * Flow:
 *   1. Screen opens → phase=WAITING → NavGraph starts scanner on low power.
 *   2. Cashier places piece on reader → scanner emits tag → onEpcScanned().
 *   3. NavGraph observes phase != WAITING and stops the scanner.
 *   4. Lookup runs → phase moves to FOUND / NOT_FOUND / ALREADY_SOLD / etc.
 *   5. On tap of "Mark Sold" → confirmation dialog (owned by the screen).
 *   6. On dialog confirm → confirmSold() → batch write → phase=SOLD.
 *   7. Cashier taps "Sell another piece" → clearScan() → phase=WAITING,
 *      scanner restarts, ready for the next piece.
 */
class JewelleryCheckoutViewModel : ViewModel() {

    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()

    private val _state = MutableStateFlow(JewelleryCheckoutState())
    val state: StateFlow<JewelleryCheckoutState> = _state.asStateFlow()

    /**
     * Called by the screen when a tag has been detected by the sled.
     *
     * Idempotent per phase: if we're already past WAITING (e.g. a stray
     * second tag arrived in the same window before the scanner fully
     * stopped), we ignore it to avoid clobbering an in-progress lookup
     * or an already-shown result.
     */
    fun onEpcScanned(epc: String) {
        if (_state.value.phase != CheckoutPhase.WAITING) return

        val cleaned = epc.uppercase().trim()
        _state.update {
            JewelleryCheckoutState(
                phase = CheckoutPhase.LOOKING_UP,
                scannedEpc = cleaned
            )
        }
        lookupPiece(cleaned)
    }

    /**
     * Reset back to WAITING so the scanner restarts for the next piece.
     * Called when the cashier taps "Scan another piece" / "Sell another",
     * and used internally after an error.
     */
    fun clearScan() {
        _state.update { JewelleryCheckoutState(phase = CheckoutPhase.WAITING) }
    }

    /** Dismisses the error snackbar without changing phase. */
    fun dismissMessage() {
        _state.update { it.copy(errorMessage = null) }
    }

    /** Fetches the InventoryUnit and classifies the result into a phase. */
    private fun lookupPiece(epc: String) {
        val userId = auth.currentUser?.uid
        if (userId == null) {
            _state.update {
                it.copy(
                    phase = CheckoutPhase.WAITING,
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
                    _state.update { it.copy(phase = CheckoutPhase.NOT_FOUND, foundPiece = null) }
                    return@launch
                }

                val unit = doc.toObject(InventoryUnit::class.java)
                if (unit == null) {
                    _state.update {
                        it.copy(
                            phase = CheckoutPhase.WAITING,
                            errorMessage = "Could not parse piece data."
                        )
                    }
                    return@launch
                }

                val nextPhase = when {
                    unit.category != "jewellery" -> CheckoutPhase.NOT_JEWELLERY
                    unit.status == "sold" -> CheckoutPhase.ALREADY_SOLD
                    unit.status == "in_stock" -> CheckoutPhase.FOUND
                    else -> CheckoutPhase.WRONG_STATUS
                }

                _state.update { it.copy(phase = nextPhase, foundPiece = unit) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        phase = CheckoutPhase.WAITING,
                        errorMessage = "Lookup failed: ${e.message ?: "unknown error"}"
                    )
                }
            }
        }
    }

    /**
     * Mark the currently-shown piece as sold.
     * Writes status change + outward movement in a single batch, then moves
     * to phase=SOLD so the screen can show the big confirmation.
     */
    fun confirmSold() {
        val current = _state.value
        val piece = current.foundPiece
        if (piece == null || current.phase != CheckoutPhase.FOUND) {
            _state.update { it.copy(errorMessage = "No piece ready to mark sold.") }
            return
        }

        val userId = auth.currentUser?.uid
        if (userId == null) {
            _state.update { it.copy(errorMessage = "Not signed in — please log in again.") }
            return
        }

        _state.update { it.copy(phase = CheckoutPhase.SAVING, errorMessage = null) }

        viewModelScope.launch {
            try {
                val companyRef = db.collection("companies").document(userId)
                val unitRef = companyRef.collection("inventory_units").document(piece.epc)
                val movementRef = companyRef.collection("movements").document()

                val now = Timestamp.now()

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

                // Keep foundPiece so the SOLD screen can show item details.
                // Reflect the status change locally so the UI copy is accurate.
                val soldPiece = piece.copy(
                    status = "sold",
                    soldAt = now,
                    soldBy = userId,
                    lastMovedAt = now
                )
                _state.update {
                    it.copy(phase = CheckoutPhase.SOLD, foundPiece = soldPiece)
                }
            } catch (e: Exception) {
                // On failure, drop back to FOUND so the cashier can retry.
                _state.update {
                    it.copy(
                        phase = CheckoutPhase.FOUND,
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