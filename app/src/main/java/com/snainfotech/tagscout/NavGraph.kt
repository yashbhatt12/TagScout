package com.snainfotech.tagscout

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.snainfotech.tagscout.ui.screens.home.HomeScreen
import com.snainfotech.tagscout.ui.screens.home.HomeViewModel
import com.snainfotech.tagscout.ui.screens.quickscan.ClearConfirmationDialog
import com.snainfotech.tagscout.ui.screens.quickscan.QuickScanScreen
import com.snainfotech.tagscout.ui.screens.quickscan.QuickScanViewModel
import com.snainfotech.tagscout.ui.screens.quickscan.SaveScanDialog
import com.snainfotech.tagscout.ui.screens.quickscan.TimeWarningDialog

// All possible screen routes (like URLs for each screen)
object Routes {
    const val HOME = "home"
    const val QUICK_SCAN = "quick_scan"
    // Future routes (we'll add these later):
    // const val INVENTORY = "inventory"
    // const val DEVICE_CONFIG = "device_config"
    // const val CONNECT_DEVICE = "connect_device"
    // const val ABOUT = "about"
}

@Composable
fun TagScoutNavGraph(
    navController: NavHostController = rememberNavController(),
    modifier: Modifier = Modifier
) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as TagScoutApplication

    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier
    ) {
        // ============================================
        // HOME SCREEN
        // ============================================
        composable(Routes.HOME) {
            val homeViewModel: HomeViewModel = viewModel(
                factory = HomeViewModelFactory(app.deviceRepository)
            )

            val deviceState by homeViewModel.deviceState.collectAsState()

            // Fake "connected" device (remove when real SDK is integrated)
            LaunchedEffect(Unit) {
                homeViewModel.updateDeviceStatus(
                    isConnected = true,
                    deviceName = "RFR-901",
                    serialNumber = "SN-123456",
                    firmwareVersion = "v5.90.00.02",
                    batteryPercent = 85,
                    isCharging = false
                )
            }

            HomeScreen(
                deviceState = deviceState,
                onMenuClick = {
                    // TODO: Show menu dropdown
                },
                onQuickScanClick = {
                    navController.navigate(Routes.QUICK_SCAN)
                },
                onInventoryClick = {
                    // TODO: Navigate to Inventory
                },
                onDeviceConfigClick = {
                    // TODO: Navigate to Device Config
                }
            )
        }

        // ============================================
        // QUICK SCAN SCREEN
        // ============================================
        composable(Routes.QUICK_SCAN) {
            val quickScanViewModel: QuickScanViewModel = viewModel(
                factory = QuickScanViewModelFactory(app.quickScanRepository)
            )

            val scanState by quickScanViewModel.state.collectAsState()

            // Dialog state — which dialog is showing (if any)
            var showSaveDialog by remember { mutableStateOf(false) }
            var showClearDialog by remember { mutableStateOf(false) }

            QuickScanScreen(
                state = scanState,
                deviceName = "RFR-901",
                serialNumber = "SN-123456",
                firmwareVersion = "v5.90.00.02",
                batteryPercent = 85,
                onBackClick = {
                    navController.popBackStack()
                },
                onMenuClick = {
                    // TODO: Show menu
                },
                onAntennaChange = { newValue ->
                    quickScanViewModel.setAntennaStrength(newValue)
                },
                onPlayPauseClick = {
                    when {
                        scanState.isScanning -> quickScanViewModel.pauseScanning()
                        scanState.isPaused -> quickScanViewModel.resumeScanning()
                        else -> quickScanViewModel.startScanning()
                    }
                },
                onSaveClick = {
                    showSaveDialog = true
                },
                onClearClick = {
                    showClearDialog = true
                }
            )

            // Save dialog
            if (showSaveDialog) {
                SaveScanDialog(
                    onDismiss = { showSaveDialog = false },
                    onSave = { filename, _ ->
                        quickScanViewModel.saveScan(filename)
                        showSaveDialog = false
                    }
                )
            }

            // Clear confirmation dialog
            if (showClearDialog) {
                ClearConfirmationDialog(
                    onDismiss = { showClearDialog = false },
                    onConfirm = {
                        quickScanViewModel.clearAllData()
                        showClearDialog = false
                    }
                )
            }

            // Time warning dialog (auto-shown by ViewModel)
            if (scanState.showTimeWarning) {
                TimeWarningDialog(
                    onStopScan = {
                        quickScanViewModel.dismissTimeWarning()
                        quickScanViewModel.pauseScanning()
                    },
                    onExtend = {
                        quickScanViewModel.extendTimer()
                    }
                )
            }
        }
    }
}