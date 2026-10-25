package com.snainfotech.tagscout.ui.screens.wms
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.snainfotech.tagscout.data.wms.Bin
import com.snainfotech.tagscout.data.wms.CycleCountService
import com.snainfotech.tagscout.data.wms.InventoryRepository
import com.snainfotech.tagscout.data.wms.ProductRepository
import com.snainfotech.tagscout.data.wms.Rack
import com.snainfotech.tagscout.data.wms.Warehouse
import com.snainfotech.tagscout.data.wms.WarehouseRepository
import com.snainfotech.tagscout.sdk.RfidScanner
import com.snainfotech.tagscout.ui.screens.inventory.InventoryItem
import com.snainfotech.tagscout.ui.screens.inventory.InventoryTab
import com.snainfotech.tagscout.ui.screens.inventory.UnexpectedTag
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The steps of a Cycle Count session.
 */
enum class CycleCountStep {
    ENTER_REF,   // enter the CC reference number
    PICK_BIN,    // pick warehouse -> rack -> bin
    SCANNING,    // sled trigger reads tags into the session
    REVIEW,      // show reconciliation (matched / missing / unexpected)
    DONE         // summary after commit
}

data class CycleCountState(
    val step: CycleCountStep = CycleCountStep.ENTER_REF,
    val reference: String = "",
    val message: String? = null,
    val isLoading: Boolean = false,

    // Bin picker
    val warehouses: List<Warehouse> = emptyList(),
    val selectedWarehouse: Warehouse? = null,
    val racks: List<Rack> = emptyList(),
    val selectedRack: Rack? = null,
    val bins: List<Bin> = emptyList(),
    val selectedBin: Bin? = null,

    // Full warehouse structure (loaded once); the visible racks/bins
    // lists above are filtered from these in memory.
    val allRacks: List<Rack> = emptyList(),
    val allBins: List<Bin> = emptyList(),
    // Bin codes that currently hold at least one unit, derived from the
    // SKU-aggregate byLocation map. Used to hide empty bins without one
    // count query per bin.
    val nonEmptyBinCodes: Set<String> = emptySet(),

    // Scanning
    val scanning: Boolean = false,
    val scannedEpcs: Set<String> = emptySet(),
    val firstSeenAt: Map<String, Long> = emptyMap(),   // epc -> wall-clock millis when first seen
    val scanStartedAt: Long = 0L,

    // Expected set, loaded when the bin is picked. Each item is one inventory_unit.
    val expectedItems: List<InventoryItem> = emptyList(),
    val expectedLoadFailed: Boolean = false,

    // Live unexpected list (scanned EPCs not in expectedItems). knownLocation
    // is filled in when entering REVIEW, by looking each one up in inventory_units.
    val unexpectedTags: List<UnexpectedTag> = emptyList(),

    val selectedTab: InventoryTab = InventoryTab.FILE_MAPPING,

    // Review
    val report: CycleCountService.ReconciliationReport? = null,

    // Done
    val committed: Boolean = false,
    val movementsWritten: Int = 0,
    val hadNoDiscrepancies: Boolean = false
) {
    val canProceedFromRef get() = reference.isNotBlank()
    val canProceedFromBin get() = selectedBin != null
    val scannedCount get() = scannedEpcs.size

    // Derived counters for the Match Progress strip
    val expectedCount get() = expectedItems.size
    val expectedFoundCount get() = expectedItems.count { it.isFound }
}

class CycleCountViewModel(
    private val scanner: RfidScanner,
    private val warehouseRepository: WarehouseRepository = WarehouseRepository(),
    private val service: CycleCountService = CycleCountService(),
    private val inventoryRepository: InventoryRepository = InventoryRepository(),
    private val productRepository: ProductRepository = ProductRepository()
) : ViewModel() {

    private val _state = MutableStateFlow(CycleCountState())
    val state: StateFlow<CycleCountState> = _state.asStateFlow()

    private var scanJob: Job? = null

    // ── Step transitions ──────────────────────────────────────

    fun updateReference(v: String) {
        _state.value = _state.value.copy(reference = v)
    }
    fun selectTab(tab: com.snainfotech.tagscout.ui.screens.inventory.InventoryTab) {
        _state.value = _state.value.copy(selectedTab = tab)
    }
    fun proceedToPickBin() {
        if (!_state.value.canProceedFromRef) {
            _state.value = _state.value.copy(message = "Enter a reference number first")
            return
        }
        _state.value = _state.value.copy(step = CycleCountStep.PICK_BIN)
        loadWarehouses()
    }

    fun proceedToScanning() {
        val bin = _state.value.selectedBin
        if (bin == null) {
            _state.value = _state.value.copy(message = "Pick a bin first")
            return
        }
        _state.value = _state.value.copy(
            step = CycleCountStep.SCANNING,
            scannedEpcs = emptySet(),
            firstSeenAt = emptyMap(),
            unexpectedTags = emptyList(),
            expectedItems = emptyList(),
            expectedLoadFailed = false,
            selectedTab = InventoryTab.FILE_MAPPING,
            isLoading = true
        )
        fun selectTab(tab: InventoryTab) {
            _state.value = _state.value.copy(selectedTab = tab)
        }
        viewModelScope.launch {
            val unitsResult = inventoryRepository.getUnitsInBin(bin.id)
            val productsResult = productRepository.getProducts()

            val units = unitsResult.getOrNull().orEmpty()
            val productTitleBySku = productsResult.getOrNull()
                .orEmpty()
                .associate { it.sku to it.title }

            val items = units.mapIndexed { idx, u ->
                InventoryItem(
                    id = idx,
                    epc = u.epc,
                    tid = "",
                    productName = productTitleBySku[u.sku] ?: u.sku,
                    binNumber = u.currentBinCode,
                    productGrouping = u.sku,
                    isFound = false
                )
            }

            _state.value = _state.value.copy(
                expectedItems = items,
                expectedLoadFailed = unitsResult.isFailure,
                isLoading = false
            )
        }
    }

    fun backToPickBin() {
        stopScanningInternal()
        _state.value = _state.value.copy(
            step = CycleCountStep.PICK_BIN,
            scannedEpcs = emptySet(),
            scanning = false
        )
    }

    // ── Bin picker ────────────────────────────────────────────
    private fun loadWarehouses() {
        _state.value = _state.value.copy(isLoading = true)
        viewModelScope.launch {
            // Fetch the full warehouse tree and the SKU aggregates in
            // parallel. Aggregates tell us which bin codes currently hold
            // stock — that replaces the per-bin / per-rack / per-warehouse
            // count queries that previously ran one after another.
            val structureDeferred = async { warehouseRepository.getFullStructure() }
            val aggDeferred = async { inventoryRepository.getAllSkuAggregates() }
            val results = awaitAll(structureDeferred, aggDeferred)
            @Suppress("UNCHECKED_CAST")
            val structureResult = results[0] as Result<WarehouseRepository.FullStructure>
            @Suppress("UNCHECKED_CAST")
            val aggResult = results[1] as Result<List<com.snainfotech.tagscout.data.wms.InventorySkuAggregate>>

            val structure = structureResult.getOrElse {
                _state.value = _state.value.copy(
                    isLoading = false,
                    message = "Could not load warehouses: ${it.message}"
                )
                return@launch
            }

            // byLocation keys look like "warehouseId/binCode". Extract the
            // binCode half; that's what we match against Bin.binCode below.
            val nonEmptyBinCodes = aggResult.getOrDefault(emptyList())
                .flatMap { it.byLocation.keys }
                .mapNotNull { key ->
                    val slash = key.indexOf('/')
                    if (slash >= 0 && slash < key.length - 1) key.substring(slash + 1) else null
                }
                .toSet()

            // Which bins hold stock → which racks and warehouses are non-empty.
            val nonEmptyBins = structure.bins.filter { it.binCode in nonEmptyBinCodes }
            val nonEmptyRackIds = nonEmptyBins.map { it.rackId }.toSet()
            val nonEmptyWarehouseIds = structure.racks
                .filter { it.id in nonEmptyRackIds }
                .map { it.warehouseId }
                .toSet()
            val visibleWarehouses = structure.warehouses.filter { it.id in nonEmptyWarehouseIds }

            val prevSelected = _state.value.selectedWarehouse
            val selectedWh = visibleWarehouses.firstOrNull { it.id == prevSelected?.id }
                ?: visibleWarehouses.firstOrNull()
            val racksForWh = selectedWh?.let { wh ->
                structure.racks.filter { it.warehouseId == wh.id && it.id in nonEmptyRackIds }
            } ?: emptyList()
            val selectedRack = racksForWh.firstOrNull()
            val binsForRack = selectedRack?.let { rack ->
                structure.bins.filter { it.rackId == rack.id && it.binCode in nonEmptyBinCodes }
            } ?: emptyList()
            val selectedBin = binsForRack.firstOrNull()

            _state.value = _state.value.copy(
                isLoading = false,
                warehouses = visibleWarehouses,
                allRacks = structure.racks,
                allBins = structure.bins,
                nonEmptyBinCodes = nonEmptyBinCodes,
                selectedWarehouse = selectedWh,
                racks = racksForWh,
                selectedRack = selectedRack,
                bins = binsForRack,
                selectedBin = selectedBin
            )
        }
    }

    fun selectWarehouse(w: Warehouse) {
        val s = _state.value
        val racksForWh = s.allRacks.filter { it.warehouseId == w.id }
        val visibleRacks = racksForWh.filter { rack ->
            s.allBins.any { it.rackId == rack.id && it.binCode in s.nonEmptyBinCodes }
        }
        val firstRack = visibleRacks.firstOrNull()
        val binsForRack = firstRack?.let { r ->
            s.allBins.filter { it.rackId == r.id && it.binCode in s.nonEmptyBinCodes }
        } ?: emptyList()
        val firstBin = binsForRack.firstOrNull()
        _state.value = s.copy(
            selectedWarehouse = w,
            racks = visibleRacks,
            selectedRack = firstRack,
            bins = binsForRack,
            selectedBin = firstBin
        )
    }



    fun selectRack(r: Rack) {
        val s = _state.value
        val binsForRack = s.allBins.filter { it.rackId == r.id && it.binCode in s.nonEmptyBinCodes }
        val firstBin = binsForRack.firstOrNull()
        _state.value = s.copy(
            selectedRack = r,
            bins = binsForRack,
            selectedBin = firstBin
        )
    }

    fun selectBin(b: Bin) {
        _state.value = _state.value.copy(selectedBin = b)
    }

    // ── Scanning ──────────────────────────────────────────────

    fun startScanning() {
        if (_state.value.scanning) return
        runCatching { scanner.setAntennaPower(20) }

        val now = System.currentTimeMillis()
        val cur = _state.value
        _state.value = cur.copy(
            scanning = true,
            // Keep scanStartedAt from the first start of this session so
            // Resume doesn't reset the "first seen" clock.
            scanStartedAt = if (cur.scanStartedAt == 0L) now else cur.scanStartedAt
        )

        scanJob = viewModelScope.launch {
            scanner.startScanning().collect { tag ->
                val epc = tag.epc.trim().uppercase()
                val s = _state.value
                if (epc in s.scannedEpcs) return@collect // ignore repeat reads

                val newScanned = s.scannedEpcs + epc
                val newFirstSeen = s.firstSeenAt + (epc to System.currentTimeMillis())

                val isExpected = s.expectedItems.any { it.epc.equals(epc, ignoreCase = true) }
                val newExpected = if (isExpected) {
                    s.expectedItems.map { if (it.epc.equals(epc, ignoreCase = true)) it.copy(isFound = true) else it }
                } else s.expectedItems

                val newUnexpected = if (!isExpected) {
                    val elapsed = ((System.currentTimeMillis() - s.scanStartedAt) / 1000).toInt()
                    s.unexpectedTags + UnexpectedTag(epc = epc, tid = "", firstSeenSeconds = elapsed)
                } else s.unexpectedTags

                _state.value = s.copy(
                    scannedEpcs = newScanned,
                    firstSeenAt = newFirstSeen,
                    expectedItems = newExpected,
                    unexpectedTags = newUnexpected
                )
            }
        }
    }

    fun pauseScanning() {
        stopScanningInternal()
        _state.value = _state.value.copy(scanning = false)
    }

    /**
     * Stop the session and move to the reconciliation review step.
     */
    fun stopAndReview() {
        stopScanningInternal()
        _state.value = _state.value.copy(scanning = false, isLoading = true)

        val bin = _state.value.selectedBin
        val warehouse = _state.value.selectedWarehouse
        val rack = _state.value.selectedRack
        if (bin == null || warehouse == null || rack == null) {
            _state.value = _state.value.copy(
                isLoading = false,
                message = "Session state incomplete — please restart the count"
            )
            return
        }

        viewModelScope.launch {
            // Resolve where each unexpected tag actually lives, so Review can show
            // "At RNG-001 • MW-R2-B01" instead of just an EPC.
            val resolvedUnexpected = _state.value.unexpectedTags.map { tag ->
                val lookup = runCatching { inventoryRepository.getUnitByEpc(tag.epc) }
                    .getOrNull()?.getOrNull()
                val location = when {
                    lookup == null -> null
                    lookup.currentBinCode.isNotBlank() ->
                        "${lookup.sku.ifBlank { "Unknown SKU" }} • ${lookup.currentBinCode}"
                    else -> lookup.sku.ifBlank { null }
                }
                tag.copy(knownLocation = location)
            }

            service.reconcile(
                binId = bin.id,
                binCode = bin.binCode,
                warehouseId = warehouse.id,
                rackId = rack.id,
                scannedEpcs = _state.value.scannedEpcs
            ).fold(
                onSuccess = { report ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        step = CycleCountStep.REVIEW,
                        report = report,
                        unexpectedTags = resolvedUnexpected
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        message = "Reconciliation failed: ${e.message}",
                        unexpectedTags = resolvedUnexpected
                    )
                }
            )
        }
    }

    private fun stopScanningInternal() {
        scanJob?.cancel()
        scanJob = null
        scanner.stopScanning()
    }

    // ── Commit ────────────────────────────────────────────────

    fun commitReconciliation() {
        val report = _state.value.report ?: return
        val ref = _state.value.reference
        _state.value = _state.value.copy(isLoading = true)

        viewModelScope.launch {
            when (val result = service.commitReconciliation(report, ref)) {
                is CycleCountService.CommitResult.Success -> {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        step = CycleCountStep.DONE,
                        committed = true,
                        movementsWritten = result.movementsWritten,
                        hadNoDiscrepancies = false
                    )
                }
                is CycleCountService.CommitResult.NothingToCommit -> {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        step = CycleCountStep.DONE,
                        committed = true,
                        movementsWritten = 0,
                        hadNoDiscrepancies = true
                    )
                }
                is CycleCountService.CommitResult.Failure -> {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        message = result.error
                    )
                }
            }
        }
    }

    fun reset() {
        stopScanningInternal()
        _state.value = CycleCountState()
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    override fun onCleared() {
        super.onCleared()
        stopScanningInternal()
    }
}

class CycleCountViewModelFactory(
    private val scanner: RfidScanner
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return CycleCountViewModel(scanner) as T
    }
}