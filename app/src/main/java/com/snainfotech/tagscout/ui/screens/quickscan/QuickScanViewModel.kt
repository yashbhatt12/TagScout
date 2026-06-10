package com.snainfotech.tagscout.ui.screens.quickscan

import kotlinx.coroutines.flow.collect
import com.snainfotech.tagscout.sdk.RfidScanner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snainfotech.tagscout.data.repository.QuickScanRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// A single tag that was detected during scanning
data class DetectedTag(
    val epc: String,                // Tag's unique ID (like "3004A1B2C3D4E5F6")
    val signalStrength: Int,        // How strong the signal was (dBm, e.g., -45)
    val count: Int,                 // How many times this tag was detected
    val timestamp: Long             // When first detected
)

// What info the Quick Scan brain remembers
data class QuickScanState(
    val isScanning: Boolean = false,
    val isPaused: Boolean = false,
    val uniqueTags: Int = 0,
    val totalTags: Int = 0,
    val readPerSecond: Float = 0f,
    val antennaStrength: Int = 5,       // 1-10, default 5
    val timeRemaining: Int = 180,       // 3 minutes = 180 seconds
    val detectedTags: List<DetectedTag> = emptyList(),
    val showTimeWarning: Boolean = false,  // Show "30 seconds left" dialog
    val isTimerExpired: Boolean = false
)

class QuickScanViewModel(
    private val repository: QuickScanRepository,
    private val scanner: RfidScanner
) : ViewModel() {

    // Private: only this ViewModel can change state
    private val _state = MutableStateFlow(QuickScanState())

    // Public: screens can read this
    val state: StateFlow<QuickScanState> = _state.asStateFlow()

    // Reference to the running timer (so we can cancel it)
    private var timerJob: Job? = null
    private var sdkJob: Job? = null

    // Called when user taps "Play" button
    fun startScanning() {
        _state.value = _state.value.copy(
            isScanning = true,
            isPaused = false,
            timeRemaining = 180,
            isTimerExpired = false
        )
        startTimer()
        startSdkScan()
    }

    // Called when user taps "Pause" button
    fun pauseScanning() {
        timerJob?.cancel()
        scanner.stopScanning()
        sdkJob?.cancel()
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
        startSdkScan()
    }
    // Called when user moves the antenna slider (only allowed when NOT scanning)
    fun setAntennaStrength(strength: Int) {
        if (!_state.value.isScanning) {
            _state.value = _state.value.copy(antennaStrength = strength)
            scanner.setAntennaPower(strength)
        }
    }

    // Called by SDK when a tag is detected (we'll wire SDK up later)
    fun addDetectedTag(epc: String, signalStrength: Int) {
        val currentState = _state.value
        val existingTag = currentState.detectedTags.find { it.epc == epc }

        val updatedTags = if (existingTag != null) {
            // Tag already detected — increment its count
            currentState.detectedTags.map { tag ->
                if (tag.epc == epc) tag.copy(count = tag.count + 1) else tag
            }
        } else {
            // New tag — add to list
            currentState.detectedTags + DetectedTag(
                epc = epc,
                signalStrength = signalStrength,
                count = 1,
                timestamp = System.currentTimeMillis()
            )
        }

        // Calculate read rate (tags per second)
        val timeElapsed = 180 - currentState.timeRemaining
        val totalReads = updatedTags.sumOf { it.count }
        val rate = if (timeElapsed > 0) totalReads.toFloat() / timeElapsed else 0f

        _state.value = currentState.copy(
            detectedTags = updatedTags,
            uniqueTags = updatedTags.size,
            totalTags = totalReads,
            readPerSecond = rate
        )
    }

    // Called when user clicks "Clear" button
    fun clearAllData() {
        timerJob?.cancel()
        sdkJob?.cancel()
        scanner.stopScanning()
        _state.value = QuickScanState()
    }

    // Called when user clicks "Save" button
    fun saveScan(filename: String) {
        viewModelScope.launch {
            val currentState = _state.value
            repository.saveScan(
                filename = filename,
                uniqueTagsFound = currentState.uniqueTags,
                totalTagsScanned = currentState.totalTags,
                maxReadPerSecond = currentState.readPerSecond,
                durationSeconds = 180 - currentState.timeRemaining,
                antennaStrength = currentState.antennaStrength
            )
        }
    }

    // Called when user taps "Extend 3 Min" in the warning dialog
    fun extendTimer() {
        _state.value = _state.value.copy(
            timeRemaining = _state.value.timeRemaining + 180,
            showTimeWarning = false
        )
    }

    // Called when user dismisses the time warning dialog
    fun dismissTimeWarning() {
        _state.value = _state.value.copy(showTimeWarning = false)
    }

    // Timer countdown — runs in the background
    private fun startTimer() {
        timerJob?.cancel()  // Cancel any existing timer first
        timerJob = viewModelScope.launch {
            while (_state.value.isScanning && _state.value.timeRemaining > 0) {
                delay(1000)  // Wait 1 second

                val newTime = _state.value.timeRemaining - 1
                _state.value = _state.value.copy(timeRemaining = newTime)

                // Show warning at 30 seconds remaining
                if (newTime == 30) {
                    _state.value = _state.value.copy(showTimeWarning = true)
                }

                // Auto-pause when timer reaches 0
                if (newTime == 0) {
                    pauseScanning()
                    _state.value = _state.value.copy(isTimerExpired = true)
                }
            }
        }
    }
    // Listens to the SDK for detected tags and adds them to state
    private fun startSdkScan() {
        sdkJob?.cancel()  // Cancel any previous scan
        sdkJob = viewModelScope.launch {
            scanner.startScanning().collect { scannedTag ->
                addDetectedTag(scannedTag.epc, scannedTag.rssi)
            }
        }
    }
}