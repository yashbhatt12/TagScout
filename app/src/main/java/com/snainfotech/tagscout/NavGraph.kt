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
import androidx.activity.compose.rememberLauncherForActivityResult
import com.snainfotech.tagscout.ui.screens.inventory.InventoryItem
import com.snainfotech.tagscout.ui.screens.inventory.InventoryScanScreen
import com.snainfotech.tagscout.ui.screens.inventory.InventoryScanViewModel
import com.snainfotech.tagscout.ui.screens.inventory.InventoryScanViewModelFactory
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
import com.snainfotech.tagscout.ui.screens.quickscan.SaveScanDialog
import com.snainfotech.tagscout.ui.screens.tagops.WriteTagScreen
import com.snainfotech.tagscout.ui.screens.tagops.WriteTagViewModel
import com.snainfotech.tagscout.ui.screens.tagops.WriteTagViewModelFactory
import com.snainfotech.tagscout.ui.screens.tagops.WritePhase
import com.snainfotech.tagscout.ui.screens.tagops.KillPhase
import com.snainfotech.tagscout.ui.screens.tagops.KillTagScreen
import com.snainfotech.tagscout.ui.screens.tagops.KillTagViewModel
import com.snainfotech.tagscout.ui.screens.tagops.KillTagViewModelFactory
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.snainfotech.tagscout.PermissionHelper
import com.snainfotech.tagscout.ui.components.BluetoothPermissionDeniedDialog
import com.snainfotech.tagscout.ui.components.BluetoothPermissionRationaleDialog
import com.snainfotech.tagscout.ui.components.PreSaveWarningDialog
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
    const val INVENTORY = "inventory"
    // Future routes (we'll add these later):
    // const val INVENTORY = "inventory"
    const val WRITE_TAG = "write_tag"
    const val KILL_TAG= "kill_tag"
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
                    onConnectDeviceClick = { navController.navigate(Routes.CONNECT_DEVICE) },
                    onQuickScanClick = { navController.navigate(Routes.QUICK_SCAN) },
                    onInventoryClick = { navController.navigate(Routes.INVENTORY) },
                    onWriteTagClick = { navController.navigate(Routes.WRITE_TAG) },
                    onKillTagClick = { navController.navigate(Routes.KILL_TAG) },
                    onDeviceConfigClick = { navController.navigate(Routes.DEVICE_CONFIG) }
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 50.dp, end = 10.dp)
                ) {
                    AppMenu(
                        expanded = menuExpanded,
                        onDismiss = { menuExpanded = false },
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
            var showPreSaveWarning by remember { mutableStateOf(false) }

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
                onSaveClick = { showPreSaveWarning = true },
                onClearClick = { showClearDialog = true }
            )
            if (showPreSaveWarning) {
                PreSaveWarningDialog(
                    onDismiss = { showPreSaveWarning = false },
                    onContinue = {
                        showPreSaveWarning = false
                        showSaveDialog = true   // Now show the actual save dialog
                    }
                )
            }
            if (showSaveDialog) {
                SaveScanDialog(
                    onDismiss = { showSaveDialog = false },
                    onSave = { filename, _ ->
                        quickScanViewModel.saveScan(filename)
                        quickScanViewModel.clearAllData()
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
        composable(Routes.INVENTORY) {
            val inventoryViewModel: InventoryScanViewModel = viewModel(
                factory = InventoryScanViewModelFactory(
                    app.inventoryScanRepository,
                    app.rfidScanner,
                    app.settingsRepository
                )
            )

            val inventoryState by inventoryViewModel.state.collectAsState()
            val deviceState by sharedHomeViewModel.deviceState.collectAsState()
            var showSaveDialog by remember { mutableStateOf(false) }
            var showPreSaveWarning by remember { mutableStateOf(false) }
            // File picker launcher
            val filePickerLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    // Extract filename from URI
                    val fileName = extractFileName(uri.toString())

                    // For now, load mock data (real parsing in Phase 2)
                    val mockItems = generateMockInventoryItems()
                    inventoryViewModel.loadInventoryFile(mockItems, fileName)
                }
            }

            // Block back during scanning
            BackHandler(enabled = inventoryState.isScanning) {
                // Do nothing
            }

            InventoryScanScreen(
                state = inventoryState,
                deviceName = deviceState.deviceName,
                serialNumber = deviceState.serialNumber,
                firmwareVersion = deviceState.firmwareVersion,
                batteryPercent = deviceState.batteryPercent,
                isDeviceConnected = deviceState.isConnected,
                onBackClick = { navController.popBackStack() },
                onMenuClick = { /* TODO */ },
                onDeviceStatusClick = { navController.navigate(Routes.DEVICE_CONFIG) },
                onLoadFileClick = {
                    // TEMPORARY: Skip file picker for testing — REMOVE LATER
                    val mockItems = generateMockInventoryItems()
                    android.util.Log.d("TagScout", "Load clicked. Mock items count: ${mockItems.size}")
                    inventoryViewModel.loadInventoryFile(mockItems, "sample_inventory.xlsx")
                    android.util.Log.d("TagScout", "After load. hasFileLoaded=${inventoryViewModel.state.value.hasFileLoaded}, items=${inventoryViewModel.state.value.inventoryItems.size}")
                },
                onAntennaChange = { inventoryViewModel.setAntennaStrength(it) },
                onTabSelect = { inventoryViewModel.selectTab(it) },
                onPlayPauseClick = {
                    when {
                        inventoryState.isScanning -> inventoryViewModel.pauseScanning()
                        inventoryState.isPaused -> inventoryViewModel.resumeScanning()
                        else -> inventoryViewModel.startScanning()
                    }
                },
                onSaveClick = { showPreSaveWarning = true },
                onClearClick = { inventoryViewModel.clearAllData() }
            )
            if (showPreSaveWarning) {
                PreSaveWarningDialog(
                    onDismiss = { showPreSaveWarning = false },
                    onContinue = {
                        showPreSaveWarning = false
                        showSaveDialog = true   // Now show the actual save dialog
                    }
                )
            }
            if (showSaveDialog) {
                SaveScanDialog(
                    defaultPrefix = "inventory",
                    onDismiss = { showSaveDialog = false },
                    onSave = { filename, _ ->
                        inventoryViewModel.saveScan(filename)
                        inventoryViewModel.clearAllData()
                        showSaveDialog = false
                    }
                )
            }
        }
        composable(Routes.WRITE_TAG) {
            val writeTagViewModel: WriteTagViewModel = viewModel(
                factory = WriteTagViewModelFactory(
                    app.rfidScanner,
                    app.settingsRepository
                )
            )

            val writeTagState by writeTagViewModel.state.collectAsState()
            val deviceState by sharedHomeViewModel.deviceState.collectAsState()

            // Block back during operations
            BackHandler(
                enabled = writeTagState.phase == WritePhase.SEARCHING ||
                        writeTagState.phase == WritePhase.WRITING
            ) {
                // Do nothing
            }

            WriteTagScreen(
                state = writeTagState,
                deviceName = deviceState.deviceName,
                serialNumber = deviceState.serialNumber,
                firmwareVersion = deviceState.firmwareVersion,
                batteryPercent = deviceState.batteryPercent,
                isDeviceConnected = deviceState.isConnected,
                onBackClick = { navController.popBackStack() },
                onMenuClick = { /* TODO */ },
                onDeviceStatusClick = { navController.navigate(Routes.DEVICE_CONFIG) },
                onAntennaChange = { writeTagViewModel.setAntennaStrength(it) },
                onTargetEpcChange = { writeTagViewModel.setTargetEpc(it) },
                onNewEpcChange = { writeTagViewModel.setNewEpc(it) },
                onPasswordChange = { writeTagViewModel.setAccessPassword(it) },
                onFindTag = { writeTagViewModel.findTag() },
                onWriteTag = { writeTagViewModel.writeTag() },
                onRetry = { writeTagViewModel.retrySearch() },
                onStartOver = { writeTagViewModel.startOver() },
                onWriteAnother = { writeTagViewModel.writeAnotherTag() },
                canFindTag = writeTagViewModel.canFindTag(),
                canWriteTag = writeTagViewModel.canWriteTag(),
                isTargetEpcValid = writeTagViewModel.isTargetEpcValid(),
                isNewEpcValid = writeTagViewModel.isNewEpcValid()
            )
        }
        composable(Routes.KILL_TAG) {
            val killTagViewModel: KillTagViewModel = viewModel(
                factory = KillTagViewModelFactory(
                    app.rfidScanner,
                    app.settingsRepository
                )
            )

            val killTagState by killTagViewModel.state.collectAsState()
            val deviceState by sharedHomeViewModel.deviceState.collectAsState()

            // Block back during operations
            BackHandler(
                enabled = killTagState.phase == KillPhase.SEARCHING ||
                        killTagState.phase == KillPhase.KILLING
            ) {
                // Do nothing — let operation complete
            }

            KillTagScreen(
                state = killTagState,
                deviceName = deviceState.deviceName,
                serialNumber = deviceState.serialNumber,
                firmwareVersion = deviceState.firmwareVersion,
                batteryPercent = deviceState.batteryPercent,
                isDeviceConnected = deviceState.isConnected,
                onBackClick = { navController.popBackStack() },
                onMenuClick = { /* TODO */ },
                onDeviceStatusClick = { navController.navigate(Routes.DEVICE_CONFIG) },
                onAntennaChange = { killTagViewModel.setAntennaStrength(it) },
                onTargetEpcChange = { killTagViewModel.setTargetEpc(it) },
                onPasswordChange = { killTagViewModel.setKillPassword(it) },
                onFindTag = { killTagViewModel.findTag() },
                onKillTag = { killTagViewModel.killTag() },
                onConfirmIrreversibleChange = { killTagViewModel.setConfirmIrreversible(it) },
                onConfirmCorrectTagChange = { killTagViewModel.setConfirmCorrectTag(it) },
                onRetry = { killTagViewModel.retrySearch() },
                onStartOver = { killTagViewModel.startOver() },
                canFindTag = killTagViewModel.canFindTag(),
                canKillTag = killTagViewModel.canKillTag(),
                isTargetEpcValid = killTagViewModel.isTargetEpcValid()
            )
        }
    }
}
// Helper: extract a usable filename from a content URI
private fun extractFileName(uriString: String): String {
    // Content URIs have a path-like structure; grab the last segment
    val parts = uriString.split("/", "%2F", ":")
    val last = parts.lastOrNull() ?: "inventory.xlsx"
    // Decode common URL-encoded characters
    return last.replace("%20", " ").take(50)
}

// Helper: generate mock inventory items (used until Phase 2 parsing)
private fun generateMockInventoryItems(): List<com.snainfotech.tagscout.ui.screens.inventory.InventoryItem> {
    return listOf(
        // These match FakeRfidScanner — will become "matched"
        com.snainfotech.tagscout.ui.screens.inventory.InventoryItem(
            id = 1, epc = "3004A1B2C3D4E5F600000001", tid = "TID00000001", productName = "Widget Type A"
        ),
        com.snainfotech.tagscout.ui.screens.inventory.InventoryItem(
            id = 2, epc = "3004A2B3C4D5E6F700000002", tid = "TID00000002", productName = "Widget Type B"
        ),
        com.snainfotech.tagscout.ui.screens.inventory.InventoryItem(
            id = 3, epc = "3004A3B4C5D6E7F800000003", tid = "TID00000003", productName = "Gadget Mark 1"
        ),
        com.snainfotech.tagscout.ui.screens.inventory.InventoryItem(
            id = 4, epc = "3004A4B5C6D7E8F900000004", tid = "TID00000004", productName = "Gadget Mark 2"
        ),
        com.snainfotech.tagscout.ui.screens.inventory.InventoryItem(
            id = 5, epc = "3004A5B6C7D8E9F000000005", tid = "TID00000005", productName = "Sprocket S-100"
        ),
        com.snainfotech.tagscout.ui.screens.inventory.InventoryItem(
            id = 6, epc = "3004A6B7C8D9E0F100000006", tid = "TID00000006", productName = "Sprocket S-200"
        ),
        // These do NOT match — will stay missing
        com.snainfotech.tagscout.ui.screens.inventory.InventoryItem(
            id = 7, epc = "3004FF00FF00FF00FF000001", tid = "TIDFF000001", productName = "Bolt B-1"
        ),
        com.snainfotech.tagscout.ui.screens.inventory.InventoryItem(
            id = 8, epc = "3004FF11FF11FF11FF000002", tid = "TIDFF000002", productName = "Bolt B-2"
        ),
        com.snainfotech.tagscout.ui.screens.inventory.InventoryItem(
            id = 9, epc = "3004FF22FF22FF22FF000003", tid = "TIDFF000003", productName = "Nut N-1"
        ),
        com.snainfotech.tagscout.ui.screens.inventory.InventoryItem(
            id = 10, epc = "3004FF33FF33FF33FF000004", tid = "TIDFF000004", productName = "Nut N-2"
        )
    )
}