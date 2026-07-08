package com.snainfotech.tagscout.ui.screens.orderpicking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.snainfotech.tagscout.data.repository.SettingsRepository
import com.snainfotech.tagscout.sdk.RfidScanner
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * "High dB" / close-proximity threshold, in dBm.
 *
 * FakeRfidScanner's simulated RSSI is roughly (-65 + antennaPower * 3) ± 5, so at the
 * default antenna power (5) that's about -55..-45, and at max power (10) about -40..-30.
 * -48 sits near the top of the default-power range and comfortably inside the
 * higher-power range, so a tag genuinely has to be "loud" (close) to trigger a pause —
 * tune this constant if real hardware calibration needs a different cutoff.
 */
private const val HIGH_SIGNAL_RSSI_THRESHOLD = -48

class OrderPickingViewModel(
    private val rfidScanner: RfidScanner,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(
        OrderPickingState(antennaPower = settingsRepository.getAntennaStrength())
    )
    val state: StateFlow<OrderPickingState> = _state.asStateFlow()

    private var scanJob: Job? = null

    // ============================================
    // FILE LOAD
    // ============================================

    fun beginFileParse() {
        _state.value = _state.value.copy(isParsingFile = true, fileError = null)
    }

    fun onFileParsed(fileName: String, items: List<OrderPickingItem>) {
        _state.value = OrderPickingState(
            fileName = fileName,
            hasFileLoaded = true,
            items = items,
            antennaPower = settingsRepository.getAntennaStrength()
        )
    }

    fun onFileParseFailed(message: String) {
        _state.value = _state.value.copy(isParsingFile = false, fileError = message)
    }

    fun dismissFileError() {
        _state.value = _state.value.copy(fileError = null)
    }

    // ============================================
    // SCANNING
    // ============================================

    fun startScanning() {
        if (_state.value.isScanning) return
        _state.value = _state.value.copy(isScanning = true, isPaused = false)

        scanJob = viewModelScope.launch {
            rfidScanner.startScanning().collect { tag ->
                // Ignore reads while a candidate is already awaiting confirmation,
                // or once every item has already been picked
                val current = _state.value
                if (current.proximityCandidate != null || current.allPicked) return@collect

                val matchedItem = current.items.firstOrNull {
                    !it.picked && it.epc.equals(tag.epc, ignoreCase = true)
                } ?: return@collect

                if (tag.rssi >= HIGH_SIGNAL_RSSI_THRESHOLD) {
                    // Strong/close signal on a not-yet-picked item — pause and ask for confirmation
                    pauseForProximityMatch(ProximityCandidate(tag.epc, tag.rssi, matchedItem))
                }
            }
        }
    }

    private fun pauseForProximityMatch(candidate: ProximityCandidate) {
        rfidScanner.stopScanning()
        scanJob?.cancel()
        _state.value = _state.value.copy(
            isScanning = false,
            isPaused = true,
            proximityCandidate = candidate
        )
    }

    fun pauseScanning() {
        rfidScanner.stopScanning()
        scanJob?.cancel()
        _state.value = _state.value.copy(isScanning = false, isPaused = true)
    }

    fun resumeScanning() {
        _state.value = _state.value.copy(proximityCandidate = null)
        startScanning()
    }

    // ============================================
    // PICK CONFIRMATION
    // ============================================

    fun confirmPick() {
        val candidate = _state.value.proximityCandidate ?: return
        val updatedItems = _state.value.items.map { item ->
            if (item.rowIndex == candidate.item.rowIndex) {
                item.copy(picked = true, pickedAtMillis = System.currentTimeMillis())
            } else item
        }
        _state.value = _state.value.copy(items = updatedItems, proximityCandidate = null)

        // Automatically continue scanning for the next item, unless the order is now complete
        if (_state.value.allPicked) {
            pauseScanning()
        } else {
            startScanning()
        }
    }

    fun dismissProximityCandidate() {
        // User declined to confirm this one right now — drop it and keep scanning
        _state.value = _state.value.copy(proximityCandidate = null)
        startScanning()
    }

    // ============================================
    // ANTENNA
    // ============================================

    fun setAntennaStrength(power: Int) {
        rfidScanner.setAntennaPower(power)
        settingsRepository.setAntennaStrength(power)
        _state.value = _state.value.copy(antennaPower = power)
    }

    // ============================================
    // CLEAR
    // ============================================

    fun requestClear() {
        _state.value = _state.value.copy(showClearWarningDialog = true)
    }

    fun cancelClearRequest() {
        _state.value = _state.value.copy(showClearWarningDialog = false)
    }

    fun confirmClear() {
        rfidScanner.stopScanning()
        scanJob?.cancel()
        _state.value = OrderPickingState(antennaPower = settingsRepository.getAntennaStrength())
    }

    // ============================================
    // SAVE
    // ============================================

    fun beginSave() {
        _state.value = _state.value.copy(isSaving = true, saveError = null, saveCompleted = false)
    }

    fun onSaveSucceeded() {
        _state.value = _state.value.copy(isSaving = false, saveCompleted = true)
    }

    fun onSaveFailed(message: String) {
        _state.value = _state.value.copy(isSaving = false, saveError = message)
    }

    fun dismissSaveError() {
        _state.value = _state.value.copy(saveError = null)
    }

    fun acknowledgeSaveCompleted() {
        // Saving is the natural end of an order — reset to a clean slate afterward
        confirmClear()
    }

    // ============================================
    // DISCONNECT HANDLING
    // ============================================

    fun handleDeviceDisconnected() {
        rfidScanner.stopScanning()
        scanJob?.cancel()
        _state.value = _state.value.copy(
            isScanning = false,
            isPaused = true,
            showDeviceDisconnectedDialog = true
        )
    }

    fun dismissDeviceDisconnectedDialog() {
        _state.value = _state.value.copy(showDeviceDisconnectedDialog = false)
    }

    override fun onCleared() {
        super.onCleared()
        rfidScanner.stopScanning()
        scanJob?.cancel()
    }
}

class OrderPickingViewModelFactory(
    private val rfidScanner: RfidScanner,
    private val settingsRepository: SettingsRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return OrderPickingViewModel(rfidScanner, settingsRepository) as T
    }
}