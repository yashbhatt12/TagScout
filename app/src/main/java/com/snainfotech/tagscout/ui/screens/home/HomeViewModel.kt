package com.snainfotech.tagscout.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snainfotech.tagscout.data.repository.DeviceRepository
import com.snainfotech.tagscout.sdk.ConnectionEvent
import com.snainfotech.tagscout.sdk.RfidScanner
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// What info the homepage brain remembers
data class DeviceState(
    val isConnected: Boolean = false,
    val deviceId: String = "",
    val deviceName: String = "",
    val serialNumber: String = "",
    val firmwareVersion: String = "",
    val batteryPercent: Int = 0,
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED
)

// Possible connection states (matches the 4 device status colors)
enum class ConnectionStatus {
    DISCONNECTED,    // Red - no device
    CONNECTED,       // Green - normal
    LOW_BATTERY,     // Orange - battery < 15%
    CHARGING         // Blue - device charging
}

class HomeViewModel(
    private val deviceRepository: DeviceRepository
) : ViewModel() {

    // Private: only this ViewModel can change the state
    private val _deviceState = MutableStateFlow(DeviceState())

    // Public: screens can read this but not modify
    val deviceState: StateFlow<DeviceState> = _deviceState.asStateFlow()

    private var connectionJob: Job? = null

    // ============================================
    // CONNECTION LIFECYCLE (Phase 2)
    // ============================================

    // Starts continuously observing the scanner's connection/battery stream,
    // replacing the old one-time status snapshot. Call this once a connect
    // action succeeds. Safe to call again on a new connection — cancels any
    // previous observation first.
    fun startObservingConnection(rfidScanner: RfidScanner) {
        connectionJob?.cancel()
        connectionJob = viewModelScope.launch {
            rfidScanner.connectionEvents().collect { event ->
                when (event) {
                    is ConnectionEvent.Connected -> updateDeviceStatus(
                        isConnected = true,
                        deviceId = event.address,
                        deviceName = event.deviceName,
                        serialNumber = event.serialNumber,
                        firmwareVersion = event.firmwareVersion,
                        batteryPercent = _deviceState.value.batteryPercent.takeIf { it > 0 } ?: 100
                    )
                    is ConnectionEvent.Disconnected -> updateDeviceStatus(
                        isConnected = false,
                        deviceId = "",
                        deviceName = "",
                        serialNumber = "",
                        firmwareVersion = "",
                        batteryPercent = 0
                    )
                    is ConnectionEvent.BatteryUpdate -> {
                        val current = _deviceState.value
                        updateDeviceStatus(
                            isConnected = current.isConnected,
                            deviceId = current.deviceId,
                            deviceName = current.deviceName,
                            serialNumber = current.serialNumber,
                            firmwareVersion = current.firmwareVersion,
                            batteryPercent = event.percent
                        )
                    }
                }
            }
        }
    }

    // Stops observing the connection stream — call on an explicit disconnect/delete,
    // so a stale battery tick can't override the disconnected status right after.
    fun stopObservingConnection() {
        connectionJob?.cancel()
        connectionJob = null
    }

    // Called when the device status changes (we'll wire this up later when SDK is added)
    fun updateDeviceStatus(
        isConnected: Boolean,
        deviceId: String = "",
        deviceName: String,
        serialNumber: String,
        firmwareVersion: String,
        batteryPercent: Int,
        isCharging: Boolean = false
    ) {
        // Decide which state to show based on the values
        val status = when {
            !isConnected -> ConnectionStatus.DISCONNECTED
            isCharging -> ConnectionStatus.CHARGING
            batteryPercent < 15 -> ConnectionStatus.LOW_BATTERY
            else -> ConnectionStatus.CONNECTED
        }

        // Update the state — all screens watching will automatically refresh
        _deviceState.value = DeviceState(
            isConnected = isConnected,
            deviceId = deviceId,
            deviceName = deviceName,
            serialNumber = serialNumber,
            firmwareVersion = firmwareVersion,
            batteryPercent = batteryPercent,
            connectionStatus = status
        )
    }
    // Called when user successfully connects to a device
    fun recordConnection(
        deviceId: String,
        deviceName: String,
        serialNumber: String,
        firmwareVersion: String
    ) {
        viewModelScope.launch {
            deviceRepository.recordConnection(
                deviceId = deviceId,
                deviceName = deviceName,
                serialNumber = serialNumber,
                firmwareVersion = firmwareVersion,
                isSaved = true
            )
        }
    }
}