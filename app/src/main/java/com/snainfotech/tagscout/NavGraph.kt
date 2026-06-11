package com.snainfotech.tagscout

import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.snainfotech.tagscout.ui.components.AppMenu
import com.snainfotech.tagscout.ui.components.ExitConfirmationDialog
import com.snainfotech.tagscout.ui.screens.about.AboutScreen
import com.snainfotech.tagscout.ui.screens.home.HomeScreen
import com.snainfotech.tagscout.ui.screens.home.HomeViewModel
import com.snainfotech.tagscout.ui.screens.quickscan.ClearConfirmationDialog
import com.snainfotech.tagscout.ui.screens.quickscan.QuickScanScreen
import com.snainfotech.tagscout.ui.screens.quickscan.QuickScanViewModel
import com.snainfotech.tagscout.ui.screens.quickscan.SaveScanDialog
import com.snainfotech.tagscout.ui.screens.quickscan.TimeWarningDialog
import com.snainfotech.tagscout.ui.screens.connect.ConnectDeviceScreen
import com.snainfotech.tagscout.ui.screens.connect.ConnectDeviceViewModel

// All possible screen routes (like URLs for each screen)
object Routes {
    const val HOME = "home"
    const val QUICK_SCAN = "quick_scan"
    const val ABOUT = "about"
    const val CONNECT_DEVICE = "connect_device"
    // Future routes (we'll add these later):
    // const val INVENTORY = "inventory"
    // const val DEVICE_CONFIG = "device_config"
    // const val CONNECT_DEVICE = "connect_device"
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

            // Menu state
            var menuExpanded by remember { mutableStateOf(false) }
            var showExitDialog by remember { mutableStateOf(false) }
            val context = LocalContext.current
            val activity = context as? android.app.Activity

            // Wrap HomeScreen + menu in a Box so menu appears on top
            Box {
                HomeScreen(
                    deviceState = deviceState,
                    onMenuClick = { menuExpanded = true },
                    onQuickScanClick = { navController.navigate(Routes.QUICK_SCAN) },
                    onInventoryClick = { /* TODO: Inventory navigation */ },
                    onDeviceConfigClick = { /* TODO: Device Config navigation */ }
                )

                // Menu dropdown — anchored to top-right
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 50.dp, end = 10.dp)
                ) {
                    AppMenu(
                        expanded = menuExpanded,
                        showConnectHighlighted = !deviceState.isConnected,
                        onDismiss = { menuExpanded = false },
                        onConnectClick = { navController.navigate(Routes.CONNECT_DEVICE) },
                        onAboutClick = { navController.navigate(Routes.ABOUT) },
                        onExitClick = { showExitDialog = true }
                    )
                }
            }

            // Exit confirmation
            if (showExitDialog) {
                ExitConfirmationDialog(
                    onDismiss = { showExitDialog = false },
                    onConfirm = {
                        showExitDialog = false
                        activity?.finish()
                    }
                )
            }
        }

        // ============================================
        // QUICK SCAN SCREEN
        // ============================================
        composable(Routes.QUICK_SCAN) {
            val quickScanViewModel: QuickScanViewModel = viewModel(
                factory = QuickScanViewModelFactory(app.quickScanRepository, app.rfidScanner)
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

        // ============================================
        // ABOUT SCREEN
        // ============================================
        composable(Routes.ABOUT) {
            AboutScreen(
                onBackClick = { navController.popBackStack() },
                onMenuClick = { /* Menu not needed on about screen */ }
            )
        }
        // ============================================
        // CONNECT DEVICE SCREEN
        // ============================================
        composable(Routes.CONNECT_DEVICE) {
            val connectViewModel: ConnectDeviceViewModel = viewModel(
                factory = ConnectDeviceViewModelFactory(app.deviceRepository)
            )

            val connectState by connectViewModel.state.collectAsState()

            ConnectDeviceScreen(
                state = connectState,
                onBackClick = { navController.popBackStack() },
                onMenuClick = { /* TODO: maybe show menu here too */ },
                onSearchClick = { connectViewModel.startSearch() },
                onSearchQueryChange = { connectViewModel.updateSearchQuery(it) },
                onDeviceClick = { device ->
                    connectViewModel.connectToDevice(device)
                }
            )
        }
    }
}