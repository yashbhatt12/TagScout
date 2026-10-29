package com.snainfotech.tagscout.data.reader

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Which readers suitable for one-piece-at-a-time enrollment are connected.
 *
 * Unit enrollment is only offered when the app KNOWS a desktop reader (USB)
 * or a fixed reader (network) is connected. The Bluetooth sled is not used
 * for enrollment: with only a sled, the Enroll screen offers Excel upload.
 *
 * Both start false. The desktop and fixed reader integration (next phase)
 * sets them: USB attach/detach for the desktop reader, the network
 * connection for the fixed reader.
 */
object ReaderAvailability {

    private val _desktopConnected = MutableStateFlow(false)
    private val _fixedConnected = MutableStateFlow(false)

    val desktopConnected: StateFlow<Boolean> = _desktopConnected.asStateFlow()
    val fixedConnected: StateFlow<Boolean> = _fixedConnected.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** True when either a desktop or a fixed reader is connected. */
    val unitReaderConnected: StateFlow<Boolean> =
        combine(_desktopConnected, _fixedConnected) { desktop, fixed -> desktop || fixed }
            .stateIn(scope, SharingStarted.Eagerly, false)

    fun setDesktopConnected(connected: Boolean) { _desktopConnected.value = connected }
    fun setFixedConnected(connected: Boolean) { _fixedConnected.value = connected }
}
