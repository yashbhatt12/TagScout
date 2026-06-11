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
    private val deviceRepository: DeviceRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(com.snainfotech.tagscout.ui.screens.connect.ConnectDeviceViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return com.snainfotech.tagscout.ui.screens.connect.ConnectDeviceViewModel(deviceRepository) as T
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