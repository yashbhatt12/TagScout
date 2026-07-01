package com.snainfotech.tagscout.ui.screens.inventory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snainfotech.tagscout.data.repository.InventoryScanRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModelProvider

// A single item from the uploaded inventory file
data class InventoryItem(
    val id: Int,
    val epc: String,
    val tid: String,
    val productName: String,
    val binNumber: String? = null,
    val productGrouping: String? = null,
    val isFound: Boolean = false
)

// A tag scanned that wasn't in the expected file
data class UnexpectedTag(
    val epc: String,
    val tid: String,
    val firstSeenSeconds: Int  // Seconds since scan started
)

// Tab selection
enum class InventoryTab {
    FILE_MAPPING,
    ADDITIONAL_TAGS
}

// What info the Inventory brain remembers
data class InventoryScanState(
    val isScanning: Boolean = false,
    val isPaused: Boolean = false,
    val tagsScanned: Int = 0,
    val uniqueTags: Int = 0,
    val readPerSecond: Float = 0f,
    val expectedItems: Int = 0,
    val foundItems: Int = 0,
    val missingItems: Int = 0,
    val progressPercent: Int = 0,
    val inventoryItems: List<InventoryItem> = emptyList(),
    val timeRemaining: Int = 180,
    val uploadedFilename: String = "",
    val showTimeWarning: Boolean = false,
    val hasFileLoaded: Boolean = false,
    val antennaStrength: Int = 5,
    val unexpectedTags: List<UnexpectedTag> = emptyList(),
    val selectedTab: InventoryTab = InventoryTab.FILE_MAPPING,
    val showDeviceDisconnectedDialog: Boolean = false
)

class InventoryScanViewModel(
    private val repository: InventoryScanRepository,
    private val scanner: com.snainfotech.tagscout.sdk.RfidScanner,
    private val settingsRepository: com.snainfotech.tagscout.data.repository.SettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(InventoryScanState())
    val state: StateFlow<InventoryScanState> = _state.asStateFlow()

    init {
        val savedAntenna = settingsRepository.getAntennaStrength()
        _state.value = _state.value.copy(antennaStrength = savedAntenna)
        scanner.setAntennaPower(savedAntenna)
    }
    private var timerJob: Job? = null
    private val scannedEpcs = mutableSetOf<String>()

    fun loadInventoryFile(items: List<InventoryItem>, filename: String) {
        _state.value = _state.value.copy(
            inventoryItems = items,
            expectedItems = items.size,
            missingItems = items.size,
            foundItems = 0,
            progressPercent = 0,
            uploadedFilename = filename,
            hasFileLoaded = true
        )
    }

    private var scanJob: Job? = null

    fun startScanning() {
        _state.value = _state.value.copy(
            isScanning = true,
            isPaused = false,
            timeRemaining = 180
        )
        scannedEpcs.clear()
        startTimer()
        startScanCollection()
    }

    private fun startScanCollection() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            scanner.startScanning().collect { tag ->
                if (!_state.value.isScanning) return@collect
                onTagDetected(tag.epc, tag.tid)
            }
        }
    }

    fun pauseScanning() {
        timerJob?.cancel()
        scanJob?.cancel()
        scanner.stopScanning()
        _state.value = _state.value.copy(
            isScanning = false,
            isPaused = true
        )
    }

    fun resumeScanning() {
        _state.value = _state.value.copy(
            isScanning = true,
            isPaused = false
        )
        startTimer()
        startScanCollection()
    }

    fun onTagDetected(epc: String, tid: String) {
        val currentState = _state.value
        scannedEpcs.add(epc)

        val itemIndex = currentState.inventoryItems.indexOfFirst { item ->
            item.epc == epc || item.tid == tid
        }

        val timeElapsed = 180 - currentState.timeRemaining
        val rate = if (timeElapsed > 0) {
            currentState.tagsScanned.toFloat() / timeElapsed
        } else 0f

        if (itemIndex >= 0) {
            // Match found in file
            val currentItem = currentState.inventoryItems[itemIndex]

            if (!currentItem.isFound) {
                // First time matching this item
                val updatedItems = currentState.inventoryItems.toMutableList()
                updatedItems[itemIndex] = currentItem.copy(isFound = true)

                val foundCount = updatedItems.count { it.isFound }
                val missingCount = updatedItems.size - foundCount
                val percent = if (updatedItems.size > 0) {
                    (foundCount * 100) / updatedItems.size
                } else 0

                _state.value = currentState.copy(
                    inventoryItems = updatedItems,
                    tagsScanned = currentState.tagsScanned + 1,
                    uniqueTags = scannedEpcs.size,
                    foundItems = foundCount,
                    missingItems = missingCount,
                    progressPercent = percent,
                    readPerSecond = rate
                )
            } else {
                // Already marked as found — just update scan count
                _state.value = currentState.copy(
                    tagsScanned = currentState.tagsScanned + 1,
                    uniqueTags = scannedEpcs.size,
                    readPerSecond = rate
                )
            }
        } else {
            // Tag not in file — capture as "additional" if first time seeing it
            val alreadyCaptured = currentState.unexpectedTags.any { it.epc == epc }

            if (!alreadyCaptured) {
                val timeElapsedSec = 180 - currentState.timeRemaining
                val newUnexpected = UnexpectedTag(
                    epc = epc,
                    tid = tid,
                    firstSeenSeconds = timeElapsedSec
                )
                _state.value = currentState.copy(
                    tagsScanned = currentState.tagsScanned + 1,
                    uniqueTags = scannedEpcs.size,
                    readPerSecond = rate,
                    unexpectedTags = currentState.unexpectedTags + newUnexpected
                )
            } else {
                // Already captured — just update scan count
                _state.value = currentState.copy(
                    tagsScanned = currentState.tagsScanned + 1,
                    uniqueTags = scannedEpcs.size,
                    readPerSecond = rate
                )
            }
        }
    }

    fun setAntennaStrength(strength: Int) {
        if (!_state.value.isScanning) {
            settingsRepository.setAntennaStrength(strength)
            _state.value = _state.value.copy(antennaStrength = strength)
            scanner.setAntennaPower(strength)
        }
    }
    fun selectTab(tab: InventoryTab) {
        _state.value = _state.value.copy(selectedTab = tab)
    }
    fun clearAllData() {
        timerJob?.cancel()
        scanJob?.cancel()
        scanner.stopScanning()
        scannedEpcs.clear()
        _state.value = InventoryScanState()
    }

    fun saveScan(filename: String) {
        viewModelScope.launch {
            val currentState = _state.value
            repository.saveScan(
                uploadedFilename = currentState.uploadedFilename,
                resultFilename = filename,
                expectedItems = currentState.expectedItems,
                foundItems = currentState.foundItems,
                missingItems = currentState.missingItems,
                matchPercentage = currentState.progressPercent.toFloat()
            )
        }
    }

    fun extendTimer() {
        _state.value = _state.value.copy(
            timeRemaining = _state.value.timeRemaining + 180,
            showTimeWarning = false
        )
    }

    fun dismissTimeWarning() {
        _state.value = _state.value.copy(showTimeWarning = false)
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (_state.value.isScanning && _state.value.timeRemaining > 0) {
                delay(1000)

                val newTime = _state.value.timeRemaining - 1
                _state.value = _state.value.copy(timeRemaining = newTime)

                if (newTime == 30) {
                    _state.value = _state.value.copy(showTimeWarning = true)
                }

                if (newTime == 0) {
                    pauseScanning()
                }
            }
        }
    }
    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
        scanJob?.cancel()
        scanner.stopScanning()
    }
// ============================================
    // DEVICE DISCONNECTED HANDLING (E11)
    // ============================================

    fun handleDeviceDisconnected() {
        // Auto-pause the scan
        timerJob?.cancel()
        scanJob?.cancel()
        scanner.stopScanning()

        _state.value = _state.value.copy(
            isScanning = false,
            isPaused = true,
            showDeviceDisconnectedDialog = true
        )
    }

    fun dismissDeviceDisconnectedDialog() {
        _state.value = _state.value.copy(showDeviceDisconnectedDialog = false)
    }
}
class InventoryScanViewModelFactory(
    private val repository: InventoryScanRepository,
    private val scanner: com.snainfotech.tagscout.sdk.RfidScanner,
    private val settingsRepository: com.snainfotech.tagscout.data.repository.SettingsRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(InventoryScanViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return InventoryScanViewModel(repository, scanner, settingsRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
