package com.snainfotech.tagscout.ui.screens.config

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.snainfotech.tagscout.data.repository.SettingsRepository

// Buzzer level options
enum class BuzzerLevel(val label: String) {
    LOW("LOW"),
    MEDIUM("MEDIUM"),
    HIGH("HIGH")
}

// Auto-sleep timeout options
enum class SleepTimeout(val label: String, val seconds: Int) {
    NEVER("Never", -1),
    THIRTY_SECONDS("30 Seconds", 30),
    FIVE_MINUTES("5 Minutes", 300),
    TEN_MINUTES("10 Minutes", 600)
}

// Reset or firmware update operation state
enum class OperationState {
    IDLE,
    CONFIRMING,
    IN_PROGRESS,
    SUCCESS,
    UP_TO_DATE
}

// Overall state for Device Config screen
data class DeviceConfigState(
    val buzzerLevel: BuzzerLevel = BuzzerLevel.MEDIUM,
    val sleepTimeout: SleepTimeout = SleepTimeout.FIVE_MINUTES,
    val firmwareVersion: String = "v5.90.00.02",
    val resetState: OperationState = OperationState.IDLE,
    val resetProgress: Int = 0,           // 0-100%
    val firmwareUpdateState: OperationState = OperationState.IDLE,
    val firmwareUpdateProgress: Int = 0,  // 0-100%
    val newFirmwareVersion: String = "",  // Version available
    val showSettingChangedToast: String = "" // Empty = no toast, otherwise the toast text
)

class DeviceConfigViewModel(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(DeviceConfigState())
    val state: StateFlow<DeviceConfigState> = _state.asStateFlow()
    init {
        loadSavedSettings()
    }

    private fun loadSavedSettings() {
        val savedBuzzerLabel = settingsRepository.getBuzzerLevel()
        val savedSleepLabel = settingsRepository.getSleepTimeout()

        // Convert labels back to enums
        val buzzerLevel = BuzzerLevel.values().find { it.label == savedBuzzerLabel } ?: BuzzerLevel.MEDIUM
        val sleepTimeout = SleepTimeout.values().find { it.label == savedSleepLabel } ?: SleepTimeout.FIVE_MINUTES

        _state.value = _state.value.copy(
            buzzerLevel = buzzerLevel,
            sleepTimeout = sleepTimeout
        )
    }
    // ============================================
    // BUZZER LEVEL
    // ============================================
    fun setBuzzerLevel(level: BuzzerLevel) {
        settingsRepository.setBuzzerLevel(level.label)   // Save to disk
        _state.value = _state.value.copy(
            buzzerLevel = level,
            showSettingChangedToast = "✓ Buzzer level changed to ${level.label}"
        )
        clearToastAfterDelay()
    }

    // ============================================
    // AUTO SLEEP TIMEOUT
    // ============================================
    fun setSleepTimeout(timeout: SleepTimeout) {
        settingsRepository.setSleepTimeout(timeout.label)   // Save to disk
        _state.value = _state.value.copy(
            sleepTimeout = timeout,
            showSettingChangedToast = "✓ Auto sleep set to ${timeout.label}"
        )
        clearToastAfterDelay()
    }

    private fun clearToastAfterDelay() {
        viewModelScope.launch {
            delay(2000)
            _state.value = _state.value.copy(showSettingChangedToast = "")
        }
    }

    // ============================================
    // RESET DEVICE FLOW
    // ============================================
    fun startResetConfirmation() {
        _state.value = _state.value.copy(resetState = OperationState.CONFIRMING)
    }

    fun confirmReset() {
        _state.value = _state.value.copy(
            resetState = OperationState.IN_PROGRESS,
            resetProgress = 0
        )

        viewModelScope.launch {
            // Simulate reset taking 5 seconds with progress updates
            for (progress in 0..100 step 5) {
                _state.value = _state.value.copy(resetProgress = progress)
                delay(250)
            }

            // Reset persisted settings to defaults
            settingsRepository.resetToDefaults()

            _state.value = _state.value.copy(
                resetState = OperationState.SUCCESS,
                buzzerLevel = BuzzerLevel.MEDIUM,
                sleepTimeout = SleepTimeout.FIVE_MINUTES
            )

            // Auto-dismiss success after 2 seconds
            delay(2000)
            dismissReset()
        }
    }

    fun dismissReset() {
        _state.value = _state.value.copy(
            resetState = OperationState.IDLE,
            resetProgress = 0
        )
    }

    // ============================================
    // FIRMWARE UPDATE FLOW
    // ============================================
    fun checkForFirmwareUpdate() {
        viewModelScope.launch {
            // Show checking spinner briefly
            _state.value = _state.value.copy(
                firmwareUpdateState = OperationState.IN_PROGRESS,
                firmwareUpdateProgress = 0
            )

            // Simulate network check (1.5 seconds)
            kotlinx.coroutines.delay(1500)

            // Randomly decide: 50/50 whether an update is available
            // (In real SDK integration, this would come from the device)
            val updateAvailable = kotlin.random.Random.nextBoolean()

            if (updateAvailable) {
                _state.value = _state.value.copy(
                    firmwareUpdateState = OperationState.CONFIRMING,
                    newFirmwareVersion = "v5.92.00.01"
                )
            } else {
                _state.value = _state.value.copy(
                    firmwareUpdateState = OperationState.UP_TO_DATE
                )
            }
        }
    }

    fun confirmFirmwareUpdate() {
        _state.value = _state.value.copy(
            firmwareUpdateState = OperationState.IN_PROGRESS,
            firmwareUpdateProgress = 0
        )

        viewModelScope.launch {
            // Simulate firmware update with progress
            for (progress in 0..100 step 2) {
                _state.value = _state.value.copy(firmwareUpdateProgress = progress)
                delay(100)
            }

            _state.value = _state.value.copy(
                firmwareUpdateState = OperationState.SUCCESS,
                firmwareVersion = _state.value.newFirmwareVersion
            )

            // Auto-dismiss success after 3 seconds
            delay(3000)
            dismissFirmwareUpdate()
        }
    }

    fun dismissFirmwareUpdate() {
        _state.value = _state.value.copy(
            firmwareUpdateState = OperationState.IDLE,
            firmwareUpdateProgress = 0,
            newFirmwareVersion = ""
        )
    }
}