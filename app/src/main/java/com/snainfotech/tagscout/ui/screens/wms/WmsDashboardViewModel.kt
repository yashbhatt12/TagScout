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
    val warehouses: List<Warehouse> = emptyList(),
    val selectedWarehouse: Warehouse? = null,
    val racks: List<Rack> = emptyList(),
    val selectedRack: Rack? = null,
    val bins: List<Bin> = emptyList(),
    val selectedBin: Bin? = null,
    val unitsInSelectedBin: List<InventoryUnit> = emptyList(),

    // ── Summary counters (shown on both views) ─────────────
    val totalSkuCount: Int = 0,
    val totalUnitCount: Int = 0
) {
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
        // Load both views' primary data on entry so switching between
        // tabs feels instant. Aggregates are small (one doc per SKU) and
        // warehouses/racks/bins are small too, so this is cheap.
        loadByProductView()
        loadWarehouseTree()
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }

    fun switchView(view: DashboardView) {
        _state.value = _state.value.copy(currentView = view)
    }

    /** Manual refresh from the pull-to-refresh gesture or after external changes. */
    fun refresh() {
        loadByProductView()
        loadWarehouseTree()
        _state.value.selectedBin?.let { loadUnitsInBin(it) }
    }

    // ── By-Product view ─────────────────────────────────────

    private fun loadByProductView() {
        _state.value = _state.value.copy(isLoading = true)
        viewModelScope.launch {
            val aggResult = inventoryRepo.getAllSkuAggregates()
            val productResult = productRepo.getProducts()

            aggResult.fold(
                onSuccess = { rawAggregates ->
                    // Enrich each aggregate with the product's title so the UI
                    // can show a human-readable name next to the SKU.
                    val products = productResult.getOrDefault(emptyList())
                    val productById = products.associateBy { it.id }
                    val enriched = rawAggregates
                        .filter { it.totalQuantity > 0 }  // hide zero-count SKUs
                        .map { agg ->
                            agg.copy(
                                productTitle = productById[agg.productId]?.title ?: ""
                            )
                        }

                    val totalUnits = enriched.sumOf { it.totalQuantity }

                    _state.value = _state.value.copy(
                        isLoading = false,
                        aggregates = enriched,
                        totalSkuCount = enriched.size,
                        totalUnitCount = totalUnits
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        message = "Could not load inventory: ${e.message}"
                    )
                }
            )
        }
    }

    // ── By-Location view ────────────────────────────────────

    private fun loadWarehouseTree() {
        viewModelScope.launch {
            warehouseRepo.getWarehouses().fold(
                onSuccess = { list ->
                    _state.value = _state.value.copy(
                        warehouses = list,
                        selectedWarehouse = _state.value.selectedWarehouse ?: list.firstOrNull()
                    )
                    _state.value.selectedWarehouse?.let { loadRacks(it) }
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        message = "Could not load warehouses: ${e.message}"
                    )
                }
            )
        }
    }

    fun selectWarehouse(warehouse: Warehouse) {
        _state.value = _state.value.copy(
            selectedWarehouse = warehouse,
            selectedRack = null,
            selectedBin = null,
            racks = emptyList(),
            bins = emptyList(),
            unitsInSelectedBin = emptyList()
        )
        loadRacks(warehouse)
    }

    private fun loadRacks(warehouse: Warehouse) {
        viewModelScope.launch {
            warehouseRepo.getRacks(warehouse.id).fold(
                onSuccess = { list ->
                    _state.value = _state.value.copy(
                        racks = list,
                        selectedRack = list.firstOrNull()
                    )
                    _state.value.selectedRack?.let { loadBins(it) }
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        message = "Could not load racks: ${e.message}"
                    )
                }
            )
        }
    }

    fun selectRack(rack: Rack) {
        _state.value = _state.value.copy(
            selectedRack = rack,
            selectedBin = null,
            bins = emptyList(),
            unitsInSelectedBin = emptyList()
        )
        loadBins(rack)
    }

    private fun loadBins(rack: Rack) {
        viewModelScope.launch {
            warehouseRepo.getBins(rack.warehouseId, rack.id).fold(
                onSuccess = { list ->
                    _state.value = _state.value.copy(
                        bins = list,
                        selectedBin = list.firstOrNull()
                    )
                    _state.value.selectedBin?.let { loadUnitsInBin(it) }
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        message = "Could not load bins: ${e.message}"
                    )
                }
            )
        }
    }

    fun selectBin(bin: Bin) {
        _state.value = _state.value.copy(
            selectedBin = bin,
            unitsInSelectedBin = emptyList()
        )
        loadUnitsInBin(bin)
    }

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