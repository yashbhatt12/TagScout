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

// A single item from the uploaded inventory file
data class InventoryItem(
    val id: Int,
    val epc: String,
    val tid: String,
    val productName: String,
    val binNumber: String? = null,         // Optional column
    val productGrouping: String? = null,   // Optional column
    val isFound: Boolean = false           // Will be true if scanned
)

// What info the Inventory brain remembers
data class InventoryScanState(
    val isScanning: Boolean = false,
    val isPaused: Boolean = false,
    val tagsScanned: Int = 0,               // Total tag reads (including duplicates)
    val uniqueTags: Int = 0,                // Unique tags detected
    val readPerSecond: Float = 0f,
    val expectedItems: Int = 0,             // Items in uploaded file
    val foundItems: Int = 0,                // Items matched
    val missingItems: Int = 0,              // Items not yet matched
    val progressPercent: Int = 0,
    val inventoryItems: List<InventoryItem> = emptyList(),
    val timeRemaining: Int = 180,
    val uploadedFilename: String = "",
    val showTimeWarning: Boolean = false,
    val hasFileLoaded: Boolean = false
)

class InventoryScanViewModel(
    private val repository: InventoryScanRepository
) : ViewModel() {

    private val _state = MutableStateFlow(InventoryScanState())
    val state: StateFlow<InventoryScanState> = _state.asStateFlow()

    private var timerJob: Job? = null

    // Track unique EPCs we've seen (for the stats counter)
    private val scannedEpcs = mutableSetOf<String>()

    // Called when user uploads a file (we'll wire up file parsing later)
    fun loadInventoryFile(items: List<InventoryItem>, filename: String) {
        _state.value = _state.value.copy(
            inventoryItems = items,
            expectedItems = items.size,
            missingItems = items.size,    // Initially, all items are "missing"
            foundItems = 0,
            progressPercent = 0,
            uploadedFilename = filename,
            hasFileLoaded = true
        )
    }

    // Called when user taps "Play" button
    fun startScanning() {
        _state.value = _state.value.copy(
            isScanning = true,
            isPaused = false,
            timeRemaining = 180
        )
        scannedEpcs.clear()
        startTimer()
    }

    // Called when user taps "Pause" button
    fun pauseScanning() {
        timerJob?.cancel()
        _state.value = _state.value.copy(
            isScanning = false,
            isPaused = true
        )
    }

    // Called when user taps "Resume" button
    fun resumeScanning() {
        _state.value = _state.value.copy(
            isScanning = true,
            isPaused = false
        )
        startTimer()
    }

    // Called by SDK when a tag is detected
    fun onTagDetected(epc: String, tid: String) {
        val currentState = _state.value

        // Track unique scans
        scannedEpcs.add(epc)

        // Try to match this tag against the inventory file
        val itemIndex = currentState.inventoryItems.indexOfFirst { item ->
            item.epc == epc || item.tid == tid
        }

        // Calculate read rate
        val timeElapsed = 180 - currentState.timeRemaining
        val rate = if (timeElapsed > 0) {
            currentState.tagsScanned.toFloat() / timeElapsed
        } else 0f

        if (itemIndex >= 0) {
            // ✓ Found a match — mark the item as found
            val currentItem = currentState.inventoryItems[itemIndex]

            // Only update if not already marked (avoid duplicate counting)
            if (!currentItem.isFound) {
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
                // Already marked as found, just update scan count
                _state.value = currentState.copy(
                    tagsScanned = currentState.tagsScanned + 1,
                    uniqueTags = scannedEpcs.size,
                    readPerSecond = rate
                )
            }
        } else {
            // Tag not in file — count it but don't mark anything
            _state.value = currentState.copy(
                tagsScanned = currentState.tagsScanned + 1,
                uniqueTags = scannedEpcs.size,
                readPerSecond = rate
            )
        }
    }

    // Called when user taps "Clear" button
    fun clearAllData() {
        timerJob?.cancel()
        scannedEpcs.clear()
        _state.value = InventoryScanState()
    }

    // Called when user taps "Save" button
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

    // Timer countdown
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
}