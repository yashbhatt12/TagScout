package com.snainfotech.tagscout.ui.screens.manager

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.snainfotech.tagscout.data.manager.Alert
import com.snainfotech.tagscout.data.manager.ManagerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State for the Manager Alerts screen.
 *
 * The screen shows a list of alerts with unread ones visually distinct from
 * read ones. The manager can tap any alert to mark it read, or clear every
 * unread alert at once.
 *
 * - alerts         the live list from Firestore, newest-first.
 * - isLoading      true until the first snapshot arrives.
 * - errorMessage   one-shot error for display.
 * - infoMessage    one-shot info/success for display (e.g. "7 alerts cleared").
 */
data class ManagerAlertsState(
    val alerts: List<Alert> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val infoMessage: String? = null
) {
    /** Derived — how many of the current alerts are still unread. */
    val unreadCount: Int get() = alerts.count { !it.read }

    /** Derived — true iff the screen has settled with zero alerts to show. */
    val isEmpty: Boolean get() = !isLoading && alerts.isEmpty()
}

/**
 * ViewModel for the Manager Alerts screen.
 *
 * On creation it starts collecting [ManagerRepository.observeAlerts] in
 * viewModelScope. The collection stays alive for the ViewModel's lifetime
 * (across configuration changes, briefly across back-to-foreground) and
 * closes when the ViewModel is cleared. One Firestore listener per instance.
 *
 * Error handling: a Flow error (auth lost, network outage past Firestore's
 * own retries) surfaces as `errorMessage` and the Flow stops. The user can
 * leave and re-enter the screen to retry; adding automatic retry here is
 * work for later.
 */
class ManagerAlertsViewModel(
    private val repository: ManagerRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ManagerAlertsState())
    val state: StateFlow<ManagerAlertsState> = _state.asStateFlow()

    init {
        observe()
    }

    private fun observe() {
        viewModelScope.launch {
            repository.observeAlerts()
                .catch { e ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Could not load alerts: ${e.message ?: "unknown error"}"
                        )
                    }
                }
                .collect { alerts ->
                    _state.update {
                        it.copy(alerts = alerts, isLoading = false)
                    }
                }
        }
    }

    /** Tap on a single alert — mark it read. No-op if already read. */
    fun markRead(alertId: String) {
        val target = _state.value.alerts.firstOrNull { it.id == alertId }
        if (target == null || target.read) return

        viewModelScope.launch {
            val result = repository.markRead(alertId)
            // The realtime listener will reflect the change in `alerts`
            // on its own; no need to manually update state here.
            result.onFailure { e ->
                _state.update {
                    it.copy(errorMessage = "Failed to mark read: ${e.message ?: "unknown error"}")
                }
            }
        }
    }

    /** Clear everything unread in one batch. Reports how many got cleared. */
    fun markAllRead() {
        viewModelScope.launch {
            val result = repository.markAllRead()
            result
                .onSuccess { count ->
                    _state.update {
                        it.copy(
                            infoMessage = when (count) {
                                0 -> "Nothing to clear"
                                1 -> "1 alert cleared"
                                else -> "$count alerts cleared"
                            }
                        )
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(errorMessage = "Failed to clear alerts: ${e.message ?: "unknown error"}")
                    }
                }
        }
    }

    /** Dismiss any one-shot error/info messages. Called by the screen. */
    fun dismissMessage() {
        _state.update { it.copy(errorMessage = null, infoMessage = null) }
    }
}

/**
 * Factory — matches the pattern used by other ViewModels in the project
 * (JewelleryCheckoutViewModel, WarehouseSetupViewModel, etc.).
 *
 * The repository is injected rather than constructed inside the ViewModel
 * so screens can swap in a fake for previews/tests.
 */
class ManagerAlertsViewModelFactory(
    private val repository: ManagerRepository
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ManagerAlertsViewModel::class.java)) {
            return ManagerAlertsViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}