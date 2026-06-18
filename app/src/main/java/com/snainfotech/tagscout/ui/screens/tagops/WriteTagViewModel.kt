package com.snainfotech.tagscout.ui.screens.tagops

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.snainfotech.tagscout.data.repository.SettingsRepository
import com.snainfotech.tagscout.sdk.FindTagResult
import com.snainfotech.tagscout.sdk.RfidConstants
import com.snainfotech.tagscout.sdk.RfidScanner
import com.snainfotech.tagscout.sdk.WriteTagResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// The phases of the write tag flow
enum class WritePhase {
    ENTER_TARGET,       // User entering target EPC
    SEARCHING,          // Calling findTag()
    TAG_FOUND,          // Tag found, enter new EPC + password
    TAG_NOT_FOUND,      // Tag not detected
    WRITING,            // Calling writeEpc()
    WRITE_SUCCESS,      // Done — show new EPC
    WRITE_FAILURE       // Failed — show reason
}

// Overall state
data class WriteTagState(
    val phase: WritePhase = WritePhase.ENTER_TARGET,
    val targetEpc: String = "",
    val newEpc: String = "",
    val accessPassword: String = "",          // Empty = use default
    val antennaStrength: Int = 5,
    val foundTagRssi: Int = 0,                // Signal strength when tag found
    val writeError: String = "",              // Error message when failed
    val previousEpc: String = "",             // The old EPC after successful write
    val verifiedNewEpc: String = ""           // The verified new EPC after write
)

class WriteTagViewModel(
    private val scanner: RfidScanner,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(WriteTagState())
    val state: StateFlow<WriteTagState> = _state.asStateFlow()

    private var operationJob: Job? = null

    init {
        val saved = settingsRepository.getAntennaStrength()
        _state.value = _state.value.copy(antennaStrength = saved)
        scanner.setAntennaPower(saved)
    }

    // ============================================
    // INPUT HANDLING
    // ============================================

    fun setTargetEpc(epc: String) {
        _state.value = _state.value.copy(targetEpc = epc.uppercase())
    }

    fun setNewEpc(epc: String) {
        _state.value = _state.value.copy(newEpc = epc.uppercase())
    }

    fun setAccessPassword(password: String) {
        _state.value = _state.value.copy(accessPassword = password)
    }

    fun setAntennaStrength(strength: Int) {
        if (_state.value.phase == WritePhase.SEARCHING || _state.value.phase == WritePhase.WRITING) {
            return  // Don't allow changes during active operation
        }
        settingsRepository.setAntennaStrength(strength)
        _state.value = _state.value.copy(antennaStrength = strength)
        scanner.setAntennaPower(strength)
    }

    // ============================================
    // VALIDATION
    // ============================================

    fun isTargetEpcValid(): Boolean = RfidConstants.isValidEpc(_state.value.targetEpc)

    fun isNewEpcValid(): Boolean = RfidConstants.isValidEpc(_state.value.newEpc)

    fun canFindTag(): Boolean = isTargetEpcValid() && _state.value.phase == WritePhase.ENTER_TARGET

    fun canWriteTag(): Boolean = isNewEpcValid() && _state.value.phase == WritePhase.TAG_FOUND

    // ============================================
    // OPERATIONS
    // ============================================

    fun findTag() {
        if (!canFindTag()) return

        operationJob?.cancel()
        _state.value = _state.value.copy(phase = WritePhase.SEARCHING)

        operationJob = viewModelScope.launch {
            val result = scanner.findTag(_state.value.targetEpc)

            _state.value = when (result) {
                is FindTagResult.Found -> _state.value.copy(
                    phase = WritePhase.TAG_FOUND,
                    foundTagRssi = result.tag.rssi
                )
                is FindTagResult.NotFound -> _state.value.copy(
                    phase = WritePhase.TAG_NOT_FOUND
                )
                is FindTagResult.Error -> _state.value.copy(
                    phase = WritePhase.TAG_NOT_FOUND,
                    writeError = result.reason
                )
            }
        }
    }

    fun retrySearch() {
        _state.value = _state.value.copy(
            phase = WritePhase.ENTER_TARGET,
            writeError = ""
        )
    }

    fun writeTag() {
        if (!canWriteTag()) return

        operationJob?.cancel()
        val targetEpc = _state.value.targetEpc
        _state.value = _state.value.copy(phase = WritePhase.WRITING)

        operationJob = viewModelScope.launch {
            val result = scanner.writeEpc(
                targetEpc = targetEpc,
                newEpc = _state.value.newEpc,
                accessPassword = _state.value.accessPassword
            )

            _state.value = when (result) {
                is WriteTagResult.Success -> _state.value.copy(
                    phase = WritePhase.WRITE_SUCCESS,
                    previousEpc = targetEpc,
                    verifiedNewEpc = result.verifiedEpc
                )
                is WriteTagResult.TagNotFound -> _state.value.copy(
                    phase = WritePhase.WRITE_FAILURE,
                    writeError = "Tag not found. It may have moved out of range."
                )
                is WriteTagResult.WrongPassword -> _state.value.copy(
                    phase = WritePhase.WRITE_FAILURE,
                    writeError = "Wrong access password. Try the default or check with your tag administrator."
                )
                is WriteTagResult.Timeout -> _state.value.copy(
                    phase = WritePhase.WRITE_FAILURE,
                    writeError = "Operation timed out. Move closer to the tag and try again."
                )
                is WriteTagResult.Failure -> _state.value.copy(
                    phase = WritePhase.WRITE_FAILURE,
                    writeError = result.reason
                )
            }
        }
    }

    // ============================================
    // RESET / NAVIGATION
    // ============================================

    fun startOver() {
        operationJob?.cancel()
        _state.value = WriteTagState(antennaStrength = _state.value.antennaStrength)
    }

    fun writeAnotherTag() {
        operationJob?.cancel()
        _state.value = WriteTagState(antennaStrength = _state.value.antennaStrength)
    }

    override fun onCleared() {
        super.onCleared()
        operationJob?.cancel()
    }
}

// Factory
class WriteTagViewModelFactory(
    private val scanner: RfidScanner,
    private val settingsRepository: SettingsRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WriteTagViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return WriteTagViewModel(scanner, settingsRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}