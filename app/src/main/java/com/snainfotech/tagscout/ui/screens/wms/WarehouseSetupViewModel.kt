package com.snainfotech.tagscout.ui.screens.wms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.snainfotech.tagscout.data.wms.Bin
import com.snainfotech.tagscout.data.wms.Rack
import com.snainfotech.tagscout.data.wms.Warehouse
import com.snainfotech.tagscout.data.wms.WarehouseRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class WarehouseSetupState(
    val isLoading: Boolean = false,
    val message: String? = null,

    val warehouses: List<Warehouse> = emptyList(),
    val selectedWarehouse: Warehouse? = null,

    val racks: List<Rack> = emptyList(),
    val selectedRack: Rack? = null,

    val bins: List<Bin> = emptyList()
)

class WarehouseSetupViewModel(
    private val repo: WarehouseRepository
) : ViewModel() {

    private val _state = MutableStateFlow(WarehouseSetupState())
    val state: StateFlow<WarehouseSetupState> = _state.asStateFlow()

    init { loadWarehouses() }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }

    // ── Warehouses ────────────────────────────────────────────

    fun loadWarehouses() {
        _state.value = _state.value.copy(isLoading = true)
        viewModelScope.launch {
            repo.getWarehouses().fold(
                onSuccess = { list ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        warehouses = list,
                        // Auto-select the first warehouse if there's exactly one
                        selectedWarehouse = _state.value.selectedWarehouse ?: list.firstOrNull()
                    )
                    // Cascade to load racks for the selected warehouse
                    _state.value.selectedWarehouse?.let { loadRacks(it) }
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        message = "Could not load warehouses: ${e.message}"
                    )
                }
            )
        }
    }

    fun createWarehouse(name: String, address: String) {
        if (name.isBlank()) {
            _state.value = _state.value.copy(message = "Warehouse name is required")
            return
        }
        _state.value = _state.value.copy(isLoading = true)
        viewModelScope.launch {
            repo.createWarehouse(name, address).fold(
                onSuccess = {
                    _state.value = _state.value.copy(message = "Warehouse created")
                    loadWarehouses()
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        message = "Could not create warehouse: ${e.message}"
                    )
                }
            )
        }
    }

    fun selectWarehouse(warehouse: Warehouse) {
        _state.value = _state.value.copy(
            selectedWarehouse = warehouse,
            selectedRack = null,
            racks = emptyList(),
            bins = emptyList()
        )
        loadRacks(warehouse)
    }

    // ── Racks ─────────────────────────────────────────────────

    private fun loadRacks(warehouse: Warehouse) {
        viewModelScope.launch {
            repo.getRacks(warehouse.id).fold(
                onSuccess = { list ->
                    _state.value = _state.value.copy(racks = list)
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(message = "Could not load racks: ${e.message}")
                }
            )
        }
    }

    fun createRack(name: String) {
        val warehouse = _state.value.selectedWarehouse ?: run {
            _state.value = _state.value.copy(message = "Select a warehouse first")
            return
        }
        if (name.isBlank()) {
            _state.value = _state.value.copy(message = "Rack name is required")
            return
        }
        viewModelScope.launch {
            repo.createRack(warehouse.id, name).fold(
                onSuccess = {
                    _state.value = _state.value.copy(message = "Rack created")
                    loadRacks(warehouse)
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(message = "Could not create rack: ${e.message}")
                }
            )
        }
    }

    fun selectRack(rack: Rack) {
        _state.value = _state.value.copy(selectedRack = rack, bins = emptyList())
        loadBins(rack)
    }

    // ── Bins ──────────────────────────────────────────────────

    private fun loadBins(rack: Rack) {
        viewModelScope.launch {
            repo.getBins(rack.warehouseId, rack.id).fold(
                onSuccess = { list ->
                    _state.value = _state.value.copy(bins = list)
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(message = "Could not load bins: ${e.message}")
                }
            )
        }
    }

    fun createBin(binCode: String, name: String) {
        val rack = _state.value.selectedRack ?: run {
            _state.value = _state.value.copy(message = "Select a rack first")
            return
        }
        if (binCode.isBlank()) {
            _state.value = _state.value.copy(message = "Bin code is required")
            return
        }
        viewModelScope.launch {
            repo.createBin(rack.warehouseId, rack.id, binCode, name).fold(
                onSuccess = {
                    _state.value = _state.value.copy(message = "Bin created")
                    loadBins(rack)
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(message = "Could not create bin: ${e.message}")
                }
            )
        }
    }
}

class WarehouseSetupViewModelFactory(
    private val repo: WarehouseRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return WarehouseSetupViewModel(repo) as T
    }
}