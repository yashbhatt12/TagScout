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

    /**
     * Load warehouses and reconcile the current selection:
     *   - if the currently selected warehouse still exists, its fresh copy
     *     is picked up (so edits to name/address reflect immediately)
     *   - if it was deleted (or nothing was selected), fall back to list[0]
     *   - if the effective selection actually changed (was A, now B or null),
     *     downstream rack/bin state is cleared before cascading
     */
    fun loadWarehouses() {
        _state.value = _state.value.copy(isLoading = true)
        viewModelScope.launch {
            repo.getWarehouses().fold(
                onSuccess = { list ->
                    val currentId = _state.value.selectedWarehouse?.id
                    val newSelected = list.firstOrNull { it.id == currentId }
                        ?: list.firstOrNull()
                    val selectionChanged = newSelected?.id != currentId
                    _state.value = _state.value.copy(
                        isLoading = false,
                        warehouses = list,
                        selectedWarehouse = newSelected,
                        selectedRack = if (selectionChanged) null else _state.value.selectedRack,
                        racks = if (selectionChanged) emptyList() else _state.value.racks,
                        bins = if (selectionChanged) emptyList() else _state.value.bins
                    )
                    if (selectionChanged) newSelected?.let { loadRacks(it) }
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

    fun updateWarehouse(warehouseId: String, name: String, address: String) {
        if (name.isBlank()) {
            _state.value = _state.value.copy(message = "Warehouse name is required")
            return
        }
        viewModelScope.launch {
            repo.updateWarehouse(warehouseId, name, address).fold(
                onSuccess = {
                    _state.value = _state.value.copy(message = "Warehouse updated")
                    loadWarehouses()
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        message = e.message ?: "Could not update warehouse"
                    )
                }
            )
        }
    }

    /**
     * Delete a warehouse and everything under it. Repository enforces the
     * "no inventory anywhere under it" guard and cascades racks + bins on
     * success. loadWarehouses() then handles selection cleanup if we just
     * deleted the currently selected one.
     */
    fun deleteWarehouse(warehouse: Warehouse) {
        viewModelScope.launch {
            repo.deleteWarehouse(warehouse.id).fold(
                onSuccess = {
                    _state.value = _state.value.copy(message = "Warehouse deleted")
                    loadWarehouses()
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        message = e.message ?: "Could not delete warehouse"
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

    /**
     * Load racks for a warehouse. Preserves the selected rack across reloads
     * so edits are reflected; clears the selection (and bins) if the rack
     * was deleted underneath us.
     */
    private fun loadRacks(warehouse: Warehouse) {
        viewModelScope.launch {
            repo.getRacks(warehouse.id).fold(
                onSuccess = { list ->
                    val currentRackId = _state.value.selectedRack?.id
                    val newSelectedRack = list.firstOrNull { it.id == currentRackId }
                    val selectionChanged = newSelectedRack?.id != currentRackId
                    _state.value = _state.value.copy(
                        racks = list,
                        selectedRack = newSelectedRack,
                        bins = if (selectionChanged) emptyList() else _state.value.bins
                    )
                    if (selectionChanged) newSelectedRack?.let { loadBins(it) }
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

    fun updateRack(rackId: String, name: String) {
        val warehouse = _state.value.selectedWarehouse ?: run {
            _state.value = _state.value.copy(message = "Select a warehouse first")
            return
        }
        if (name.isBlank()) {
            _state.value = _state.value.copy(message = "Rack name is required")
            return
        }
        viewModelScope.launch {
            repo.updateRack(warehouse.id, rackId, name).fold(
                onSuccess = {
                    _state.value = _state.value.copy(message = "Rack updated")
                    loadRacks(warehouse)
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        message = e.message ?: "Could not update rack"
                    )
                }
            )
        }
    }

    fun deleteRack(rack: Rack) {
        val warehouse = _state.value.selectedWarehouse ?: run {
            _state.value = _state.value.copy(message = "Select a warehouse first")
            return
        }
        viewModelScope.launch {
            repo.deleteRack(warehouse.id, rack.id).fold(
                onSuccess = {
                    _state.value = _state.value.copy(message = "Rack deleted")
                    loadRacks(warehouse)
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        message = e.message ?: "Could not delete rack"
                    )
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

    fun updateBin(binId: String, name: String) {
        val rack = _state.value.selectedRack ?: run {
            _state.value = _state.value.copy(message = "Select a rack first")
            return
        }
        if (name.isBlank()) {
            _state.value = _state.value.copy(message = "Bin name is required")
            return
        }
        viewModelScope.launch {
            repo.updateBin(rack.warehouseId, rack.id, binId, name).fold(
                onSuccess = {
                    _state.value = _state.value.copy(message = "Bin updated")
                    loadBins(rack)
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        message = e.message ?: "Could not update bin"
                    )
                }
            )
        }
    }

    fun deleteBin(bin: Bin) {
        val rack = _state.value.selectedRack ?: run {
            _state.value = _state.value.copy(message = "Select a rack first")
            return
        }
        viewModelScope.launch {
            repo.deleteBin(rack.warehouseId, rack.id, bin.id).fold(
                onSuccess = {
                    _state.value = _state.value.copy(message = "Bin deleted")
                    loadBins(rack)
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        message = e.message ?: "Could not delete bin"
                    )
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