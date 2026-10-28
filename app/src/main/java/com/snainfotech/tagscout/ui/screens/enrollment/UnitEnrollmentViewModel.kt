package com.snainfotech.tagscout.ui.screens.enrollment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.snainfotech.tagscout.data.config.Capability
import com.snainfotech.tagscout.data.config.CompanyConfig
import com.snainfotech.tagscout.data.config.ConfigRepository
import com.snainfotech.tagscout.data.config.CoreField
import com.snainfotech.tagscout.data.config.FieldDef
import com.snainfotech.tagscout.data.config.FieldLevel
import com.snainfotech.tagscout.data.config.FieldValidator
import com.snainfotech.tagscout.data.config.ProductTypeKey
import com.snainfotech.tagscout.data.wms.Batch
import com.snainfotech.tagscout.data.wms.BatchDates
import com.snainfotech.tagscout.data.wms.BatchRepository
import com.snainfotech.tagscout.data.wms.Bin
import com.snainfotech.tagscout.data.wms.EnrollContext
import com.snainfotech.tagscout.data.wms.EnrollOutcome
import com.snainfotech.tagscout.data.wms.EnrollSummary
import com.snainfotech.tagscout.data.wms.EnrollUnitInput
import com.snainfotech.tagscout.data.wms.InventoryRepository
import com.snainfotech.tagscout.data.wms.Product
import com.snainfotech.tagscout.data.wms.ProductRepository
import com.snainfotech.tagscout.data.wms.ProductStatus
import com.snainfotech.tagscout.data.wms.Rack
import com.snainfotech.tagscout.data.wms.Warehouse
import com.snainfotech.tagscout.data.wms.WarehouseRepository
import com.snainfotech.tagscout.data.wms.isValidEpc
import com.snainfotech.tagscout.data.wms.productStatus
import com.snainfotech.tagscout.data.wms.type
import com.snainfotech.tagscout.data.config.ConfigSource
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ── Modes and phases ──────────────────────────────────────────────────────

/** How tags arrive. The ViewModel never talks to a reader; each mode just feeds it tags. */
enum class ReaderMode(val label: String, val description: String) {
    SLED("Handheld sled", "Bluetooth sled: one piece or many"),
    DESKTOP("Desktop reader", "USB pad: one piece at a time"),
    FIXED("Fixed reader", "RFID chamber: a whole box at once");

    val defaultCapture: CaptureMode
        get() = if (this == FIXED) CaptureMode.BATCH else CaptureMode.SINGLE

    /** Only the sled lets the user choose; the pad is always single, the chamber always batch. */
    val captureSelectable: Boolean get() = this == SLED
}

enum class CaptureMode(val label: String) {
    SINGLE("One at a time"),
    BATCH("Many together")
}

enum class EnrollPhase {
    SETUP,      // choose reader, product, location (and batch)
    SCANNING,   // reader live, waiting for tags
    DETAILS,    // filling in per-piece values
    SAVING,     // writes in flight
    RESULT      // summary after a batch save, or after a single save that failed
}

enum class PieceStatus { CHECKING, READY, ALREADY_ENROLLED, SAVED, FAILED }

/** One scanned tag and the values typed for it. Everything is text until save. */
data class PieceDraft(
    val epc: String,
    val tid: String = "",
    val status: PieceStatus = PieceStatus.CHECKING,
    val note: String = "",
    val serialNumber: String = "",
    val price: String = "",
    val customValues: Map<String, String> = emptyMap(),
    /** Key → message. Core keys are CoreField keys; custom keys are FieldDef keys (they can't collide). */
    val errors: Map<String, String> = emptyMap()
) {
    val isSaveable: Boolean get() = status == PieceStatus.READY || status == PieceStatus.FAILED
}

// ── State ─────────────────────────────────────────────────────────────────

data class UnitEnrollmentState(
    val loading: Boolean = true,
    val config: CompanyConfig = CompanyConfig.DEFAULT,
    val phase: EnrollPhase = EnrollPhase.SETUP,
    val reader: ReaderMode = ReaderMode.SLED,
    val capture: CaptureMode = ReaderMode.SLED.defaultCapture,

    val products: List<Product> = emptyList(),
    val product: Product? = null,

    val warehouses: List<Warehouse> = emptyList(),
    val racks: List<Rack> = emptyList(),
    val bins: List<Bin> = emptyList(),
    val warehouseId: String = "",
    val rackId: String = "",
    val binId: String = "",

    val batchNo: String = "",
    val mfgDate: String = "",       // "YYYY-MM-DD" from the date picker
    val expiryDate: String = "",    // "YYYY-MM-DD" from the date picker
    val existingBatch: Batch? = null,
    val checkingBatch: Boolean = false,
    val batchErrors: Map<String, String> = emptyMap(),

    val pieces: List<PieceDraft> = emptyList(),
    val selectedEpc: String? = null,

    val sessionEnrolled: Int = 0,
    val lastSummary: EnrollSummary? = null,
    val message: String? = null
) {
    val type: ProductTypeKey get() = product?.type() ?: ProductTypeKey.GENERAL

    /** Unit-level custom fields for the chosen product's type, in display order. */
    val unitFields: List<FieldDef>
        get() = if (product == null) emptyList() else config.fieldsFor(type, FieldLevel.UNIT)

    fun isRequired(field: CoreField): Boolean = product != null && config.isCoreRequired(type, field)

    val tracksBatches: Boolean
        get() = product != null && config.hasCapability(type, Capability.BATCH_TRACKING)

    val showPrice: Boolean
        get() = product != null &&
                (config.hasCapability(type, Capability.RETAIL_SALE) ||
                        isRequired(CoreField.PRICE) ||
                        (config.source == ConfigSource.DEFAULTS && type == ProductTypeKey.JEWELLERY))

    val showSerial: Boolean
        get() = product != null &&
                (type == ProductTypeKey.IT_ASSET || type == ProductTypeKey.NON_IT_ASSET ||
                        isRequired(CoreField.SERIAL_NUMBER))

    val racksInWarehouse: List<Rack> get() = racks.filter { it.warehouseId == warehouseId }
    val binsInRack: List<Bin> get() = bins.filter { it.rackId == rackId }
    val bin: Bin? get() = bins.firstOrNull { it.id == binId }

    val setupComplete: Boolean get() = product != null && bin != null

    /** NavGraph / the screen run the reader exactly while this is true. */
    val isListening: Boolean get() = phase == EnrollPhase.SCANNING && pieces.size < MAX_PIECES

    val saveablePieces: List<PieceDraft> get() = pieces.filter { it.isSaveable }
    val selectedPiece: PieceDraft? get() = pieces.firstOrNull { it.epc == selectedEpc }

    val canContinue: Boolean
        get() = phase == EnrollPhase.SCANNING &&
                pieces.any { it.status == PieceStatus.READY } &&
                pieces.none { it.status == PieceStatus.CHECKING }

    val canSave: Boolean get() = phase == EnrollPhase.DETAILS && saveablePieces.isNotEmpty() &&
            pieces.none { it.status == PieceStatus.CHECKING }

    val hasUnsavedPieces: Boolean get() = pieces.any { it.isSaveable || it.status == PieceStatus.CHECKING }
    /** Save finished with nothing left to fix: the screen asks "enroll another or back to menu". */
    val allDone: Boolean
        get() = phase == EnrollPhase.RESULT && pieces.none { it.status == PieceStatus.FAILED }
    companion object {
        const val MAX_PIECES = 200
    }
}

private data class ValidatedPiece(val input: EnrollUnitInput, val errors: Map<String, String>)

// ── ViewModel ─────────────────────────────────────────────────────────────

/**
 * Scan-based enrollment for every product type. Reader-agnostic: the sled
 * (via NavGraph), the desktop pad (keystrokes captured by the screen) and
 * the fixed reader (edge service, later) all call [onTagScanned] or
 * [onTagsReceived]. Saving goes through InventoryRepository.enrollUnits,
 * the same path GRN will use.
 */
class UnitEnrollmentViewModel(
    private val initialProductId: String? = null,
    private val productRepo: ProductRepository = ProductRepository(),
    private val warehouseRepo: WarehouseRepository = WarehouseRepository(),
    private val inventoryRepo: InventoryRepository = InventoryRepository(),
    private val batchRepo: BatchRepository = BatchRepository()
) : ViewModel() {

    private val _state = MutableStateFlow(UnitEnrollmentState())
    val state: StateFlow<UnitEnrollmentState> = _state.asStateFlow()
    /**
     * Tags enrolled, or found already enrolled, since this screen opened.
     * Readers keep reporting a tag while it's in the field (a piece left on
     * the pad, or the sled still pointing at it), so these are ignored
     * silently instead of reopening and rejecting the same tag in a loop.
     */
    private val handledEpcs = HashSet<String>()
    init {
        viewModelScope.launch {
            ConfigRepository.config.collect { cfg -> _state.update { it.copy(config = cfg) } }
        }
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val productsJob = async { productRepo.getProducts() }
            val structureJob = async { warehouseRepo.getFullStructure() }

            val products = productsJob.await().getOrElse { e ->
                _state.update { it.copy(message = "Could not load products: ${e.message}") }
                emptyList()
            }
                .filter { it.isActive && it.productStatus() != ProductStatus.DISCONTINUED }
                .sortedBy { it.title.lowercase() }

            val structure = structureJob.await().getOrElse { e ->
                _state.update { it.copy(message = "Could not load locations: ${e.message}") }
                null
            }

            _state.update { st ->
                var next = st.copy(
                    loading = false,
                    products = products,
                    warehouses = structure?.warehouses.orEmpty().sortedBy { it.name.lowercase() },
                    racks = structure?.racks.orEmpty().sortedBy { it.name.lowercase() },
                    bins = structure?.bins.orEmpty().sortedBy { it.binCode.lowercase() }
                )
                // Auto-pick when there's only one choice at a level.
                next.warehouses.singleOrNull()?.let { next = withWarehouse(next, it.id) }
                initialProductId?.let { id -> products.firstOrNull { it.id == id } }
                    ?.let { next = next.copy(product = it) }
                next
            }
        }
    }

    // ── Setup ─────────────────────────────────────────────────

    fun setReader(mode: ReaderMode) = _state.update { st ->
        when {
            st.reader == mode -> st
            st.hasUnsavedPieces -> st.copy(message = "Save or clear the scanned tags first")
            else -> st.copy(
                reader = mode,
                capture = mode.defaultCapture,
                phase = if (st.phase == EnrollPhase.RESULT) EnrollPhase.SETUP else st.phase,
                pieces = emptyList(),
                selectedEpc = null
            )
        }
    }

    fun setCapture(mode: CaptureMode) = _state.update { st ->
        when {
            !st.reader.captureSelectable || st.capture == mode -> st
            st.hasUnsavedPieces -> st.copy(message = "Save or clear the scanned tags first")
            else -> st.copy(capture = mode)
        }
    }

    fun selectProduct(productId: String) = _state.update { st ->
        val product = st.products.firstOrNull { it.id == productId } ?: return@update st
        when {
            st.product?.id == productId -> st
            st.hasUnsavedPieces -> st.copy(message = "Save or clear the scanned tags first")
            else -> st.copy(
                product = product,
                batchNo = "", mfgDate = "", expiryDate = "",
                existingBatch = null, batchErrors = emptyMap(),
                pieces = emptyList(), selectedEpc = null
            )
        }
    }

    fun selectWarehouse(id: String) = _state.update { withWarehouse(it, id) }

    fun selectRack(id: String) = _state.update { withRack(it, id) }

    fun selectBin(id: String) = _state.update { it.copy(binId = id) }

    private fun withWarehouse(st: UnitEnrollmentState, id: String): UnitEnrollmentState {
        if (st.warehouseId == id) return st
        val base = st.copy(warehouseId = id, rackId = "", binId = "")
        return base.racksInWarehouse.singleOrNull()?.let { withRack(base, it.id) } ?: base
    }

    private fun withRack(st: UnitEnrollmentState, id: String): UnitEnrollmentState {
        if (st.rackId == id) return st
        val base = st.copy(rackId = id, binId = "")
        return base.binsInRack.singleOrNull()?.let { base.copy(binId = it.id) } ?: base
    }

    // ── Batch ─────────────────────────────────────────────────

    fun onBatchNo(v: String) = _state.update {
        it.copy(batchNo = v, existingBatch = null, batchErrors = it.batchErrors - CoreField.BATCH_NO.key)
    }

    fun onMfgDate(iso: String) = _state.update {
        it.copy(mfgDate = iso, batchErrors = it.batchErrors - CoreField.MFG_DATE.key - KEY_BATCH_DATES)
    }

    fun onExpiryDate(iso: String) = _state.update {
        it.copy(expiryDate = iso, batchErrors = it.batchErrors - CoreField.EXPIRY_DATE.key - KEY_BATCH_DATES)
    }

    /** Called when the batch number box loses focus: an existing batch fills in its locked dates. */
    fun checkBatch() {
        val st = _state.value
        val product = st.product ?: return
        val no = st.batchNo.trim()
        if (!st.tracksBatches || no.isEmpty() || st.checkingBatch) return
        _state.update { it.copy(checkingBatch = true) }
        viewModelScope.launch {
            val found = batchRepo.findBatch(product.id, no).getOrNull()
            _state.update { cur ->
                if (cur.batchNo.trim() != no) return@update cur.copy(checkingBatch = false)
                if (found == null) cur.copy(checkingBatch = false, existingBatch = null)
                else cur.copy(
                    checkingBatch = false,
                    existingBatch = found,
                    mfgDate = found.mfgDate,
                    expiryDate = found.expiryDate,
                    batchErrors = emptyMap()
                )
            }
        }
    }

    // ── Scanning ──────────────────────────────────────────────

    fun startScanning() = _state.update { st ->
        when {
            st.product == null -> st.copy(message = "Pick a product first")
            st.bin == null -> st.copy(message = "Pick a location first")
            else -> st.copy(phase = EnrollPhase.SCANNING, lastSummary = null)
        }
    }

    /** Back to setup from scanning, only when nothing is waiting to be saved. */
    fun backToSetup() = _state.update { st ->
        if (st.hasUnsavedPieces) st.copy(message = "Save or clear the scanned tags first")
        else st.copy(phase = EnrollPhase.SETUP, pieces = emptyList(), selectedEpc = null)
    }

    /** One tag from any reader. Ignored unless listening. */
    fun onTagScanned(rawEpc: String, tid: String = "") {
        val st = _state.value
        if (!st.isListening) return
        val epc = rawEpc.trim().uppercase()
        if (!isValidEpc(epc)) {
            _state.update { it.copy(message = "Not a tag: ${epc.take(24)}") }
            return
        }
        if (st.pieces.any { it.epc == epc }) return
        if (epc in handledEpcs) return

        val piece = PieceDraft(epc = epc, tid = tid.trim().uppercase())
        _state.update { cur ->
            if (cur.capture == CaptureMode.SINGLE) {
                // One piece: go straight to its form; the reader stops because we leave SCANNING.
                cur.copy(phase = EnrollPhase.DETAILS, pieces = listOf(piece), selectedEpc = epc, message = null)
            } else {
                cur.copy(pieces = cur.pieces + piece)
            }
        }
        lookUp(epc)
    }

    /** A whole read from a fixed reader / chamber. */
    fun onTagsReceived(epcs: List<String>) = epcs.forEach { onTagScanned(it) }

    private fun lookUp(epc: String) {
        viewModelScope.launch {
            val result = inventoryRepo.findUnitByEpc(epc)
            if (result.getOrNull() != null) handledEpcs += epc
            _state.update { cur ->
                val existing = result.getOrNull()
                when {
                    cur.pieces.none { it.epc == epc } -> cur   // removed meanwhile

                    existing != null && cur.capture == CaptureMode.SINGLE -> cur.copy(
                        phase = EnrollPhase.SCANNING,
                        pieces = emptyList(),
                        selectedEpc = null,
                        message = "Already enrolled as ${existing.sku.ifBlank { "a legacy piece" }} (${existing.status})"
                    )

                    existing != null -> cur.copy(pieces = cur.pieces.map {
                        if (it.epc == epc) it.copy(
                            status = PieceStatus.ALREADY_ENROLLED,
                            note = "Already enrolled as ${existing.sku.ifBlank { "a legacy piece" }}"
                        ) else it
                    })

                    else -> cur.copy(pieces = cur.pieces.map {
                        if (it.epc == epc) it.copy(
                            status = PieceStatus.READY,
                            // A failed check isn't fatal: enrollUnits re-checks inside its transaction.
                            note = if (result.isFailure) "Couldn't check now; will be checked on save" else ""
                        ) else it
                    })
                }
            }
        }
    }

    fun removePiece(epc: String) = _state.update { st ->
        val remaining = st.pieces.filterNot { it.epc == epc }
        when {
            st.phase == EnrollPhase.SAVING -> st
            st.capture == CaptureMode.SINGLE || remaining.none { it.isSaveable } ->
                st.copy(phase = EnrollPhase.SCANNING, pieces = emptyList(), selectedEpc = null)
            else -> st.copy(
                pieces = remaining,
                selectedEpc = if (st.selectedEpc == epc) remaining.firstOrNull { it.isSaveable }?.epc else st.selectedEpc
            )
        }
    }

    /** Batch mode: stop collecting and move on to the details. */
    fun doneScanning() = _state.update { st ->
        if (!st.canContinue) st
        else st.copy(
            phase = EnrollPhase.DETAILS,
            selectedEpc = st.pieces.firstOrNull { it.isSaveable }?.epc
        )
    }

    /** Batch mode: back to collecting more tags, keeping what's been typed. */
    fun scanMore() = _state.update { st ->
        if (st.phase != EnrollPhase.DETAILS || st.capture != CaptureMode.BATCH) st
        else st.copy(phase = EnrollPhase.SCANNING)
    }

    // ── Details ───────────────────────────────────────────────

    fun selectPiece(epc: String) = _state.update { it.copy(selectedEpc = epc) }

    fun onSerial(v: String) = editSelected { it.copy(serialNumber = v, errors = it.errors - CoreField.SERIAL_NUMBER.key) }

    fun onPrice(v: String) {
        val clean = v.filterIndexed { i, ch -> ch.isDigit() || (ch == '.' && !v.substring(0, i).contains('.')) }
        editSelected { it.copy(price = clean, errors = it.errors - CoreField.PRICE.key) }
    }

    fun onCustomValue(key: String, v: String) =
        editSelected { it.copy(customValues = it.customValues + (key to v), errors = it.errors - key) }

    /**
     * Copies the selected piece's value for [key] onto every other saveable piece.
     * [key] is "price" or a custom field key; serial numbers are per piece and can't be copied.
     */
    fun applyToAll(key: String) = _state.update { st ->
        val source = st.selectedPiece ?: return@update st
        if (key == CoreField.SERIAL_NUMBER.key) return@update st
        val pieces = st.pieces.map { p ->
            if (!p.isSaveable || p.epc == source.epc) p
            else if (key == CoreField.PRICE.key) p.copy(price = source.price, errors = p.errors - key)
            else p.copy(customValues = p.customValues + (key to source.customValues[key].orEmpty()), errors = p.errors - key)
        }
        val count = pieces.count { it.isSaveable } - 1
        st.copy(pieces = pieces, message = "Copied to $count other piece${if (count == 1) "" else "s"}")
    }

    private fun editSelected(change: (PieceDraft) -> PieceDraft) = _state.update { st ->
        val epc = st.selectedEpc ?: return@update st
        if (st.phase != EnrollPhase.DETAILS) return@update st
        st.copy(pieces = st.pieces.map { if (it.epc == epc && it.isSaveable) change(it) else it })
    }

    // ── Save ──────────────────────────────────────────────────

    fun save() {
        val st = _state.value
        if (!st.canSave) return
        val product = st.product ?: return
        val bin = st.bin ?: return

        val toSave = st.saveablePieces
        val validated = validatePieces(st, toSave)
        val pieces = st.pieces.map { p -> validated[p.epc]?.let { p.copy(errors = it.errors) } ?: p }
        val firstBad = pieces.firstOrNull { it.errors.isNotEmpty() }
        if (firstBad != null) {
            val bad = pieces.count { it.errors.isNotEmpty() }
            _state.update {
                it.copy(
                    pieces = pieces,
                    selectedEpc = firstBad.epc,
                    message = if (bad == 1) "Fix the highlighted fields" else "Fix the highlighted fields on $bad pieces"
                )
            }
            return
        }

        _state.update { it.copy(phase = EnrollPhase.SAVING, pieces = pieces, message = null) }

        viewModelScope.launch {
            // Batch: validated against the stored batch when it exists, else against the type's rules.
            val batch: Batch? = if (st.tracksBatches) {
                val outcome = resolveBatch(st, product)
                if (outcome.errors.isNotEmpty()) {
                    _state.update {
                        it.copy(phase = EnrollPhase.DETAILS, batchErrors = outcome.errors, message = outcome.errors.values.first())
                    }
                    return@launch
                }
                outcome.batch
            } else null

            val context = EnrollContext(
                product = product,
                warehouseId = bin.warehouseId,
                rackId = bin.rackId,
                binId = bin.id,
                binCode = bin.binCode,
                batch = batch,
                referenceType = "enrollment",
                notes = "Enrolled via ${st.reader.label}"
            )
            val inputs = toSave.map { validated.getValue(it.epc).input }

            inventoryRepo.enrollUnits(context, inputs).fold(
                onSuccess = { summary -> applySummary(summary, batch) },
                onFailure = { e ->
                    _state.update { it.copy(phase = EnrollPhase.DETAILS, message = e.message ?: "Save failed") }
                }
            )
        }
    }

    private fun validatePieces(st: UnitEnrollmentState, pieces: List<PieceDraft>): Map<String, ValidatedPiece> {
        val serialCounts = pieces
            .map { it.serialNumber.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .groupingBy { it }
            .eachCount()

        return pieces.associate { p ->
            val errors = mutableMapOf<String, String>()

            val serial = p.serialNumber.trim()
            if (serial.isEmpty() && st.isRequired(CoreField.SERIAL_NUMBER)) {
                errors[CoreField.SERIAL_NUMBER.key] = "Serial number is required"
            } else if (serial.isNotEmpty() && (serialCounts[serial.lowercase()] ?: 0) > 1) {
                errors[CoreField.SERIAL_NUMBER.key] = "Same serial number as another piece"
            }

            val priceText = p.price.trim()
            var price: Double? = null
            if (priceText.isNotEmpty()) {
                price = priceText.toDoubleOrNull()?.takeIf { it > 0 }
                if (price == null) errors[CoreField.PRICE.key] = "Enter a valid price"
            } else if (st.isRequired(CoreField.PRICE)) {
                errors[CoreField.PRICE.key] = "Price is required"
            }

            val custom = FieldValidator.validate(st.config, st.type, FieldLevel.UNIT, p.customValues)
            errors.putAll(custom.errors)

            p.epc to ValidatedPiece(
                input = EnrollUnitInput(
                    epc = p.epc,
                    tid = p.tid,
                    serialNumber = serial,
                    price = price,
                    customFields = custom.values
                ),
                errors = errors
            )
        }
    }

    private data class BatchOutcome(val batch: Batch?, val errors: Map<String, String>)

    private suspend fun resolveBatch(st: UnitEnrollmentState, product: Product): BatchOutcome {
        val no = st.batchNo.trim()
        val mfg = st.mfgDate.trim()
        val exp = st.expiryDate.trim()

        if (no.isEmpty()) {
            val errors = mutableMapOf<String, String>()
            if (st.isRequired(CoreField.BATCH_NO)) errors[CoreField.BATCH_NO.key] = "Batch number is required"
            else if (mfg.isNotEmpty() || exp.isNotEmpty()) errors[CoreField.BATCH_NO.key] = "Enter the batch number for these dates"
            return BatchOutcome(null, errors)
        }
        if (no.length > BatchRepository.BATCH_NO_MAX) {
            return BatchOutcome(null, mapOf(CoreField.BATCH_NO.key to "Batch number can be at most ${BatchRepository.BATCH_NO_MAX} characters"))
        }

        // An existing batch keeps its stored dates, so required-date rules apply only to new batches.
        val existing = st.existingBatch?.takeIf { it.batchKey == BatchRepository.batchKeyOf(no) }
            ?: batchRepo.findBatch(product.id, no).getOrNull()

        if (existing == null) {
            val errors = mutableMapOf<String, String>()
            if (mfg.isEmpty() && st.isRequired(CoreField.MFG_DATE)) errors[CoreField.MFG_DATE.key] = "Manufacturing date is required"
            if (exp.isEmpty() && st.isRequired(CoreField.EXPIRY_DATE)) errors[CoreField.EXPIRY_DATE.key] = "Expiry date is required"
            BatchDates.validate(mfg, exp)?.let { errors[KEY_BATCH_DATES] = it }
            if (errors.isNotEmpty()) return BatchOutcome(null, errors)
        }

        return batchRepo.findOrCreate(product.id, product.sku, no, mfg, exp).fold(
            onSuccess = { BatchOutcome(it, emptyMap()) },
            onFailure = { e -> BatchOutcome(null, mapOf(KEY_BATCH_DATES to (e.message ?: "Could not save the batch"))) }
        )
    }

    private fun applySummary(summary: EnrollSummary, batch: Batch?) {
        summary.results
            .filter { it.outcome == EnrollOutcome.ENROLLED || it.outcome == EnrollOutcome.ALREADY_ENROLLED }
            .forEach { handledEpcs += it.epc }
        applySummaryToState(summary, batch)
    }

    private fun applySummaryToState(summary: EnrollSummary, batch: Batch?) = _state.update { st ->
        val byEpc = summary.results.associateBy { it.epc }
        val pieces = st.pieces.map { p ->
            val r = byEpc[p.epc] ?: return@map p
            when (r.outcome) {
                EnrollOutcome.ENROLLED -> p.copy(status = PieceStatus.SAVED, note = "", errors = emptyMap())
                EnrollOutcome.ALREADY_ENROLLED -> p.copy(status = PieceStatus.ALREADY_ENROLLED, note = r.message)
                else -> p.copy(status = PieceStatus.FAILED, note = r.message)
            }
        }
        val total = st.sessionEnrolled + summary.enrolled
        val withBatch = st.copy(existingBatch = batch ?: st.existingBatch, batchErrors = emptyMap())

        withBatch.copy(
            phase = EnrollPhase.RESULT,
            pieces = pieces,
            selectedEpc = null,
            sessionEnrolled = total,
            lastSummary = summary
        )
    }

    // ── After a save ──────────────────────────────────────────

    /** Back to the form with only the failed pieces, to fix and retry. */
    fun retryFailed() = _state.update { st ->
        val failed = st.pieces.filter { it.status == PieceStatus.FAILED }
        if (failed.isEmpty()) st
        else st.copy(phase = EnrollPhase.DETAILS, pieces = failed, selectedEpc = failed.first().epc)
    }

    /** Same product, location and batch; ready for the next box or piece. */
    fun enrollMore() = _state.update {
        it.copy(phase = EnrollPhase.SCANNING, pieces = emptyList(), selectedEpc = null, lastSummary = null)
    }

    /** Back to setup to pick a different product, location or reader. */
    fun changeSetup() = _state.update {
        it.copy(phase = EnrollPhase.SETUP, pieces = emptyList(), selectedEpc = null, lastSummary = null)
    }
    /** Back pressed with unsaved tags, after the user confirmed: drop them and stop the reader. */
    fun discardAndSetup() = _state.update {
        it.copy(phase = EnrollPhase.SETUP, pieces = emptyList(), selectedEpc = null, lastSummary = null)
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    companion object {
        /** Error key for the batch's date combination (mfg vs expiry, or a mismatch with a stored batch). */
        const val KEY_BATCH_DATES = "batchDates"
    }
}

class UnitEnrollmentViewModelFactory(private val initialProductId: String? = null) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        UnitEnrollmentViewModel(initialProductId) as T
}