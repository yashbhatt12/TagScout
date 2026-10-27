package com.snainfotech.tagscout.ui.screens.jewellery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.snainfotech.tagscout.data.config.Capability
import com.snainfotech.tagscout.data.config.CompanyConfig
import com.snainfotech.tagscout.data.config.ConfigRepository
import com.snainfotech.tagscout.data.config.ConfigSource
import com.snainfotech.tagscout.data.config.FieldLevel
import com.snainfotech.tagscout.data.config.FieldValidator
import com.snainfotech.tagscout.data.config.ProductTypeKey
import com.snainfotech.tagscout.data.wms.BatchRepository
import com.snainfotech.tagscout.data.wms.BatchStatus
import com.snainfotech.tagscout.data.wms.InventoryUnit
import com.snainfotech.tagscout.data.wms.Product
import com.snainfotech.tagscout.data.wms.ProductRepository
import com.snainfotech.tagscout.data.wms.batchStatus
import com.snainfotech.tagscout.data.wms.isPastExpiry
import com.snainfotech.tagscout.data.wms.type
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.util.Locale

/**
 * Checkout flow phases.
 *
 * Scanner lifecycle is driven off this: the NavGraph wrapper keeps the RFID
 * scanner running whenever phase == WAITING and stops it otherwise.
 *
 * Names are kept from the jewellery pilot so the screen is unchanged:
 * NOT_JEWELLERY now means "not a sellable product type", and WRONG_STATUS
 * also covers a piece blocked by its batch (see [JewelleryCheckoutState.blockedReason]).
 */
enum class CheckoutPhase {
    WAITING,         // Scanner running on low antenna power, waiting for a tag.
    LOOKING_UP,      // Firestore reads in flight.
    FOUND,           // In-stock sellable piece, ready to mark sold.
    NOT_FOUND,       // EPC not in inventory_units.
    NOT_JEWELLERY,   // Piece exists but its product type isn't sold at the counter.
    ALREADY_SOLD,    // Status already "sold".
    WRONG_STATUS,    // Any other status, or blocked by its batch (recalled / expired).
    SAVING,          // Mark-sold transaction in flight.
    SOLD             // Success — big confirmation shown to cashier.
}

/**
 * phase          — drives both the UI and the scanner lifecycle.
 * scannedEpc     — populated by a successful scan, cleared on reset.
 * foundPiece     — the unit when a piece was found (any phase after lookup).
 * product        — its product, or null for pilot pieces enrolled without one.
 * keyDetails     — label/value pairs ready to show: product, SKU, price, key unit fields.
 * blockedReason  — set with WRONG_STATUS when the batch, not the status, blocks the sale.
 * errorMessage   — one-shot error for the Snackbar.
 */
data class JewelleryCheckoutState(
    val phase: CheckoutPhase = CheckoutPhase.WAITING,
    val scannedEpc: String = "",
    val foundPiece: InventoryUnit? = null,
    val product: Product? = null,
    val keyDetails: List<Pair<String, String>> = emptyList(),
    val blockedReason: String? = null,
    val errorMessage: String? = null
) {
    val isWaitingForTag: Boolean get() = phase == CheckoutPhase.WAITING
    val isSaving: Boolean get() = phase == CheckoutPhase.SAVING

    /** Product name for the cards; pilot pieces fall back to their item code. */
    val displayName: String
        get() = product?.title?.takeIf { it.isNotBlank() }
            ?: foundPiece?.itemCode?.takeIf { it.isNotBlank() }?.let { "Item $it" }
            ?: "Piece"
}

/**
 * Checkout / Mark Sold for any product type with the retail sale capability.
 *
 * Flow:
 *   1. Screen opens → WAITING → NavGraph starts the scanner on low power.
 *   2. Tag read → onEpcScanned() → LOOKING_UP → scanner stops.
 *   3. Unit, product and (if any) batch are read and classified.
 *   4. "Mark Sold" → confirmation dialog (screen) → confirmSold().
 *   5. One transaction re-checks the unit is in stock, marks it sold and
 *      writes the outward movement → SOLD.
 *   6. "Sell another" → clearScan() → WAITING.
 */
class JewelleryCheckoutViewModel(
    private val productRepo: ProductRepository = ProductRepository(),
    private val batchRepo: BatchRepository = BatchRepository()
) : ViewModel() {

    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()

    private val _state = MutableStateFlow(JewelleryCheckoutState())
    val state: StateFlow<JewelleryCheckoutState> = _state.asStateFlow()

    /** Ignored unless WAITING, so a stray second read can't clobber a lookup or result. */
    fun onEpcScanned(epc: String) {
        if (_state.value.phase != CheckoutPhase.WAITING) return
        val cleaned = epc.uppercase().trim()
        _state.update { JewelleryCheckoutState(phase = CheckoutPhase.LOOKING_UP, scannedEpc = cleaned) }
        lookupPiece(cleaned)
    }

    fun clearScan() {
        _state.update { JewelleryCheckoutState(phase = CheckoutPhase.WAITING) }
    }

    fun dismissMessage() {
        _state.update { it.copy(errorMessage = null) }
    }

    // ── Lookup ────────────────────────────────────────────────

    private fun lookupPiece(epc: String) {
        val userId = auth.currentUser?.uid
        if (userId == null) {
            _state.update { it.copy(phase = CheckoutPhase.WAITING, errorMessage = "Not signed in — please log in again.") }
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

                val unit = doc.toObject(InventoryUnit::class.java)?.copy(epc = doc.id)
                if (unit == null) {
                    _state.update { it.copy(phase = CheckoutPhase.WAITING, errorMessage = "Could not read this piece's data.") }
                    return@launch
                }

                val product = if (unit.productId.isBlank()) null
                else productRepo.getProduct(unit.productId).getOrNull()
                val config = ConfigRepository.current
                val details = keyDetails(unit, product, config)

                if (!isSellable(unit, product, config)) {
                    _state.update {
                        it.copy(phase = CheckoutPhase.NOT_JEWELLERY, foundPiece = unit, product = product, keyDetails = details)
                    }
                    return@launch
                }

                val blocked = if (unit.status == "in_stock") batchBlockReason(unit, product, config) else null
                val nextPhase = when {
                    unit.status == "sold" -> CheckoutPhase.ALREADY_SOLD
                    blocked != null -> CheckoutPhase.WRONG_STATUS
                    unit.status == "in_stock" -> CheckoutPhase.FOUND
                    else -> CheckoutPhase.WRONG_STATUS
                }

                _state.update {
                    it.copy(
                        phase = nextPhase,
                        foundPiece = unit,
                        product = product,
                        keyDetails = details,
                        blockedReason = blocked
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(phase = CheckoutPhase.WAITING, errorMessage = "Lookup failed: ${e.message ?: "unknown error"}")
                }
            }
        }
    }

    /**
     * Sellable = the product's type has the retail sale capability.
     * Pilot pieces (no product, category "jewellery") stay sellable.
     * With no settings doc (defaults), jewellery products are sellable, as today.
     */
    private fun isSellable(unit: InventoryUnit, product: Product?, config: CompanyConfig): Boolean {
        if (product == null) return unit.productId.isBlank() && unit.category == LEGACY_CATEGORY
        val type = product.type()
        if (config.hasCapability(type, Capability.RETAIL_SALE)) return true
        return config.source == ConfigSource.DEFAULTS && type == ProductTypeKey.JEWELLERY
    }

    /** Null when the batch allows the sale; otherwise a reason for the cashier. */
    private suspend fun batchBlockReason(unit: InventoryUnit, product: Product?, config: CompanyConfig): String? {
        if (unit.batchId.isBlank()) return null
        val batch = batchRepo.getBatch(unit.batchId).getOrNull()
        val label = unit.batchNo.ifBlank { batch?.batchNo.orEmpty() }

        if (batch != null && !batch.batchStatus().allowsOutward) {
            return when (batch.batchStatus()) {
                BatchStatus.RECALLED -> "Batch $label has been recalled."
                BatchStatus.QUARANTINED -> "Batch $label is in quarantine."
                BatchStatus.EXPIRED -> "Batch $label has expired."
                BatchStatus.ACTIVE -> null
            }
        }

        val blockExpired = config.capabilityOptions.batchTracking.blockExpiredDispatch
        val tracksBatches = product != null && config.hasCapability(product.type(), Capability.BATCH_TRACKING)
        if (blockExpired && tracksBatches) {
            val expired = batch?.isPastExpiry() ?: unitPastExpiry(unit)
            if (expired) return "Batch $label is past its expiry date."
        }
        return null
    }

    private fun unitPastExpiry(unit: InventoryUnit): Boolean =
        runCatching { LocalDate.parse(unit.expiryDate).isBefore(LocalDate.now()) }.getOrDefault(false)

    /** Product, SKU, price and the first few filled unit fields, formatted for display. */
    private fun keyDetails(unit: InventoryUnit, product: Product?, config: CompanyConfig): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        if (product != null) {
            out += "Product" to product.title
            out += "SKU" to product.sku
        } else {
            unit.itemCode?.takeIf { it.isNotBlank() }?.let { out += "Item code" to it }
        }
        unit.price?.let { out += "Tag price" to formatRupees(it) }
        if (unit.batchNo.isNotBlank()) out += "Batch" to unit.batchNo
        if (unit.serialNumber.isNotBlank()) out += "Serial number" to unit.serialNumber

        if (product != null) {
            config.fieldsFor(product.type(), FieldLevel.UNIT)
                .mapNotNull { def ->
                    val text = FieldValidator.display(def, unit.customFields[def.key])
                    if (text.isBlank()) null else def.label to text
                }
                .take(MAX_UNIT_DETAILS)
                .forEach { out += it }
        }
        if (unit.currentBinCode.isNotBlank()) out += "Location" to unit.currentBinCode
        return out
    }

    private fun formatRupees(value: Double): String {
        val whole = value % 1.0 == 0.0
        val number = if (whole) value.toLong().toString() else String.format(Locale.ENGLISH, "%.2f", value)
        return "₹" + groupIndian(number)
    }

    /** 1234567 → 12,34,567 (Indian grouping); decimals kept as they are. */
    private fun groupIndian(number: String): String {
        val intPart = number.substringBefore('.')
        val decPart = number.substringAfter('.', "")
        if (intPart.length <= 3) return number
        val last3 = intPart.takeLast(3)
        val rest = intPart.dropLast(3).reversed().chunked(2).joinToString(",").reversed()
        return "$rest,$last3" + if (decPart.isNotEmpty()) ".$decPart" else ""
    }

    // ── Mark sold ─────────────────────────────────────────────

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
        val product = current.product

        _state.update { it.copy(phase = CheckoutPhase.SAVING, errorMessage = null) }

        viewModelScope.launch {
            try {
                val companyRef = db.collection("companies").document(userId)
                val unitRef = companyRef.collection("inventory_units").document(piece.epc)
                val movementRef = companyRef.collection("movements").document()
                val now = Timestamp.now()

                db.runTransaction { txn ->
                    // Re-check inside the transaction: another counter may have sold it.
                    val snap = txn.get(unitRef)
                    val status = snap.getString("status")
                    if (!snap.exists() || status != "in_stock") {
                        throw IllegalStateException(
                            if (status == "sold") "This piece was just sold elsewhere." else "This piece is no longer in stock."
                        )
                    }

                    txn.update(
                        unitRef,
                        mapOf(
                            "status" to "sold",
                            "soldAt" to now,
                            "soldBy" to userId,
                            "lastMovedAt" to now
                        )
                    )

                    // productId / sku / from-location let the aggregate function drop
                    // the stock count. Pilot pieces have no product, so it skips them as before.
                    val movement = hashMapOf<String, Any>(
                        "type" to "outward",
                        "productId" to piece.productId,
                        "sku" to (product?.sku ?: piece.sku),
                        "epc" to piece.epc,
                        "quantity" to -1,
                        "fromWarehouseId" to piece.currentWarehouseId,
                        "fromRackId" to piece.currentRackId,
                        "fromBinId" to piece.currentBinId,
                        "fromBinCode" to piece.currentBinCode,
                        "toWarehouseId" to "",
                        "toRackId" to "",
                        "toBinId" to "",
                        "toBinCode" to "",
                        "referenceType" to if (product != null) "retail_sale" else "jewellery_sale",
                        "referenceId" to (piece.itemCode ?: ""),
                        "userId" to userId,
                        "timestamp" to now,
                        "notes" to "Sold at checkout",
                        "batchId" to piece.batchId
                    )
                    txn.set(movementRef, movement)
                    null
                }.await()

                val soldPiece = piece.copy(status = "sold", soldAt = now, soldBy = userId, lastMovedAt = now)
                _state.update { it.copy(phase = CheckoutPhase.SOLD, foundPiece = soldPiece) }
            } catch (e: Exception) {
                val msg = (e.cause as? IllegalStateException)?.message ?: e.message ?: "unknown error"
                _state.update { it.copy(phase = CheckoutPhase.FOUND, errorMessage = "Failed to mark sold: $msg") }
            }
        }
    }

    private companion object {
        const val LEGACY_CATEGORY = "jewellery"
        const val MAX_UNIT_DETAILS = 4
    }
}

class JewelleryCheckoutViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(JewelleryCheckoutViewModel::class.java)) {
            return JewelleryCheckoutViewModel() as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}