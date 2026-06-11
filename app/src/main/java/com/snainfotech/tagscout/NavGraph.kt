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
import com.snainfotech.tagscout.ui.screens.config.DeviceConfigScreen
import com.snainfotech.tagscout.ui.screens.config.DeviceConfigViewModel
import com.snainfotech.tagscout.ui.screens.config.OperationState
import com.snainfotech.tagscout.ui.screens.config.ResetConfirmationDialog
import com.snainfotech.tagscout.ui.screens.config.ResetProgressDialog
import com.snainfotech.tagscout.ui.screens.config.ResetSuccessDialog
import com.snainfotech.tagscout.ui.screens.config.FirmwareUpdateAvailableDialog
import com.snainfotech.tagscout.ui.screens.config.FirmwareUpdateProgressDialog
import com.snainfotech.tagscout.ui.screens.config.FirmwareUpdateSuccessDialog
import com.snainfotech.tagscout.ui.screens.config.FirmwareCheckingDialog
import com.snainfotech.tagscout.ui.screens.config.FirmwareUpToDateDialog

// All possible screen routes (like URLs for each screen)
object Routes {
    const val HOME = "home"
    const val QUICK_SCAN = "quick_scan"
    const val ABOUT = "about"
    const val CONNECT_DEVICE = "connect_device"
    const val DEVICE_CONFIG = "device_config"
    // Future routes (we'll add these later):
    // const val INVENTORY = "inventory"
}

@Composable
fun TagScoutNavGraph(
    navController: NavHostController = rememberNavController(),
    modifier: Modifier = Modifier
) {
    val app = LocalContext.current.applicationContext as TagScoutApplication

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

            var menuExpanded by remember { mutableStateOf(false) }
            var showExitDialog by remember { mutableStateOf(false) }
            val context = LocalContext.current
            val activity = context as? android.app.Activity

            Box {
                HomeScreen(
                    deviceState = deviceState,
                    onMenuClick = { menuExpanded = true },
                    onQuickScanClick = { navController.navigate(Routes.QUICK_SCAN) },
                    onInventoryClick = { /* TODO: Inventory navigation */ },
                    onDeviceConfigClick = { navController.navigate(Routes.DEVICE_CONFIG) }
                )

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

            var showSaveDialog by remember { mutableStateOf(false) }
            var showClearDialog by remember { mutableStateOf(false) }

            QuickScanScreen(
                state = scanState,
                deviceName = "RFR-901",
                serialNumber = "SN-123456",
                firmwareVersion = "v5.90.00.02",
                batteryPercent = 85,
                onBackClick = { navController.popBackStack() },
                onMenuClick = { /* TODO: Show menu */ },
                onAntennaChange = { quickScanViewModel.setAntennaStrength(it) },
                onPlayPauseClick = {
                    when {
                        scanState.isScanning -> quickScanViewModel.pauseScanning()
                        scanState.isPaused -> quickScanViewModel.resumeScanning()
                        else -> quickScanViewModel.startScanning()
                    }
                },
                onSaveClick = { showSaveDialog = true },
                onClearClick = { showClearDialog = true }
            )

            if (showSaveDialog) {
                SaveScanDialog(
                    onDismiss = { showSaveDialog = false },
                    onSave = { filename, _ ->
                        quickScanViewModel.saveScan(filename)
                        showSaveDialog = false
                    }
                )
            }

            if (showClearDialog) {
                ClearConfirmationDialog(
                    onDismiss = { showClearDialog = false },
                    onConfirm = {
                        quickScanViewModel.clearAllData()
                        showClearDialog = false
                    }
                )
            }

            if (scanState.showTimeWarning) {
                TimeWarningDialog(
                    onStopScan = {
                        quickScanViewModel.dismissTimeWarning()
                        quickScanViewModel.pauseScanning()
                    },
                    onExtend = { quickScanViewModel.extendTimer() }
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
                onMenuClick = { /* TODO */ },
                onSearchClick = { connectViewModel.startSearch() },
                onSearchQueryChange = { connectViewModel.updateSearchQuery(it) },
                onDeviceClick = { device -> connectViewModel.connectToDevice(device) }
            )
        }

        // ============================================
        // DEVICE CONFIG SCREEN
        // ============================================
        composable(Routes.DEVICE_CONFIG) {
            val configViewModel: DeviceConfigViewModel = viewModel(
                factory = DeviceConfigViewModelFactory()
            )

            val configState by configViewModel.state.collectAsState()

            DeviceConfigScreen(
                state = configState,
                onBackClick = { navController.popBackStack() },
                onMenuClick = { /* TODO */ },
                onBuzzerChange = { level -> configViewModel.setBuzzerLevel(level) },
                onSleepTimeoutChange = { timeout -> configViewModel.setSleepTimeout(timeout) },
                onResetClick = { configViewModel.startResetConfirmation() },
                onCheckFirmwareClick = { configViewModel.checkForFirmwareUpdate() }
            )

            // === Reset dialogs ===
            when (configState.resetState) {
                OperationState.CONFIRMING -> ResetConfirmationDialog(
                    onDismiss = { configViewModel.dismissReset() },
                    onConfirm = { configViewModel.confirmReset() }
                )
                OperationState.IN_PROGRESS -> ResetProgressDialog(progress = configState.resetProgress)
                OperationState.SUCCESS -> ResetSuccessDialog(
                    onDismiss = { configViewModel.dismissReset() }
                )
                else -> { /* IDLE or UP_TO_DATE: nothing for reset */ }
            }

            // === Firmware update dialogs ===
            when (configState.firmwareUpdateState) {
                OperationState.IN_PROGRESS -> {
                    if (configState.firmwareUpdateProgress == 0 && configState.newFirmwareVersion.isEmpty()) {
                        FirmwareCheckingDialog()
                    } else {
                        FirmwareUpdateProgressDialog(progress = configState.firmwareUpdateProgress)
                    }
                }
                OperationState.CONFIRMING -> FirmwareUpdateAvailableDialog(
                    currentVersion = configState.firmwareVersion,
                    newVersion = configState.newFirmwareVersion,
                    onDismiss = { configViewModel.dismissFirmwareUpdate() },
                    onUpdate = { configViewModel.confirmFirmwareUpdate() }
                )
                OperationState.SUCCESS -> FirmwareUpdateSuccessDialog(
                    newVersion = configState.firmwareVersion,
                    onDismiss = { configViewModel.dismissFirmwareUpdate() }
                )
                OperationState.UP_TO_DATE -> FirmwareUpToDateDialog(
                    currentVersion = configState.firmwareVersion,
                    onDismiss = { configViewModel.dismissFirmwareUpdate() }
                )
                OperationState.IDLE -> { /* nothing */ }
            }
        }
    }
}