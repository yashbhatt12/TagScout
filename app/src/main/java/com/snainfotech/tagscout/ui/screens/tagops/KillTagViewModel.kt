package com.snainfotech.tagscout.ui.screens.tagops

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.snainfotech.tagscout.data.repository.SettingsRepository
import com.snainfotech.tagscout.sdk.FindTagResult
import com.snainfotech.tagscout.sdk.KillTagResult
import com.snainfotech.tagscout.sdk.RfidConstants
import com.snainfotech.tagscout.sdk.RfidScanner
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// The phases of the kill tag flow
enum class KillPhase {
    ENTER_TARGET,       // User entering target EPC
    SEARCHING,          // Calling findTag()
    TAG_FOUND,          // Tag found, show confirmation checkboxes
    TAG_NOT_FOUND,      // Tag not detected
    KILLING,            // Calling killTag()
    KILL_SUCCESS,       // Done — tag is dead
    KILL_FAILURE        // Failed — show reason
}

// Overall state
data class KillTagState(
    val phase: KillPhase = KillPhase.ENTER_TARGET,
    val targetEpc: String = "",
    val killPassword: String = "",          // Empty = use default
    val antennaStrength: Int = 5,
    val foundTagRssi: Int = 0,
    val killError: String = "",
    val killedEpc: String = "",             // The EPC that was killed (for success display)
    // Safety confirmation checkboxes — BOTH must be true to enable kill
    val confirmIrreversible: Boolean = false,
    val confirmCorrectTag: Boolean = false,
    val showDeviceDisconnectedDialog: Boolean = false
)

class KillTagViewModel(
    private val scanner: RfidScanner,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(KillTagState())
    val state: StateFlow<KillTagState> = _state.asStateFlow()

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

    fun setKillPassword(password: String) {
        _state.value = _state.value.copy(killPassword = password)
    }

    fun setAntennaStrength(strength: Int) {
        if (_state.value.phase == KillPhase.SEARCHING || _state.value.phase == KillPhase.KILLING) {
            return  // Don't allow changes during active operation
        }
        settingsRepository.setAntennaStrength(strength)
        _state.value = _state.value.copy(antennaStrength = strength)
        scanner.setAntennaPower(strength)
    }

    fun setConfirmIrreversible(checked: Boolean) {
        _state.value = _state.value.copy(confirmIrreversible = checked)
    }

    fun setConfirmCorrectTag(checked: Boolean) {
        _state.value = _state.value.copy(confirmCorrectTag = checked)
    }

    // ============================================
    // VALIDATION
    // ============================================

    fun isTargetEpcValid(): Boolean = RfidConstants.isValidEpc(_state.value.targetEpc)

    fun canFindTag(): Boolean = isTargetEpcValid() && _state.value.phase == KillPhase.ENTER_TARGET

    // Both confirmations must be checked AND we must be in TAG_FOUND phase
    fun canKillTag(): Boolean {
        val s = _state.value
        return s.phase == KillPhase.TAG_FOUND &&
                s.confirmIrreversible &&
                s.confirmCorrectTag
    }

    // ============================================
    // OPERATIONS
    // ============================================

    fun findTag() {
        if (!canFindTag()) return

        operationJob?.cancel()
        _state.value = _state.value.copy(phase = KillPhase.SEARCHING)

        operationJob = viewModelScope.launch {
            val result = scanner.findTag(_state.value.targetEpc)

            _state.value = when (result) {
                is FindTagResult.Found -> _state.value.copy(
                    phase = KillPhase.TAG_FOUND,
                    foundTagRssi = result.tag.rssi
                )
                is FindTagResult.NotFound -> _state.value.copy(
                    phase = KillPhase.TAG_NOT_FOUND
                )
                is FindTagResult.Error -> _state.value.copy(
                    phase = KillPhase.TAG_NOT_FOUND,
                    killError = result.reason
                )
            }
        }
    }

    fun retrySearch() {
        // Reset confirmations when going back
        _state.value = _state.value.copy(
            phase = KillPhase.ENTER_TARGET,
            killError = "",
            confirmIrreversible = false,
            confirmCorrectTag = false
        )
    }

    fun killTag() {
        if (!canKillTag()) return

        operationJob?.cancel()
        val targetEpc = _state.value.targetEpc
        _state.value = _state.value.copy(phase = KillPhase.KILLING)

        operationJob = viewModelScope.launch {
            val result = scanner.killTag(
                targetEpc = targetEpc,
                killPassword = _state.value.killPassword
            )

            _state.value = when (result) {
                is KillTagResult.Success -> _state.value.copy(
                    phase = KillPhase.KILL_SUCCESS,
                    killedEpc = targetEpc
                )
                is KillTagResult.TagNotFound -> _state.value.copy(
                    phase = KillPhase.KILL_FAILURE,
                    killError = "Tag not found. It may have moved out of range."
                )
                is KillTagResult.WrongPassword -> _state.value.copy(
                    phase = KillPhase.KILL_FAILURE,
                    killError = "Wrong kill password. Tag is unmodified."
                )
                is KillTagResult.Timeout -> _state.value.copy(
                    phase = KillPhase.KILL_FAILURE,
                    killError = "Operation timed out. Move closer and try again."
                )
                is KillTagResult.Failure -> _state.value.copy(
                    phase = KillPhase.KILL_FAILURE,
                    killError = result.reason
                )
            }
        }
    }

    // ============================================
    // RESET / NAVIGATION
    // ============================================

    fun startOver() {
        operationJob?.cancel()
        _state.value = KillTagState(antennaStrength = _state.value.antennaStrength)
    }

    override fun onCleared() {
        super.onCleared()
        operationJob?.cancel()
    }
    // ============================================
    // DEVICE DISCONNECTED HANDLING (E11)
    // ============================================

    fun handleDeviceDisconnected() {
        // Cancel any in-flight operation
        operationJob?.cancel()

        // Reset to ENTER_TARGET phase but preserve antenna setting
        // Clear all form data + confirmations since operation was interrupted
        _state.value = KillTagState(
            antennaStrength = _state.value.antennaStrength,
            showDeviceDisconnectedDialog = true
        )
    }

    fun dismissDeviceDisconnectedDialog() {
        _state.value = _state.value.copy(showDeviceDisconnectedDialog = false)
    }
}

// Factory
class KillTagViewModelFactory(
    private val scanner: RfidScanner,
    private val settingsRepository: SettingsRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(KillTagViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return KillTagViewModel(scanner, settingsRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}