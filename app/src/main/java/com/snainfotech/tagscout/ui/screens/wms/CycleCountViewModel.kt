package com.snainfotech.tagscout.ui.screens.wms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.snainfotech.tagscout.data.wms.Bin
import com.snainfotech.tagscout.data.wms.CycleCountService
import com.snainfotech.tagscout.data.wms.Rack
import com.snainfotech.tagscout.data.wms.Warehouse
import com.snainfotech.tagscout.data.wms.WarehouseRepository
import com.snainfotech.tagscout.sdk.RfidScanner
import kotlinx.coroutines.Job
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

    // Scanning
    val scanning: Boolean = false,
    val scannedEpcs: Set<String> = emptySet(),

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
}

class CycleCountViewModel(
    private val scanner: RfidScanner,
    private val warehouseRepository: WarehouseRepository = WarehouseRepository(),
    private val service: CycleCountService = CycleCountService()
) : ViewModel() {

    private val _state = MutableStateFlow(CycleCountState())
    val state: StateFlow<CycleCountState> = _state.asStateFlow()

    private var scanJob: Job? = null

    // ── Step transitions ──────────────────────────────────────

    fun updateReference(v: String) {
        _state.value = _state.value.copy(reference = v)
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
        if (!_state.value.canProceedFromBin) {
            _state.value = _state.value.copy(message = "Pick a bin first")
            return
        }
        _state.value = _state.value.copy(
            step = CycleCountStep.SCANNING,
            scannedEpcs = emptySet()
        )
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
            warehouseRepository.getWarehouses().fold(
                onSuccess = { list ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        warehouses = list,
                        selectedWarehouse = _state.value.selectedWarehouse ?: list.firstOrNull()
                    )
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

    fun selectWarehouse(w: Warehouse) {
        _state.value = _state.value.copy(
            selectedWarehouse = w,
            selectedRack = null,
            selectedBin = null,
            racks = emptyList(),
            bins = emptyList()
        )
        loadRacks(w)
    }

    private fun loadRacks(w: Warehouse) {
        viewModelScope.launch {
            warehouseRepository.getRacks(w.id).fold(
                onSuccess = { list ->
                    _state.value = _state.value.copy(
                        racks = list,
                        selectedRack = list.firstOrNull()
                    )
                    _state.value.selectedRack?.let { loadBins(it) }
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(message = "Could not load racks: ${e.message}")
                }
            )
        }
    }

    fun selectRack(r: Rack) {
        _state.value = _state.value.copy(
            selectedRack = r,
            selectedBin = null,
            bins = emptyList()
        )
        loadBins(r)
    }

    private fun loadBins(r: Rack) {
        viewModelScope.launch {
            warehouseRepository.getBins(r.warehouseId, r.id).fold(
                onSuccess = { list ->
                    _state.value = _state.value.copy(
                        bins = list,
                        selectedBin = list.firstOrNull()
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(message = "Could not load bins: ${e.message}")
                }
            )
        }
    }

    fun selectBin(b: Bin) {
        _state.value = _state.value.copy(selectedBin = b)
    }

    // ── Scanning ──────────────────────────────────────────────

    fun startScanning() {
        if (_state.value.scanning) return
        _state.value = _state.value.copy(scanning = true)
        scanJob = viewModelScope.launch {
            scanner.startScanning().collect { tag ->
                // Accumulate unique EPCs. A tag being read multiple times
                // in the same session is normal; we care about unique presence.
                _state.value = _state.value.copy(
                    scannedEpcs = _state.value.scannedEpcs + tag.epc.trim().uppercase()
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
                        report = report
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        message = "Reconciliation failed: ${e.message}"
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