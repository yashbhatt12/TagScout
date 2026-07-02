package com.snainfotech.tagscout.ui.screens.pickorder

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

class PickOrderViewModel(
    private val scanner: RfidScanner,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(PickOrderState())
    val state: StateFlow<PickOrderState> = _state.asStateFlow()

    private var scanJob: Job? = null

    // Threshold: tag must be detected continuously for this long before "Confirm" appears
    private val proximityThresholdMs = 1000L  // 1 second

    // Tracks when each EPC was last seen (for proximity threshold logic)
    private val lastSeenTimestamps = mutableMapOf<String, Long>()

    init {
        val saved = settingsRepository.getAntennaStrength()
        _state.value = _state.value.copy(antennaStrength = saved)
        scanner.setAntennaPower(saved)
    }

    // ============================================
    // FILE LOADING
    // ============================================

    fun loadPickOrder(items: List<PickOrderItem>, filename: String) {
        _state.value = _state.value.copy(
            phase = PickOrderPhase.LOADED,
            items = items,
            orderFilename = filename,
            orderNumber = generateMockOrderNumber(),
            unexpectedItems = emptyList()
        )
    }

    // ============================================
    // SCANNING CONTROL
    // ============================================

    fun startPicking() {
        if (_state.value.items.isEmpty()) return

        _state.value = _state.value.copy(
            phase = PickOrderPhase.PICKING,
            isPicking = true
        )

        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            scanner.startScanning().collect { scannedTag ->
                onTagDetected(scannedTag.epc, scannedTag.rssi)
            }
        }
    }

    fun pausePicking() {
        scanner.stopScanning()
        scanJob?.cancel()
        _state.value = _state.value.copy(isPicking = false)

        // Clear all "currently detected" flags — user may have moved
        clearAllDetections()
    }

    fun resumePicking() {
        _state.value = _state.value.copy(isPicking = true)

        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            scanner.startScanning().collect { scannedTag ->
                onTagDetected(scannedTag.epc, scannedTag.rssi)
            }
        }
    }

    private fun clearAllDetections() {
        val cleared = _state.value.items.map { item ->
            item.copy(isDetected = false, firstDetectedAt = null, currentRssi = 0)
        }
        _state.value = _state.value.copy(items = cleared)
        lastSeenTimestamps.clear()
    }

    // ============================================
    // TAG DETECTION LOGIC
    // ============================================

    private fun onTagDetected(epc: String, rssi: Int) {
        val cleanEpc = epc.trim().uppercase()
        val now = System.currentTimeMillis()
        val currentState = _state.value

        // Find matching item in pick list
        val itemIndex = currentState.items.indexOfFirst { it.epc.equals(cleanEpc, ignoreCase = true) }

        if (itemIndex >= 0) {
            // EPC IS on the pick list — update its state
            handleExpectedTagDetection(itemIndex, rssi, now)
        } else {
            // EPC is NOT on the pick list — track as unexpected
            handleUnexpectedTagDetection(cleanEpc, now)
        }
    }

    private fun handleExpectedTagDetection(itemIndex: Int, rssi: Int, now: Long) {
        val currentState = _state.value
        val item = currentState.items[itemIndex]

        // If already picked, silently ignore (Q7 decision)
        if (item.isPicked) return

        // Track first-seen timestamp for proximity threshold
        val previousLastSeen = lastSeenTimestamps[item.epc]
        val firstDetectedAt = if (previousLastSeen == null || (now - previousLastSeen) > 2000L) {
            // Either never seen, or seen more than 2s ago (reset detection window)
            now
        } else {
            item.firstDetectedAt ?: now
        }

        lastSeenTimestamps[item.epc] = now

        // Update item's runtime state
        val updated = currentState.items.toMutableList()
        updated[itemIndex] = item.copy(
            isDetected = true,
            currentRssi = rssi,
            firstDetectedAt = firstDetectedAt
        )

        _state.value = currentState.copy(items = updated)
    }

    private fun handleUnexpectedTagDetection(epc: String, now: Long) {
        val currentState = _state.value

        // If already tracked, do nothing
        val alreadyTracked = currentState.unexpectedItems.any { it.epc == epc }
        if (alreadyTracked) return

        val newUnexpected = UnexpectedItem(
            epc = epc,
            firstDetectedAt = now
        )

        _state.value = currentState.copy(
            unexpectedItems = currentState.unexpectedItems + newUnexpected,
            showWrongEpcSnackbar = true,
            wrongEpcMessage = "Unexpected tag: ${epc.take(16)}..."
        )
    }

    // ============================================
    // PROXIMITY CHECK (called from UI periodically)
    // ============================================

    // Returns true if the item has been detected for at least the threshold time
    fun isItemReadyToConfirm(item: PickOrderItem): Boolean {
        if (!item.isDetected || item.firstDetectedAt == null) return false
        val timeDetected = System.currentTimeMillis() - item.firstDetectedAt
        return timeDetected >= proximityThresholdMs
    }

    // ============================================
    // USER ACTIONS
    // ============================================

    fun confirmPick(serialNo: Int) {
        val currentState = _state.value
        val itemIndex = currentState.items.indexOfFirst { it.serialNo == serialNo }
        if (itemIndex < 0) return

        val item = currentState.items[itemIndex]
        if (item.isPicked) return  // Already picked

        val updated = currentState.items.toMutableList()
        updated[itemIndex] = item.copy(
            isPicked = true,
            pickedAt = System.currentTimeMillis(),
            isDetected = false,       // Clear detection since it's now picked
            firstDetectedAt = null,
            currentRssi = 0
        )

        // Clear this EPC from proximity tracking
        lastSeenTimestamps.remove(item.epc)

        _state.value = currentState.copy(items = updated)

        // If all items picked, auto-transition to COMPLETE
        val newState = _state.value
        if (newState.items.all { it.isPicked }) {
            completeOrder()
        }
    }

    fun addUnexpectedToOrder(epc: String) {
        val currentState = _state.value
        val updated = currentState.unexpectedItems.map { unexp ->
            if (unexp.epc == epc) unexp.copy(includedInOrder = true) else unexp
        }
        _state.value = currentState.copy(
            unexpectedItems = updated,
            showWrongEpcSnackbar = false
        )
    }

    fun dismissWrongEpcSnackbar() {
        _state.value = _state.value.copy(showWrongEpcSnackbar = false)
    }

    fun setSelectedTab(tab: PickOrderTab) {
        _state.value = _state.value.copy(selectedTab = tab)
    }

    fun setAntennaStrength(strength: Int) {
        if (_state.value.isPicking) return  // Don't allow changes during active scan
        settingsRepository.setAntennaStrength(strength)
        _state.value = _state.value.copy(antennaStrength = strength)
        scanner.setAntennaPower(strength)
    }

    // ============================================
    // COMPLETION
    // ============================================

    fun completeOrder() {
        scanner.stopScanning()
        scanJob?.cancel()
        _state.value = _state.value.copy(
            phase = PickOrderPhase.COMPLETE,
            isPicking = false
        )
    }

    fun cancelOrder() {
        scanner.stopScanning()
        scanJob?.cancel()
        _state.value = PickOrderState(
            antennaStrength = _state.value.antennaStrength
        )
    }

    // ============================================
    // LOCATE MODE (activated for missing items — full impl in Phase E)
    // ============================================

    fun startLocateMode(epc: String) {
        _state.value = _state.value.copy(locateTargetEpc = epc)
        // Full locate scanner integration in Phase E
    }

    fun stopLocateMode() {
        _state.value = _state.value.copy(locateTargetEpc = null)
    }

    // ============================================
    // CLEANUP
    // ============================================

    override fun onCleared() {
        super.onCleared()
        scanner.stopScanning()
        scanJob?.cancel()
    }
}

// Factory
class PickOrderViewModelFactory(
    private val scanner: RfidScanner,
    private val settingsRepository: SettingsRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PickOrderViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return PickOrderViewModel(scanner, settingsRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}