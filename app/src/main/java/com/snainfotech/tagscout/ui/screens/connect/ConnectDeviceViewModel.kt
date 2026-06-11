package com.snainfotech.tagscout.ui.screens.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snainfotech.tagscout.data.repository.DeviceRepository
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
    val searchQuery: String = ""
)

class ConnectDeviceViewModel(
    private val deviceRepository: DeviceRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ConnectDeviceState())
    val state: StateFlow<ConnectDeviceState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        // Initialize with some mock saved devices
        loadInitialDevices()
    }

    private fun loadInitialDevices() {
        // Simulate having previously connected to some devices
        val mockSavedDevices = listOf(
            DiscoveredDevice("ABC123", "RFR-901 (ABC123)", 4, DeviceStatus.CONNECTED, true),
            DiscoveredDevice("XYZ789", "RFR-901 (XYZ789)", 3, DeviceStatus.IN_RANGE, true),
            DiscoveredDevice("DEF456", "RFR-900 (DEF456)", 0, DeviceStatus.OUT_OF_RANGE, true)
        )

        _state.value = _state.value.copy(
            savedDevices = mockSavedDevices,
            currentlyConnectedId = "ABC123"
        )
    }

    // Called when user taps the Search button
    fun startSearch() {
        searchJob?.cancel()

        _state.value = _state.value.copy(
            isSearching = true,
            newDevices = emptyList()
        )

        searchJob = viewModelScope.launch {
            // Simulate searching for 3 seconds
            delay(3000)

            // Add some "newly discovered" devices
            val newlyFound = listOf(
                DiscoveredDevice("NEW001", "RFR-901 (NEW001)", 3, DeviceStatus.IN_RANGE, false),
                DiscoveredDevice("NEW002", "RFR-900 (NEW002)", 2, DeviceStatus.IN_RANGE, false)
            )

            _state.value = _state.value.copy(
                isSearching = false,
                newDevices = newlyFound
            )
        }
    }

    // Called when user updates the search input
    fun updateSearchQuery(query: String) {
        _state.value = _state.value.copy(searchQuery = query)
    }

    // Called when user taps a device to connect/switch
    fun connectToDevice(device: DiscoveredDevice) {
        viewModelScope.launch {
            // Update connected status — old device becomes IN_RANGE, new device becomes CONNECTED
            val updatedSaved = _state.value.savedDevices.map { existing ->
                when {
                    existing.id == device.id -> existing.copy(status = DeviceStatus.CONNECTED)
                    existing.status == DeviceStatus.CONNECTED -> existing.copy(status = DeviceStatus.IN_RANGE)
                    else -> existing
                }
            }

            // If connecting to a new device, add it to saved list
            val newDeviceAlreadySaved = updatedSaved.any { it.id == device.id }
            val finalSavedList = if (!newDeviceAlreadySaved) {
                updatedSaved + device.copy(
                    isSaved = true,
                    status = DeviceStatus.CONNECTED
                )
            } else {
                updatedSaved
            }

            // Remove from new devices list if applicable
            val updatedNew = _state.value.newDevices.filter { it.id != device.id }

            _state.value = _state.value.copy(
                savedDevices = finalSavedList,
                newDevices = updatedNew,
                currentlyConnectedId = device.id
            )

            // Save to database
            deviceRepository.recordConnection(
                deviceId = device.id,
                deviceName = device.name,
                serialNumber = "SN-${device.id}",
                firmwareVersion = "v5.90.00.02",
                isSaved = true
            )
        }
    }
}