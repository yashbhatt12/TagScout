package com.snainfotech.tagscout

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.snainfotech.tagscout.ui.components.AppMenu
import com.snainfotech.tagscout.ui.components.ExitConfirmationDialog
import com.snainfotech.tagscout.ui.screens.about.AboutScreen
import com.snainfotech.tagscout.ui.screens.config.DeviceConfigScreen
import com.snainfotech.tagscout.ui.screens.config.DeviceConfigViewModel
import com.snainfotech.tagscout.ui.screens.config.FirmwareCheckingDialog
import com.snainfotech.tagscout.ui.screens.config.FirmwareUpToDateDialog
import com.snainfotech.tagscout.ui.screens.config.FirmwareUpdateAvailableDialog
import com.snainfotech.tagscout.ui.screens.config.FirmwareUpdateProgressDialog
import com.snainfotech.tagscout.ui.screens.config.FirmwareUpdateSuccessDialog
import com.snainfotech.tagscout.ui.screens.config.OperationState
import com.snainfotech.tagscout.ui.screens.config.ResetConfirmationDialog
import com.snainfotech.tagscout.ui.screens.config.ResetProgressDialog
import com.snainfotech.tagscout.ui.screens.config.ResetSuccessDialog
import com.snainfotech.tagscout.ui.screens.connect.ConnectDeviceScreen
import com.snainfotech.tagscout.ui.screens.connect.ConnectDeviceViewModel
import com.snainfotech.tagscout.ui.screens.home.HomeScreen
import com.snainfotech.tagscout.ui.screens.home.HomeViewModel
import com.snainfotech.tagscout.ui.screens.quickscan.ClearConfirmationDialog
import com.snainfotech.tagscout.ui.screens.quickscan.QuickScanScreen
import com.snainfotech.tagscout.ui.screens.quickscan.QuickScanViewModel
import com.snainfotech.tagscout.ui.screens.quickscan.SaveScanDialog
import com.snainfotech.tagscout.ui.screens.quickscan.TimeWarningDialog
import com.snainfotech.tagscout.ui.screens.connect.DeleteDeviceConfirmationDialog
import com.snainfotech.tagscout.ui.screens.connect.DeviceLimitReachedDialog
import com.snainfotech.tagscout.ui.screens.quickscan.LowBatteryDialog
import com.snainfotech.tagscout.ui.screens.quickscan.DeviceDisconnectedDialog
import com.snainfotech.tagscout.ui.screens.quickscan.CriticalBatteryDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.snainfotech.tagscout.PermissionHelper
import com.snainfotech.tagscout.ui.components.BluetoothPermissionDeniedDialog
import com.snainfotech.tagscout.ui.components.BluetoothPermissionRationaleDialog
private const val LOW_BATTERY_THRESHOLD = 15
private const val CRITICAL_BATTERY_THRESHOLD = 5

// All possible screen routes (like URLs for each screen)
object Routes {
    const val HOME = "home"
    const val QUICK_SCAN = "quick_scan"
    const val ABOUT = "about"
    const val CONNECT_DEVICE = "connect_device"
    // Battery threshold for showing low battery warning during scan
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

    // Create a SHARED HomeViewModel that all screens can read
    val sharedHomeViewModel: HomeViewModel = viewModel(
        factory = HomeViewModelFactory(app.deviceRepository)
    )

    // Initialize device state ONCE at app startup

    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier
    ) {
        // ============================================
        // HOME SCREEN
        // ============================================
        composable(Routes.HOME) {
            val deviceState by sharedHomeViewModel.deviceState.collectAsState()

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
                factory = QuickScanViewModelFactory(app.quickScanRepository, app.rfidScanner, app.settingsRepository)
            )

            val scanState by quickScanViewModel.state.collectAsState()
            val deviceState by sharedHomeViewModel.deviceState.collectAsState()

            // Block system back button when scanning
            BackHandler(enabled = scanState.isScanning) {
                // Do nothing — user must pause first
            }

            // E7: Watch for device disconnection mid-scan
            LaunchedEffect(deviceState.isConnected) {
                if (!deviceState.isConnected && (scanState.isScanning || scanState.isPaused)) {
                    quickScanViewModel.handleDeviceDisconnected()
                }
            }

            // E8: Watch for low battery during scan (two-tier)
            LaunchedEffect(deviceState.batteryPercent, scanState.isScanning) {
                if (!scanState.isScanning || !deviceState.isConnected) return@LaunchedEffect

                val battery = deviceState.batteryPercent

                when {
                    // Critical battery (≤ 5%) — force-stop, no continue option
                    battery in 1..CRITICAL_BATTERY_THRESHOLD -> {
                        quickScanViewModel.handleCriticalBattery()
                    }
                    // Low battery (≤ 15% but > 5%) — warn with continue option
                    battery in (CRITICAL_BATTERY_THRESHOLD + 1)..LOW_BATTERY_THRESHOLD &&
                            !scanState.lowBatteryWarningAcknowledged -> {
                        quickScanViewModel.handleLowBattery()
                    }
                }
            }

            var showSaveDialog by remember { mutableStateOf(false) }
            var showClearDialog by remember { mutableStateOf(false) }

            QuickScanScreen(
                state = scanState,
                deviceName = deviceState.deviceName,
                serialNumber = deviceState.serialNumber,
                firmwareVersion = deviceState.firmwareVersion,
                batteryPercent = deviceState.batteryPercent,
                onBackClick = { navController.popBackStack() },
                onMenuClick = { /* TODO: Show menu */ },
                onDeviceStatusClick = { navController.navigate(Routes.DEVICE_CONFIG) },
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

            // E7: Device disconnected dialog
            if (scanState.showDeviceDisconnectedDialog) {
                DeviceDisconnectedDialog(
                    onDismiss = { quickScanViewModel.dismissDeviceDisconnectedDialog() }
                )
            }

            // E8: Low battery warning dialog
            if (scanState.showLowBatteryWarning) {
                LowBatteryDialog(
                    batteryPercent = deviceState.batteryPercent,
                    onContinue = { quickScanViewModel.continueScanningDespiteLowBattery() },
                    onStop = { quickScanViewModel.stopScanningDueToLowBattery() }
                )
            }
            // Critical battery dialog
            if (scanState.showCriticalBatteryDialog) {
                CriticalBatteryDialog(
                    batteryPercent = deviceState.batteryPercent,
                    onAcknowledge = { quickScanViewModel.dismissCriticalBatteryDialog() }
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
            val deviceState by sharedHomeViewModel.deviceState.collectAsState()
            val context = LocalContext.current

            // Sync visual state with shared connected device
            LaunchedEffect(deviceState.serialNumber, deviceState.isConnected) {
                val connectedId = if (deviceState.isConnected) {
                    deviceState.serialNumber.removePrefix("SN-")
                } else {
                    null
                }
                connectViewModel.syncWithConnectedDevice(connectedId)
            }

            // Android's permission request launcher
            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { permissions ->
                val allGranted = permissions.values.all { it }
                if (allGranted) {
                    // Permission granted — start searching!
                    connectViewModel.startSearch()
                } else {
                    // User denied — show denied dialog
                    connectViewModel.showPermissionDenied()
                }
            }

            ConnectDeviceScreen(
                state = connectState,
                onBackClick = { navController.popBackStack() },
                onMenuClick = { /* TODO */ },
                onSearchClick = {
                    if (PermissionHelper.hasBluetoothPermissions(context)) {
                        connectViewModel.startSearch()
                    } else {
                        connectViewModel.showPermissionRationale()
                    }
                },
                onSearchQueryChange = { connectViewModel.updateSearchQuery(it) },
                onDeviceClick = { device ->
                    val currentConnectedId = deviceState.serialNumber.removePrefix("SN-")
                    val isAlreadyConnected = deviceState.isConnected && currentConnectedId == device.id

                    if (isAlreadyConnected) {
                        navController.navigate(Routes.DEVICE_CONFIG)
                    } else {
                        connectViewModel.connectToDevice(device)
                        sharedHomeViewModel.updateDeviceStatus(
                            isConnected = true,
                            deviceName = device.name,
                            serialNumber = "SN-${device.id}",
                            firmwareVersion = "v5.90.00.02",
                            batteryPercent = 85,
                            isCharging = false
                        )
                    }
                },
                onDeviceLongPress = { device ->
                    connectViewModel.showDeleteConfirmation(device)
                }
            )

            // Permission rationale dialog
            if (connectState.showPermissionRationale) {
                BluetoothPermissionRationaleDialog(
                    onDismiss = { connectViewModel.dismissPermissionRationale() },
                    onAllow = {
                        connectViewModel.dismissPermissionRationale()
                        // Actually request the permission from Android
                        permissionLauncher.launch(PermissionHelper.getRequiredBluetoothPermissions())
                    }
                )
            }

            // Permission denied dialog
            if (connectState.showPermissionDeniedDialog) {
                BluetoothPermissionDeniedDialog(
                    onDismiss = { connectViewModel.dismissPermissionDenied() },
                    onOpenSettings = {
                        connectViewModel.dismissPermissionDenied()
                        // Open the app's settings page so user can grant permission manually
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    }
                )
            }
            // Delete device confirmation dialog
            val deviceToDelete = connectState.showDeleteConfirmation
            if (deviceToDelete != null) {
                DeleteDeviceConfirmationDialog(
                    deviceName = deviceToDelete.name,
                    isCurrentlyConnected = deviceToDelete.id == connectState.currentlyConnectedId,
                    onDismiss = { connectViewModel.dismissDeleteConfirmation() },
                    onConfirm = {
                        // If deleting the currently connected device, also disconnect
                        if (deviceToDelete.id == connectState.currentlyConnectedId) {
                            sharedHomeViewModel.updateDeviceStatus(
                                isConnected = false,
                                deviceName = "",
                                serialNumber = "",
                                firmwareVersion = "",
                                batteryPercent = 0,
                                isCharging = false
                            )
                        }
                        connectViewModel.confirmDeleteDevice(deviceToDelete)
                    }
                )
            }

            // Device limit reached dialog
            if (connectState.showLimitReachedDialog) {
                DeviceLimitReachedDialog(
                    onDismiss = { connectViewModel.dismissLimitReachedDialog() }
                )
            }
        }

        // ============================================
        // DEVICE CONFIG SCREEN
        // ============================================
        composable(Routes.DEVICE_CONFIG) {
            val configViewModel: DeviceConfigViewModel = viewModel(
                factory = DeviceConfigViewModelFactory(app.settingsRepository)
            )

            val configState by configViewModel.state.collectAsState()

            // Use the SHARED HomeViewModel from app scope
            val deviceState by sharedHomeViewModel.deviceState.collectAsState()

            DeviceConfigScreen(
                state = configState,
                deviceName = deviceState.deviceName,
                serialNumber = deviceState.serialNumber,
                batteryPercent = deviceState.batteryPercent,
                isConnected = deviceState.isConnected,
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