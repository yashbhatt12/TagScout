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
 * Enrollment flow phases.
 *
 * Scanner lifecycle: NavGraph keeps the sled scanning on low antenna power
 * whenever phase == WAITING and stops it otherwise. Mirrors the Checkout
 * flow so cashier staff see consistent UX across both screens.
 */
enum class EnrollmentPhase {
    WAITING,         // Scanner running on low antenna power, waiting for a tag.
    LOOKING_UP,      // Verifying the tag isn't already enrolled.
    NEW_PIECE,       // Fresh tag — form shown for item code + price.
    DUPLICATE,       // Tag already exists in inventory_units.
    SAVING,          // Batch write in flight.
    ENROLLED         // Success — big confirmation shown.
}

/**
 * State for the Jewellery Enrollment screen.
 *
 * phase            — drives both the UI and the scanner lifecycle.
 * scannedEpc       — populated on first scan, preserved into NEW_PIECE / ENROLLED.
 * itemCode / price — form inputs, bound to the NEW_PIECE form.
 * existingPiece    — populated in DUPLICATE phase so the user can see what the
 *                    tag is already assigned to.
 * savedPiece       — populated in ENROLLED phase for the success card.
 * errorMessage     — one-shot error for the Snackbar.
 */
data class JewelleryEnrollmentState(
    val phase: EnrollmentPhase = EnrollmentPhase.WAITING,
    val scannedEpc: String = "",
    val itemCode: String = "",
    val price: String = "",
    val existingPiece: InventoryUnit? = null,
    val savedPiece: InventoryUnit? = null,
    val errorMessage: String? = null
) {
    /** True while the scanner should be running on low antenna power. */
    val isWaitingForTag: Boolean get() = phase == EnrollmentPhase.WAITING

    /** True while a save network call is in flight. */
    val isSaving: Boolean get() = phase == EnrollmentPhase.SAVING

    /** Can Save be tapped? Only in NEW_PIECE with both fields populated. */
    val canSave: Boolean get() =
        phase == EnrollmentPhase.NEW_PIECE &&
                itemCode.isNotBlank() &&
                price.isNotBlank() &&
                price.toDoubleOrNull()?.let { it > 0 } == true
}

/**
 * Jewellery enrollment ViewModel.
 *
 * Flow:
 *   1. Screen opens → phase=WAITING → NavGraph starts scanner on low power.
 *   2. Cashier places piece on reader → scanner emits tag → onEpcScanned().
 *   3. phase=LOOKING_UP → check if EPC already in inventory_units.
 *      → If exists: phase=DUPLICATE with existingPiece populated.
 *      → If new:   phase=NEW_PIECE with scannedEpc populated and form ready.
 *   4. Cashier enters itemCode and price → canSave becomes true.
 *   5. Cashier taps Save → phase=SAVING → batch write.
 *   6. On success: phase=ENROLLED with savedPiece populated.
 *   7. Cashier taps "Enroll another" → clearScan() → back to WAITING.
 */
class JewelleryEnrollmentViewModel : ViewModel() {

    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()

    private val _state = MutableStateFlow(JewelleryEnrollmentState())
    val state: StateFlow<JewelleryEnrollmentState> = _state.asStateFlow()

    /**
     * Called by the screen when a tag has been detected by the sled.
     *
     * Idempotent per phase: calls while not in WAITING are ignored to protect
     * against a stray second read arriving in the ~50 ms window between
     * phase change and scanner shutdown.
     */
    fun onEpcScanned(epc: String) {
        if (_state.value.phase != EnrollmentPhase.WAITING) return

        val cleaned = epc.uppercase().trim()
        _state.update {
            JewelleryEnrollmentState(
                phase = EnrollmentPhase.LOOKING_UP,
                scannedEpc = cleaned
            )
        }
        checkDuplicate(cleaned)
    }

    /** User edited the item code field. */
    fun onItemCodeChanged(itemCode: String) {
        if (_state.value.phase != EnrollmentPhase.NEW_PIECE) return
        _state.update { it.copy(itemCode = itemCode, errorMessage = null) }
    }

    /** User edited the price field — sanitised to digits + single decimal. */
    fun onPriceChanged(price: String) {
        if (_state.value.phase != EnrollmentPhase.NEW_PIECE) return
        val sanitized = price.filterIndexed { index, ch ->
            ch.isDigit() || (ch == '.' && !price.substring(0, index).contains('.'))
        }
        _state.update { it.copy(price = sanitized, errorMessage = null) }
    }

    /**
     * Reset back to WAITING so the scanner restarts for the next piece.
     * Called after a successful save ("Enroll another") or user-triggered
     * reset from any non-WAITING state.
     */
    fun clearScan() {
        _state.update { JewelleryEnrollmentState(phase = EnrollmentPhase.WAITING) }
    }

    /** Dismisses the error snackbar without changing phase. */
    fun dismissMessage() {
        _state.update { it.copy(errorMessage = null) }
    }

    /** Checks whether the scanned EPC already has an InventoryUnit document. */
    private fun checkDuplicate(epc: String) {
        val userId = auth.currentUser?.uid
        if (userId == null) {
            _state.update {
                it.copy(
                    phase = EnrollmentPhase.WAITING,
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

                if (doc.exists()) {
                    val existing = doc.toObject(InventoryUnit::class.java)
                    _state.update {
                        it.copy(
                            phase = EnrollmentPhase.DUPLICATE,
                            existingPiece = existing
                        )
                    }
                } else {
                    _state.update {
                        it.copy(phase = EnrollmentPhase.NEW_PIECE)
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        phase = EnrollmentPhase.WAITING,
                        errorMessage = "Lookup failed: ${e.message ?: "unknown error"}"
                    )
                }
            }
        }
    }

    /**
     * Save the current form as a new InventoryUnit + inward Movement in one
     * atomic batch. Uses the EPC as the document ID so uniqueness is
     * guaranteed at the DB level (even in the unlikely race where another
     * device enrolls the same tag between our checkDuplicate and this write).
     */
    fun save() {
        val s = _state.value
        if (!s.canSave) return

        val priceValue = s.price.toDoubleOrNull()
        if (priceValue == null || priceValue <= 0) {
            _state.update { it.copy(errorMessage = "Enter a valid price.") }
            return
        }

        val userId = auth.currentUser?.uid
        if (userId == null) {
            _state.update { it.copy(errorMessage = "Not signed in — please log in again.") }
            return
        }

        _state.update { it.copy(phase = EnrollmentPhase.SAVING, errorMessage = null) }

        viewModelScope.launch {
            try {
                val companyRef = db.collection("companies").document(userId)
                val unitRef = companyRef.collection("inventory_units").document(s.scannedEpc)
                val movementRef = companyRef.collection("movements").document()

                val now = Timestamp.now()
                val cleanedItemCode = s.itemCode.trim()

                val unit = InventoryUnit(
                    epc = s.scannedEpc,
                    status = "in_stock",
                    inwardedAt = now,
                    lastMovedAt = now,
                    category = "jewellery",
                    itemCode = cleanedItemCode,
                    price = priceValue
                )

                val movement = Movement(
                    id = movementRef.id,
                    type = "inward",
                    productId = "",
                    sku = "",
                    epc = s.scannedEpc,
                    quantity = 1,
                    referenceType = "jewellery_enrollment",
                    referenceId = cleanedItemCode,
                    userId = userId,
                    timestamp = now,
                    notes = "Enrolled via Jewellery flow"
                )

                db.runBatch { batch ->
                    batch.set(unitRef, unit)
                    batch.set(movementRef, movement)
                }.await()

                _state.update {
                    it.copy(phase = EnrollmentPhase.ENROLLED, savedPiece = unit)
                }
            } catch (e: Exception) {
                // On failure, drop back to NEW_PIECE so the cashier can retry
                // without re-scanning.
                _state.update {
                    it.copy(
                        phase = EnrollmentPhase.NEW_PIECE,
                        errorMessage = "Failed to enroll: ${e.message ?: "unknown error"}"
                    )
                }
            }
        }
    }
}

/** Factory kept for consistency with other WMS ViewModels. */
class JewelleryEnrollmentViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(JewelleryEnrollmentViewModel::class.java)) {
            return JewelleryEnrollmentViewModel() as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}