package com.snainfotech.tagscout

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.snainfotech.tagscout.data.repository.DeviceRepository
import com.snainfotech.tagscout.data.repository.QuickScanRepository
import com.snainfotech.tagscout.ui.screens.home.HomeViewModel
import com.snainfotech.tagscout.ui.screens.quickscan.QuickScanViewModel
import com.snainfotech.tagscout.ui.theme.TagScoutTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            TagScoutTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    TagScoutNavGraph(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        // Best-effort: disconnect cleanly when the app closes, so the sled
        // doesn't get left thinking it's still connected next time the app
        // opens (which would otherwise block a fresh connection attempt).
        // Not guaranteed to run if Android kills the process outright — the
        // real fix for that case is the defensive disconnect-before-connect
        // in BluebirdRfidScanner.connect() — but this covers the common case
        // of a normal app close.
        runCatching {
            (applicationContext as? TagScoutApplication)?.rfidScanner?.disconnect()
        }
        super.onDestroy()
    }
}

// Factory: Creates the HomeViewModel with its required parameters
class HomeViewModelFactory(
    private val deviceRepository: DeviceRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HomeViewModel(deviceRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

// Factory: Creates the QuickScanViewModel with its required parameters
class QuickScanViewModelFactory(
    private val repository: QuickScanRepository,
    private val scanner: com.snainfotech.tagscout.sdk.RfidScanner,
    private val settingsRepository: com.snainfotech.tagscout.data.repository.SettingsRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(QuickScanViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return QuickScanViewModel(repository, scanner, settingsRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
// Factory: Creates the ConnectDeviceViewModel with its required parameters
class ConnectDeviceViewModelFactory(
    private val deviceRepository: DeviceRepository,
    private val rfidScanner: com.snainfotech.tagscout.sdk.RfidScanner
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(com.snainfotech.tagscout.ui.screens.connect.ConnectDeviceViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return com.snainfotech.tagscout.ui.screens.connect.ConnectDeviceViewModel(deviceRepository, rfidScanner) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }

}

// Factory: Creates the DeviceConfigViewModel (no parameters needed)
class DeviceConfigViewModelFactory(
    private val settingsRepository: com.snainfotech.tagscout.data.repository.SettingsRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(com.snainfotech.tagscout.ui.screens.config.DeviceConfigViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return com.snainfotech.tagscout.ui.screens.config.DeviceConfigViewModel(settingsRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}