package com.snainfotech.tagscout.ui.screens.locate

import android.content.Context
import android.media.AudioAttributes
import android.media.ToneGenerator
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.snainfotech.tagscout.sdk.RfidScanner
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

data class LocateTagState(
    val targetEpc: String = "",
    val isLocating: Boolean = false,
    val proximity: Int = 0,        // 0-100, higher = closer
    val statusMessage: String = "Enter an EPC to start locating",
    val error: String? = null
)

class LocateTagViewModel(
    private val scanner: RfidScanner
) : ViewModel() {

    private val _state = MutableStateFlow(LocateTagState())
    val state: StateFlow<LocateTagState> = _state.asStateFlow()

    private var locateJob: Job? = null
    private var beepJob: Job? = null
    private var toneGenerator: ToneGenerator? = null

    // Antenna power for locate mode — low power limits the read range to ~5m
    // so the user only hears beeps when physically near the target tag.
    // SDK valid range is 5-30 dBm; 10 dBm gives roughly 3-5m depending on
    // the tag and environment.
    private val LOCATE_ANTENNA_POWER = 25

    fun updateTargetEpc(value: String) {
        val filtered = value.filter { it.isLetterOrDigit() && it.uppercaseChar() in "0123456789ABCDEF" }
        _state.value = _state.value.copy(
            targetEpc = filtered.uppercase(),
            error = null
        )
    }

    fun startLocating() {
        val epc = _state.value.targetEpc.trim()
        if (epc.length < 16) {
            _state.value = _state.value.copy(error = "EPC must be at least 16 hex characters")
            return
        }

        // Set antenna to low power for short-range locate
        scanner.setAntennaPower(LOCATE_ANTENNA_POWER)

        _state.value = _state.value.copy(
            isLocating = true,
            proximity = 0,
            statusMessage = "Searching for tag...",
            error = null
        )

        // Start audio beeping
        initToneGenerator()
        startBeeping()

        // Start the locate flow from the scanner
        locateJob = viewModelScope.launch {
            scanner.locateTag(epc)
                .catch { e ->
                    _state.value = _state.value.copy(
                        isLocating = false,
                        error = "Locate failed: ${e.message}",
                        statusMessage = "Error"
                    )
                    stopBeeping()
                }
                .collect { reading ->
                    val prox = reading.proximity.coerceIn(0, 100)
                    val msg = when {
                        prox >= 80 -> "Very close! Almost there!"
                        prox >= 60 -> "Getting closer..."
                        prox >= 40 -> "Tag detected nearby"
                        prox >= 20 -> "Tag in range — keep moving"
                        prox > 0  -> "Weak signal — search around"
                        else -> "Searching for tag..."
                    }
                    _state.value = _state.value.copy(
                        proximity = prox,
                        statusMessage = msg
                    )
                }
        }
    }

    fun stopLocating() {
        locateJob?.cancel()
        locateJob = null
        scanner.stopLocating()

        // Restore antenna to full power
        scanner.setAntennaPower(30)

        stopBeeping()

        _state.value = _state.value.copy(
            isLocating = false,
            proximity = 0,
            statusMessage = "Locate stopped"
        )
    }

    // ── Audio beeping ──────────────────────────────────────────

    private fun initToneGenerator() {
        releaseToneGenerator()
        toneGenerator = try {
            ToneGenerator(AudioAttributes.USAGE_NOTIFICATION, ToneGenerator.MAX_VOLUME)
        } catch (e: Exception) {
            null // Audio not available — locate still works, just without beeps
        }
    }

    private fun startBeeping() {
        beepJob?.cancel()
        beepJob = viewModelScope.launch {
            while (true) {
                val prox = _state.value.proximity

                if (prox > 0) {
                    // Beep! Short tone
                    toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 100)

                    // Beep interval: faster when closer
                    // prox=100 → 100ms (very fast beeping)
                    // prox=50  → 550ms
                    // prox=10  → 910ms (slow beeping)
                    // prox=0   → no beep (silent)
                    val intervalMs = (1000 - (prox * 9)).toLong().coerceIn(100, 1500)
                    delay(intervalMs)
                } else {
                    // No signal — wait quietly and check again
                    delay(500)
                }
            }
        }
    }

    private fun stopBeeping() {
        beepJob?.cancel()
        beepJob = null
        releaseToneGenerator()
    }

    private fun releaseToneGenerator() {
        toneGenerator?.release()
        toneGenerator = null
    }

    override fun onCleared() {
        super.onCleared()
        stopLocating()
    }
}

class LocateTagViewModelFactory(
    private val scanner: RfidScanner
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return LocateTagViewModel(scanner) as T
    }
}