package com.snainfotech.tagscout

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.snainfotech.tagscout.data.repository.DeviceRepository
import com.snainfotech.tagscout.ui.screens.home.ConnectionStatus
import com.snainfotech.tagscout.ui.screens.home.HomeScreen
import com.snainfotech.tagscout.ui.screens.home.HomeViewModel
import com.snainfotech.tagscout.ui.theme.TagScoutTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Get the application instance (which holds our repositories)
        val app = application as TagScoutApplication

        setContent {
            TagScoutTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // Create the HomeViewModel with its repository
                    val homeViewModel: HomeViewModel = viewModel(
                        factory = HomeViewModelFactory(app.deviceRepository)
                    )

                    // Watch the state for changes
                    val deviceState by homeViewModel.deviceState.collectAsState()

                    // For now: simulate a connected device (so you can see the UI work)
                    // We'll remove this when SDK is wired up
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        homeViewModel.updateDeviceStatus(
                            isConnected = false,
                            deviceName = "RFR-900",
                            serialNumber = "SN-123456",
                            firmwareVersion = "v5.90.00.02",
                            batteryPercent = 85,
                            isCharging = false
                        )
                    }

                    HomeScreen(
                        deviceState = deviceState,
                        modifier = Modifier.padding(innerPadding),
                        onMenuClick = {
                            Log.d("TagScout", "Menu clicked")
                        },
                        onQuickScanClick = {
                            Log.d("TagScout", "Quick Scan clicked")
                        },
                        onInventoryClick = {
                            Log.d("TagScout", "Inventory clicked")
                        },
                        onDeviceConfigClick = {
                            Log.d("TagScout", "Device Config clicked")
                        }
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