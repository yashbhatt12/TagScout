package com.snainfotech.tagscout.ui.screens.wms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.snainfotech.tagscout.data.wms.Bin
import com.snainfotech.tagscout.data.wms.InventoryRepository
import com.snainfotech.tagscout.data.wms.InventorySkuAggregate
import com.snainfotech.tagscout.data.wms.InventoryUnit
import com.snainfotech.tagscout.data.wms.ProductRepository
import com.snainfotech.tagscout.data.wms.Rack
import com.snainfotech.tagscout.data.wms.Warehouse
import com.snainfotech.tagscout.data.wms.WarehouseRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The two views the dashboard toggles between.
 */
enum class DashboardView { BY_PRODUCT, BY_LOCATION }

data class WmsDashboardState(
    val currentView: DashboardView = DashboardView.BY_PRODUCT,
    val isLoading: Boolean = false,
    val message: String? = null,

    // ── By-Product view ─────────────
    // Enriched aggregates (with productTitle filled in from the product catalog)
    val aggregates: List<InventorySkuAggregate> = emptyList(),

    // ── By-Location view ────────────
    // Full warehouse → rack → bin structure, loaded once on screen open.
    // The visible racks/bins lists are filtered in memory from allRacks/allBins
    // whenever the user picks a different warehouse or rack — no Firestore
    // round-trip for dropdown changes.
    val warehouses: List<Warehouse> = emptyList(),
    val allRacks: List<Rack> = emptyList(),
    val allBins: List<Bin> = emptyList(),
    val selectedWarehouse: Warehouse? = null,
    val selectedRack: Rack? = null,
    val selectedBin: Bin? = null,
    val unitsInSelectedBin: List<InventoryUnit> = emptyList(),

    // ── Summary counters (shown on both views) ─────────────
    val totalSkuCount: Int = 0,
    val totalUnitCount: Int = 0
) {
    /** Racks under the currently selected warehouse. */
    val racks: List<Rack>
        get() = selectedWarehouse?.let { wh ->
            allRacks.filter { it.warehouseId == wh.id }
        } ?: emptyList()

    /** Bins under the currently selected rack. */
    val bins: List<Bin>
        get() = selectedRack?.let { rack ->
            allBins.filter { it.rackId == rack.id }
        } ?: emptyList()

    /**
     * The by-location view groups units in the selected bin by SKU
     * so the UI can render "SKU-1001 × 3" rows instead of listing
     * every individual EPC.
     */
    val unitsInBinGroupedBySku: List<Pair<String, Int>>
        get() = unitsInSelectedBin
            .groupBy { it.sku }
            .map { (sku, units) -> sku to units.size }
            .sortedBy { it.first }
}

class WmsDashboardViewModel(
    private val inventoryRepo: InventoryRepository,
    private val productRepo: ProductRepository,
    private val warehouseRepo: WarehouseRepository
) : ViewModel() {

    private val _state = MutableStateFlow(WmsDashboardState())
    val state: StateFlow<WmsDashboardState> = _state.asStateFlow()

    init {
        // Load both views' primary data in parallel on entry so switching
        // between tabs feels instant. Aggregates are small (one doc per SKU)
        // and the warehouse structure is small too, so pulling the whole lot
        // up front and filtering in memory is cheaper than chaining selects.
        loadAll()
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }

    fun switchView(view: DashboardView) {
        _state.value = _state.value.copy(currentView = view)
    }

    /** Manual refresh from the pull-to-refresh gesture or after external changes. */
    fun refresh() {
        loadAll()
    }

    // ── Combined load ───────────────────────────────────────
    //
    // Fires three independent reads in parallel:
    //   • SKU aggregates (for the by-product view)
    //   • Product catalog (to enrich aggregates with product titles)
    //   • Full warehouse structure (warehouses + all racks + all bins)
    //
    // Previously these ran in sequence with each dropdown change triggering
    // its own Firestore call. For a few dozen locations the old chain added
    // up to several seconds of waiting; this version loads everything in
    // roughly the time of the slowest single read.

    private fun loadAll() {
        _state.value = _state.value.copy(isLoading = true)
        viewModelScope.launch {
            val aggDeferred = async { inventoryRepo.getAllSkuAggregates() }
            val productDeferred = async { productRepo.getProducts() }
            val structureDeferred = async { warehouseRepo.getFullStructure() }

            val results = awaitAll(aggDeferred, productDeferred, structureDeferred)
            @Suppress("UNCHECKED_CAST")
            val aggResult = results[0] as Result<List<InventorySkuAggregate>>
            @Suppress("UNCHECKED_CAST")
            val productResult = results[1] as Result<List<com.snainfotech.tagscout.data.wms.Product>>
            @Suppress("UNCHECKED_CAST")
            val structureResult = results[2] as Result<WarehouseRepository.FullStructure>

            // ── By-Product view ─────────────
            val enrichedAggregates = aggResult.fold(
                onSuccess = { raw ->
                    val productsById = productResult.getOrDefault(emptyList())
                        .associateBy { it.id }
                    raw.filter { it.totalQuantity > 0 }
                        .map { agg ->
                            agg.copy(productTitle = productsById[agg.productId]?.title ?: "")
                        }
                },
                onFailure = {
                    _state.value = _state.value.copy(
                        message = "Could not load inventory: ${it.message}"
                    )
                    emptyList()
                }
            )

            // ── By-Location view ─────────────
            val structure = structureResult.getOrElse {
                _state.value = _state.value.copy(
                    message = (_state.value.message
                        ?: "Could not load warehouses: ${it.message}")
                )
                WarehouseRepository.FullStructure(emptyList(), emptyList(), emptyList())
            }

            // Preserve the user's selection if it still exists in the new
            // structure; otherwise fall back to the first available entry.
            val prev = _state.value
            val selectedWh = structure.warehouses.firstOrNull { it.id == prev.selectedWarehouse?.id }
                ?: structure.warehouses.firstOrNull()
            val racksInWh = selectedWh?.let { wh ->
                structure.racks.filter { it.warehouseId == wh.id }
            } ?: emptyList()
            val selectedRack = racksInWh.firstOrNull { it.id == prev.selectedRack?.id }
                ?: racksInWh.firstOrNull()
            val binsInRack = selectedRack?.let { rack ->
                structure.bins.filter { it.rackId == rack.id }
            } ?: emptyList()
            val selectedBin = binsInRack.firstOrNull { it.id == prev.selectedBin?.id }
                ?: binsInRack.firstOrNull()

            _state.value = _state.value.copy(
                isLoading = false,
                aggregates = enrichedAggregates,
                totalSkuCount = enrichedAggregates.size,
                totalUnitCount = enrichedAggregates.sumOf { it.totalQuantity },
                warehouses = structure.warehouses,
                allRacks = structure.racks,
                allBins = structure.bins,
                selectedWarehouse = selectedWh,
                selectedRack = selectedRack,
                selectedBin = selectedBin,
                // Clear old units if the auto-selected bin changed; the
                // follow-up loadUnitsInBin below will populate the new one.
                unitsInSelectedBin = if (selectedBin?.id == prev.selectedBin?.id) {
                    prev.unitsInSelectedBin
                } else {
                    emptyList()
                }
            )

            // Fetch units for the auto-selected bin so the screen isn't empty
            // on first open. User-driven bin changes go through selectBin().
            selectedBin?.let { bin ->
                if (bin.id != prev.selectedBin?.id || prev.unitsInSelectedBin.isEmpty()) {
                    loadUnitsInBin(bin)
                }
            }
        }
    }

    // ── Selection handlers (filter in memory) ────────────────

    fun selectWarehouse(warehouse: Warehouse) {
        val s = _state.value
        val racks = s.allRacks.filter { it.warehouseId == warehouse.id }
        val firstRack = racks.firstOrNull()
        val bins = firstRack?.let { r -> s.allBins.filter { it.rackId == r.id } } ?: emptyList()
        val firstBin = bins.firstOrNull()
        _state.value = s.copy(
            selectedWarehouse = warehouse,
            selectedRack = firstRack,
            selectedBin = firstBin,
            unitsInSelectedBin = emptyList()
        )
        firstBin?.let { loadUnitsInBin(it) }
    }

    fun selectRack(rack: Rack) {
        val s = _state.value
        val bins = s.allBins.filter { it.rackId == rack.id }
        val firstBin = bins.firstOrNull()
        _state.value = s.copy(
            selectedRack = rack,
            selectedBin = firstBin,
            unitsInSelectedBin = emptyList()
        )
        firstBin?.let { loadUnitsInBin(it) }
    }

    fun selectBin(bin: Bin) {
        _state.value = _state.value.copy(
            selectedBin = bin,
            unitsInSelectedBin = emptyList()
        )
        loadUnitsInBin(bin)
    }

    // ── The one call that still hits Firestore after structure load ───

    private fun loadUnitsInBin(bin: Bin) {
        _state.value = _state.value.copy(isLoading = true)
        viewModelScope.launch {
            inventoryRepo.getUnitsInBin(bin.id).fold(
                onSuccess = { list ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        unitsInSelectedBin = list
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        message = "Could not load units: ${e.message}"
                    )
                }
            )
        }
    }
}

class WmsDashboardViewModelFactory(
    private val inventoryRepo: InventoryRepository = InventoryRepository(),
    private val productRepo: ProductRepository = ProductRepository(),
    private val warehouseRepo: WarehouseRepository = WarehouseRepository()
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return WmsDashboardViewModel(inventoryRepo, productRepo, warehouseRepo) as T
    }
}