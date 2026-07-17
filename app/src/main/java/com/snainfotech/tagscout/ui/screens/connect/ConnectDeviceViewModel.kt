package com.snainfotech.tagscout.ui.screens.connect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snainfotech.tagscout.data.entities.DeviceConnectionEntity
import com.snainfotech.tagscout.data.repository.DeviceRepository
import com.snainfotech.tagscout.sdk.RfidScanner
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch


// Status of each discovered device
enum class DeviceStatus {
    CONNECTED,      // Currently connected
    IN_RANGE,       // Available to connect/switch
    OUT_OF_RANGE,   // Saved but not nearby
    SEARCHING       // Being discovered
}

// A discovered device (saved or new)
data class DiscoveredDevice(
    val id: String,
    val name: String,
    val signalBars: Int,        // 0-4 bars
    val status: DeviceStatus,
    val isSaved: Boolean = false
)

// Overall state for the Connect Device screen
data class ConnectDeviceState(
    val isSearching: Boolean = false,
    val savedDevices: List<DiscoveredDevice> = emptyList(),
    val newDevices: List<DiscoveredDevice> = emptyList(),
    val currentlyConnectedId: String? = null,
    val searchQuery: String = "",
    val showPermissionRationale: Boolean = false,
    val showPermissionDeniedDialog: Boolean = false,
    val showDeleteConfirmation: DiscoveredDevice? = null,  // Device to delete (or null)
    val showLimitReachedDialog: Boolean = false
)

class ConnectDeviceViewModel(
    private val deviceRepository: DeviceRepository,
    private val rfidScanner: RfidScanner
) : ViewModel() {

    private val _state = MutableStateFlow(ConnectDeviceState())
    val state: StateFlow<ConnectDeviceState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        observeSavedDevices()
    }

    // Watch the database — auto-refresh saved devices list when DB changes
    private fun observeSavedDevices() {
        viewModelScope.launch {
            deviceRepository.getSavedDevices().collect { entities ->
                val currentConnectedId = _state.value.currentlyConnectedId

                val devices = entities.map { entity ->
                    // Determine status: keep CONNECTED for current device, default rest to IN_RANGE
                    val status = when {
                        entity.deviceId == currentConnectedId -> DeviceStatus.CONNECTED
                        else -> DeviceStatus.IN_RANGE
                    }

                    DiscoveredDevice(
                        id = entity.deviceId,
                        name = entity.deviceName,
                        signalBars = if (status == DeviceStatus.CONNECTED) 4 else 3,
                        status = status,
                        isSaved = true
                    )
                }

                _state.value = _state.value.copy(savedDevices = devices)
            }
        }
    }

    // Called from outside to sync with shared connected device state
    fun syncWithConnectedDevice(connectedDeviceId: String?) {
        val updatedSaved = _state.value.savedDevices.map { device ->
            when {
                device.id == connectedDeviceId -> device.copy(status = DeviceStatus.CONNECTED)
                device.status == DeviceStatus.CONNECTED -> device.copy(status = DeviceStatus.IN_RANGE)
                else -> device
            }
        }

        _state.value = _state.value.copy(
            savedDevices = updatedSaved,
            currentlyConnectedId = connectedDeviceId
        )
    }

    // Called when user taps Search.
    // Lists devices already paired via Android's own Bluetooth settings —
    // pair the sled there first; this doesn't do discovery/pairing itself.
    fun startSearch() {
        searchJob?.cancel()

        _state.value = _state.value.copy(
            isSearching = true,
            newDevices = emptyList()
        )

        searchJob = viewModelScope.launch {
            delay(500) // brief pause so the searching state is visibly shown

            val savedIds = _state.value.savedDevices.map { it.id }.toSet()
            val paired = rfidScanner.getPairedDevices()
                .filter { it.address !in savedIds }
                .map { device ->
                    DiscoveredDevice(
                        id = device.address,
                        name = device.name,
                        signalBars = 4, // not known until connected over classic BT
                        status = DeviceStatus.IN_RANGE,
                        isSaved = false
                    )
                }

            _state.value = _state.value.copy(
                isSearching = false,
                newDevices = paired
            )
        }
    }

    fun updateSearchQuery(query: String) {
        _state.value = _state.value.copy(searchQuery = query)
    }

    // Called when user taps a device to connect to it
    fun connectToDevice(device: DiscoveredDevice) {
        viewModelScope.launch {
            // If it's not saved, try to save it (with limit check)
            if (!device.isSaved) {
                val saved = deviceRepository.saveNewDevice(
                    deviceId = device.id,
                    deviceName = device.name,
                    serialNumber = "SN-${device.id}",
                    firmwareVersion = "v5.90.00.02"
                )

                if (!saved) {
                    // Limit reached — show dialog
                    _state.value = _state.value.copy(showLimitReachedDialog = true)
                    return@launch
                }

                // Remove from "new devices" list (it's now saved)
                val updatedNew = _state.value.newDevices.filter { it.id != device.id }
                _state.value = _state.value.copy(newDevices = updatedNew)
                // Saved devices list will auto-update via the Flow we're observing
            }

            // Mark this device as connected (the database observer will reflect it,
            // but we also update locally for immediate visual feedback)
            // Note: we don't mark this device as "Connected" here. That would be
            // optimistic — showing success before we actually know the real
            // BT_Connect() call below succeeded. The Connect Device screen's
            // status instead reflects the real hardware state via
            // syncWithConnectedDevice(), which reacts to genuine connection
            // events (see the LaunchedEffect in NavGraph watching deviceState).

            // device.id is the real Bluetooth address for real hardware
            // (see startSearch()) — this is what actually opens the connection.
            rfidScanner.connect(device.id)
        }
    }

    // Disconnects the currently connected device, if any (e.g. when it's deleted).
    fun disconnectCurrentDevice() {
        rfidScanner.disconnect()
    }

    // ============================================
    // DELETE / FORGET DEVICE
    // ============================================

    fun showDeleteConfirmation(device: DiscoveredDevice) {
        _state.value = _state.value.copy(showDeleteConfirmation = device)
    }

    fun dismissDeleteConfirmation() {
        _state.value = _state.value.copy(showDeleteConfirmation = null)
    }

    fun confirmDeleteDevice(device: DiscoveredDevice) {
        viewModelScope.launch {
            deviceRepository.forgetDeviceById(device.id)
            _state.value = _state.value.copy(showDeleteConfirmation = null)
            // List auto-updates via Flow
        }
    }

    // ============================================
    // LIMIT REACHED DIALOG
    // ============================================

    fun dismissLimitReachedDialog() {
        _state.value = _state.value.copy(showLimitReachedDialog = false)
    }

    // ============================================
    // PERMISSION DIALOGS
    // ============================================

    fun showPermissionRationale() {
        _state.value = _state.value.copy(showPermissionRationale = true)
    }

    fun dismissPermissionRationale() {
        _state.value = _state.value.copy(showPermissionRationale = false)
    }

    fun showPermissionDenied() {
        _state.value = _state.value.copy(showPermissionDeniedDialog = true)
    }

    fun dismissPermissionDenied() {
        _state.value = _state.value.copy(showPermissionDeniedDialog = false)
    }
}