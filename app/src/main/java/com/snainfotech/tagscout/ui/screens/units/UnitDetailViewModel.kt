package com.snainfotech.tagscout.ui.screens.units

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.snainfotech.tagscout.data.config.Capability
import com.snainfotech.tagscout.data.config.CompanyConfig
import com.snainfotech.tagscout.data.config.ConfigRepository
import com.snainfotech.tagscout.data.config.ConfigSource
import com.snainfotech.tagscout.data.config.CoreField
import com.snainfotech.tagscout.data.config.FieldDef
import com.snainfotech.tagscout.data.config.FieldLevel
import com.snainfotech.tagscout.data.config.FieldValidator
import com.snainfotech.tagscout.data.config.ProductTypeKey
import com.snainfotech.tagscout.data.config.UnitStatus
import com.snainfotech.tagscout.data.wms.Batch
import com.snainfotech.tagscout.data.wms.BatchRepository
import com.snainfotech.tagscout.data.wms.InventoryRepository
import com.snainfotech.tagscout.data.wms.InventoryUnit
import com.snainfotech.tagscout.data.wms.Product
import com.snainfotech.tagscout.data.wms.ProductRepository
import com.snainfotech.tagscout.data.wms.isValidEpc
import com.snainfotech.tagscout.data.wms.type
import com.snainfotech.tagscout.data.wms.unitStatus
import com.snainfotech.tagscout.ui.screens.enrollment.ReaderMode
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

enum class UnitLookupPhase {
    LOOKUP,     // waiting for a tag or a search
    LOADING,    // reads in flight
    FOUND,      // a unit is open
    NOT_FOUND,  // last read / search matched nothing
    PICK        // a serial search matched several units
}

data class UnitDetailState(
    val config: CompanyConfig = CompanyConfig.DEFAULT,
    val phase: UnitLookupPhase = UnitLookupPhase.LOOKUP,
    /** Only the sled and the desktop pad make sense for looking up one tag. */
    val reader: ReaderMode = ReaderMode.SLED,
    val query: String = "",
    val lastSearched: String = "",
    val matches: List<InventoryUnit> = emptyList(),

    val unit: InventoryUnit? = null,
    val product: Product? = null,
    val batch: Batch? = null,

    // Editing
    val editing: Boolean = false,
    val serialNumber: String = "",
    val price: String = "",
    val customValues: Map<String, String> = emptyMap(),
    val errors: Map<String, String> = emptyMap(),
    val savingDetails: Boolean = false,

    // Status change
    val pendingStatus: UnitStatus? = null,
    val statusNote: String = "",
    val savingStatus: Boolean = false,

    val message: String? = null
) {
    /** Pilot pieces have no product: treated as jewellery, everything else as general. */
    val type: ProductTypeKey
        get() = product?.type()
            ?: if (unit?.category == LEGACY_CATEGORY) ProductTypeKey.JEWELLERY else ProductTypeKey.GENERAL

    val typeLabel: String get() = config.typeDef(type).label

    val status: UnitStatus? get() = unit?.unitStatus()

    /** Unit-level fields defined for this type, in display order. */
    val unitFields: List<FieldDef> get() = config.fieldsFor(type, FieldLevel.UNIT)

    /** Stored custom values whose field is no longer active for this type. */
    val otherValues: List<Pair<String, String>>
        get() {
            val known = unitFields.map { it.key }.toSet()
            return unit?.customFields.orEmpty()
                .filter { (k, v) -> k !in known && v != null && v.toString().isNotBlank() }
                .map { (k, v) -> k to v.toString() }
                .sortedBy { it.first.lowercase() }
        }

    fun isRequired(field: CoreField): Boolean = config.isCoreRequired(type, field)

    val showPrice: Boolean
        get() = unit?.price != null ||
                config.hasCapability(type, Capability.RETAIL_SALE) ||
                isRequired(CoreField.PRICE) ||
                (config.source == ConfigSource.DEFAULTS && type == ProductTypeKey.JEWELLERY)

    val showSerial: Boolean
        get() = !unit?.serialNumber.isNullOrBlank() ||
                type == ProductTypeKey.IT_ASSET || type == ProductTypeKey.NON_IT_ASSET ||
                isRequired(CoreField.SERIAL_NUMBER)

    /** Statuses a person may move this unit to from here (current one excluded). */
    val statusChoices: List<UnitStatus>
        get() {
            val current = status ?: return emptyList()
            if (current == UnitStatus.RETAGGED) return emptyList()
            return config.selectableStatuses(type).filter { it != current && it !in FLOW_ONLY_STATUSES }
        }

    val canEdit: Boolean get() = unit != null && status != UnitStatus.RETAGGED

    /** NavGraph / the screen run the reader exactly while this is true. */
    val isListening: Boolean
        get() = phase == UnitLookupPhase.LOOKUP || phase == UnitLookupPhase.NOT_FOUND

    companion object {
        const val LEGACY_CATEGORY = "jewellery"

        /** Set by their own workflows (assignment, picking, dispatch), not by hand here. */
        val FLOW_ONLY_STATUSES = setOf(UnitStatus.ASSIGNED, UnitStatus.PICKED, UnitStatus.DISPATCHED)
    }
}

/**
 * Look up one unit by tag or serial number, view everything about it,
 * edit its own details and change its status.
 *
 * Reader-agnostic like enrollment: the sled (via NavGraph) and the desktop
 * pad (keystrokes captured by the screen) call [onTagScanned].
 */
class UnitDetailViewModel(
    private val initialEpc: String? = null,
    private val inventoryRepo: InventoryRepository = InventoryRepository(),
    private val productRepo: ProductRepository = ProductRepository(),
    private val batchRepo: BatchRepository = BatchRepository()
) : ViewModel() {

    private val _state = MutableStateFlow(UnitDetailState())
    val state: StateFlow<UnitDetailState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            ConfigRepository.config.collect { cfg -> _state.update { it.copy(config = cfg) } }
        }
        initialEpc?.takeIf { it.isNotBlank() }?.let { load(it.trim().uppercase()) }
    }

    // ── Reader and search ─────────────────────────────────────

    fun setReader(mode: ReaderMode) = _state.update {
        if (mode == ReaderMode.FIXED) it else it.copy(reader = mode)
    }

    fun onTagScanned(rawEpc: String) {
        if (!_state.value.isListening) return
        val epc = rawEpc.trim().uppercase()
        if (!isValidEpc(epc)) {
            _state.update { it.copy(message = "Not a tag: ${epc.take(24)}") }
            return
        }
        load(epc)
    }

    fun onQuery(v: String) = _state.update { it.copy(query = v) }

    /** A full EPC opens that unit; anything else is searched as a serial number. */
    fun search() {
        val q = _state.value.query.trim()
        if (q.isEmpty()) return
        if (isValidEpc(q) && q.length >= MIN_EPC_SEARCH_LENGTH) {
            load(q.uppercase())
            return
        }
        _state.update { it.copy(phase = UnitLookupPhase.LOADING, lastSearched = q, matches = emptyList()) }
        viewModelScope.launch {
            inventoryRepo.findUnitsBySerial(q).fold(
                onSuccess = { units ->
                    when (units.size) {
                        0 -> _state.update { it.copy(phase = UnitLookupPhase.NOT_FOUND) }
                        1 -> load(units.first().epc)
                        else -> _state.update {
                            it.copy(phase = UnitLookupPhase.PICK, matches = units.sortedBy { u -> u.sku })
                        }
                    }
                },
                onFailure = { e ->
                    _state.update { it.copy(phase = UnitLookupPhase.LOOKUP, message = "Search failed: ${e.message}") }
                }
            )
        }
    }

    fun pickMatch(epc: String) = load(epc)

    /** Close the open unit and go back to waiting for a tag or search. */
    fun clear() = _state.update {
        UnitDetailState(config = it.config, reader = it.reader)
    }

    // ── Load ──────────────────────────────────────────────────

    private fun load(epc: String) {
        _state.update {
            it.copy(phase = UnitLookupPhase.LOADING, lastSearched = epc, editing = false, pendingStatus = null)
        }
        viewModelScope.launch {
            val unit = inventoryRepo.getUnitFull(epc).getOrElse { e ->
                _state.update { it.copy(phase = UnitLookupPhase.LOOKUP, message = "Lookup failed: ${e.message}") }
                return@launch
            }
            if (unit == null) {
                _state.update { it.copy(phase = UnitLookupPhase.NOT_FOUND, unit = null, product = null, batch = null) }
                return@launch
            }

            val productJob = async {
                if (unit.productId.isBlank()) null else productRepo.getProduct(unit.productId).getOrNull()
            }
            val batchJob = async {
                if (unit.batchId.isBlank()) null else batchRepo.getBatch(unit.batchId).getOrNull()
            }
            val product = productJob.await()
            val batch = batchJob.await()

            _state.update {
                it.copy(
                    phase = UnitLookupPhase.FOUND,
                    unit = unit,
                    product = product,
                    batch = batch,
                    matches = emptyList(),
                    query = "",
                    errors = emptyMap()
                ).withEditFieldsFrom(unit)
            }
        }
    }

    private fun UnitDetailState.withEditFieldsFrom(unit: InventoryUnit) = copy(
        serialNumber = unit.serialNumber,
        price = unit.price?.let { toInputText(it) } ?: "",
        customValues = unit.customFields.mapValues { (_, v) -> toInputText(v) }
    )

    /** Stored value → text for an input box. */
    private fun toInputText(value: Any?): String = when (value) {
        null -> ""
        is Double -> if (value % 1.0 == 0.0) value.toLong().toString()
        else String.format(Locale.ENGLISH, "%.3f", value).trimEnd('0').trimEnd('.')
        else -> value.toString()
    }

    // ── Edit details ──────────────────────────────────────────

    fun startEdit() = _state.update { if (it.canEdit) it.copy(editing = true, pendingStatus = null) else it }

    fun cancelEdit() = _state.update { st ->
        val unit = st.unit ?: return@update st
        st.copy(editing = false, errors = emptyMap()).withEditFieldsFrom(unit)
    }

    fun onSerial(v: String) = _state.update { it.copy(serialNumber = v, errors = it.errors - CoreField.SERIAL_NUMBER.key) }

    fun onPrice(v: String) {
        val clean = v.filterIndexed { i, ch -> ch.isDigit() || (ch == '.' && !v.substring(0, i).contains('.')) }
        _state.update { it.copy(price = clean, errors = it.errors - CoreField.PRICE.key) }
    }

    fun onCustomValue(key: String, v: String) =
        _state.update { it.copy(customValues = it.customValues + (key to v), errors = it.errors - key) }

    fun saveDetails() {
        val st = _state.value
        val unit = st.unit ?: return
        if (!st.editing || st.savingDetails) return

        val errors = mutableMapOf<String, String>()
        val serial = st.serialNumber.trim()
        if (serial.isEmpty() && st.isRequired(CoreField.SERIAL_NUMBER)) {
            errors[CoreField.SERIAL_NUMBER.key] = "Serial number is required"
        }

        val priceText = st.price.trim()
        var price: Double? = null
        if (priceText.isNotEmpty()) {
            price = priceText.toDoubleOrNull()?.takeIf { it > 0 }
            if (price == null) errors[CoreField.PRICE.key] = "Enter a valid price"
        } else if (st.isRequired(CoreField.PRICE)) {
            errors[CoreField.PRICE.key] = "Price is required"
        }

        // Only active fields are re-checked; values of inactive fields are kept as stored.
        val custom = FieldValidator.validate(
            config = st.config,
            type = st.type,
            level = FieldLevel.UNIT,
            input = st.customValues.filterKeys { k -> st.unitFields.any { it.key == k } },
            previous = unit.customFields
        )
        errors.putAll(custom.errors)

        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors, message = "Fix the highlighted fields") }
            return
        }

        _state.update { it.copy(savingDetails = true) }
        viewModelScope.launch {
            inventoryRepo.updateUnitDetails(unit.epc, serial, price, custom.values).fold(
                onSuccess = {
                    _state.update { it.copy(savingDetails = false, editing = false, message = "Details saved") }
                    load(unit.epc)
                },
                onFailure = { e ->
                    _state.update { it.copy(savingDetails = false, message = "Save failed: ${e.message}") }
                }
            )
        }
    }

    // ── Status ────────────────────────────────────────────────

    fun chooseStatus(status: UnitStatus?) = _state.update {
        if (status != null && status !in it.statusChoices) it
        else it.copy(pendingStatus = status, statusNote = "", editing = false)
    }

    fun onStatusNote(v: String) = _state.update { it.copy(statusNote = v.take(MAX_NOTE_LENGTH)) }

    fun confirmStatusChange() {
        val st = _state.value
        val unit = st.unit ?: return
        val target = st.pendingStatus ?: return
        if (st.savingStatus) return

        _state.update { it.copy(savingStatus = true) }
        viewModelScope.launch {
            inventoryRepo.changeUnitStatus(unit.epc, target, st.statusNote).fold(
                onSuccess = {
                    _state.update {
                        it.copy(savingStatus = false, pendingStatus = null, statusNote = "", message = "Status changed to ${target.displayName}")
                    }
                    load(unit.epc)
                },
                onFailure = { e ->
                    _state.update { it.copy(savingStatus = false, message = e.message ?: "Status change failed") }
                }
            )
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private companion object {
        /** Shorter hex strings are treated as serial numbers, not EPCs. */
        const val MIN_EPC_SEARCH_LENGTH = 16
        const val MAX_NOTE_LENGTH = 200
    }
}

class UnitDetailViewModelFactory(private val initialEpc: String? = null) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        UnitDetailViewModel(initialEpc) as T
}