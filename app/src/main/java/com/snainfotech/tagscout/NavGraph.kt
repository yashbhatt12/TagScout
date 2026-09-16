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
import androidx.compose.runtime.saveable.rememberSaveable
import com.snainfotech.tagscout.ui.screens.inventory.InventoryItem
import com.snainfotech.tagscout.ui.screens.inventory.InventoryScanScreen
import com.snainfotech.tagscout.ui.screens.inventory.InventoryScanViewModel
import com.snainfotech.tagscout.ui.screens.inventory.InventoryScanViewModelFactory
import com.snainfotech.tagscout.ui.components.AppMenu
import com.snainfotech.tagscout.ui.components.ExitConfirmationDialog
import com.snainfotech.tagscout.ui.screens.about.AboutScreen
import com.snainfotech.tagscout.data.auth.AuthRepository
import com.snainfotech.tagscout.ui.screens.auth.AuthViewModel
import com.snainfotech.tagscout.ui.screens.auth.AuthViewModelFactory
import com.snainfotech.tagscout.ui.screens.auth.EmailVerificationScreen
import com.snainfotech.tagscout.ui.screens.auth.LoginScreen
import com.snainfotech.tagscout.ui.screens.auth.MfaEnrollScreen
import com.snainfotech.tagscout.ui.screens.auth.MfaVerifyScreen
import com.snainfotech.tagscout.ui.screens.auth.RegistrationScreen
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
import com.snainfotech.tagscout.ui.components.DeviceDisconnectedDialog
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
import com.snainfotech.tagscout.ui.screens.pickorder.PickOrderScreen
import com.snainfotech.tagscout.ui.screens.pickorder.PickOrderViewModel
import com.snainfotech.tagscout.ui.screens.pickorder.PickOrderViewModelFactory
import com.snainfotech.tagscout.ui.screens.pickorder.generateMockPickOrder
import com.snainfotech.tagscout.ui.screens.pickorder.generateMockFilename
import com.snainfotech.tagscout.data.file.OrderPickingExcelParser
import com.snainfotech.tagscout.ui.screens.orderpicking.ClearOrderWarningDialog
import com.snainfotech.tagscout.ui.screens.orderpicking.ConfirmPickDialog
import com.snainfotech.tagscout.ui.screens.orderpicking.OrderFileErrorDialog
import com.snainfotech.tagscout.ui.screens.orderpicking.OrderPickingScreen
import com.snainfotech.tagscout.ui.screens.orderpicking.OrderPickingViewModel
import com.snainfotech.tagscout.ui.screens.orderpicking.OrderPickingViewModelFactory
import com.snainfotech.tagscout.ui.screens.orderpicking.OrderSaveErrorDialog
import com.snainfotech.tagscout.ui.screens.orderpicking.OrderSaveSuccessDialog
import com.snainfotech.tagscout.ui.screens.orderpicking.generateMockOrderPickingItems
import com.snainfotech.tagscout.ui.screens.orderpicking.generateMockOrderFilename
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.snainfotech.tagscout.PermissionHelper
import com.snainfotech.tagscout.ui.components.BluetoothPermissionDeniedDialog
import com.snainfotech.tagscout.ui.components.BluetoothPermissionRationaleDialog
import com.snainfotech.tagscout.ui.components.PreSaveWarningDialog
import com.snainfotech.tagscout.data.file.InventoryExcelParser
import com.snainfotech.tagscout.ui.screens.locate.LocateTagScreen
import com.snainfotech.tagscout.ui.screens.locate.LocateTagViewModel
import com.snainfotech.tagscout.ui.screens.locate.LocateTagViewModelFactory
import com.snainfotech.tagscout.data.shop.ShopRepository
import com.snainfotech.tagscout.ui.screens.shop.CartScreen
import com.snainfotech.tagscout.ui.screens.shop.ProductCatalogScreen
import com.snainfotech.tagscout.ui.screens.shop.ShopViewModel
import com.snainfotech.tagscout.ui.screens.shop.ShopViewModelFactory
import com.snainfotech.tagscout.data.wms.ProductRepository
import com.snainfotech.tagscout.data.wms.WarehouseRepository
import com.snainfotech.tagscout.ui.screens.wms.ProductCatalogScreen
import com.snainfotech.tagscout.ui.screens.wms.ProductCatalogViewModel
import com.snainfotech.tagscout.ui.screens.wms.ProductCatalogViewModelFactory
import com.snainfotech.tagscout.ui.screens.wms.WarehouseSetupScreen
import com.snainfotech.tagscout.ui.screens.wms.WarehouseSetupViewModel
import com.snainfotech.tagscout.ui.screens.wms.WarehouseSetupViewModelFactory
import com.snainfotech.tagscout.ui.screens.wms.WmsMenuScreen
import com.snainfotech.tagscout.ui.screens.wms.WmsDashboardScreen
import com.snainfotech.tagscout.ui.screens.wms.WmsDashboardViewModel
import com.snainfotech.tagscout.ui.screens.wms.WmsDashboardViewModelFactory
import com.snainfotech.tagscout.ui.components.SecureScreen

private const val LOW_BATTERY_THRESHOLD = 15
private const val CRITICAL_BATTERY_THRESHOLD = 5

// All possible screen routes (like URLs for each screen)
object Routes {
    const val AUTH_CHECK = "auth_check"
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val EMAIL_VERIFICATION = "email_verification"
    const val MFA_ENROLL = "mfa_enroll"
    const val MFA_VERIFY = "mfa_verify"
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
    const val PICK_ORDER = "pick_order"
    const val ORDER_PICKING = "order_picking"
    const val LOCATE_TAG = "locate_tag"
    const val SHOP = "shop"
    const val CART = "cart"

    // Warehouse Management System
    const val WMS_MENU = "wms_menu"
    const val WMS_WAREHOUSE_SETUP = "wms_warehouse_setup"
    const val WMS_PRODUCT_CATALOG = "wms_product_catalog"
    const val WMS_DASHBOARD = "wms_dashboard"
}

@Composable
fun TagScoutNavGraph(
    navController: NavHostController = rememberNavController(),
    modifier: Modifier = Modifier
) {
    val app = LocalContext.current.applicationContext as TagScoutApplication

    // Create a SHARED HomeViewModel that all screens can read
    val shopViewModel: ShopViewModel = viewModel(
        factory = ShopViewModelFactory(ShopRepository())
    )
    val sharedHomeViewModel: HomeViewModel = viewModel(
        factory = HomeViewModelFactory(app.deviceRepository)
    )

    // Initialize device state ONCE at app startup

    NavHost(
        navController = navController,
        startDestination = Routes.AUTH_CHECK,
        modifier = modifier
    ) {
        // ============================================
        // HOME SCREEN
        // ============================================
        // ============================================
        // AUTH SCREENS
        // ============================================

        // Auth check — silent routing based on current Firebase state
        composable(Routes.AUTH_CHECK) {
            val authRepo = remember { AuthRepository() }

            LaunchedEffect(Unit) {
                val destination = when {
                    !authRepo.isLoggedIn -> Routes.LOGIN
                    !authRepo.isEmailVerified -> Routes.EMAIL_VERIFICATION
                    !authRepo.isSessionFresh -> {
                        authRepo.logout()
                        Routes.LOGIN
                    }
                    else -> Routes.HOME
                }
                navController.navigate(destination) {
                    popUpTo(Routes.AUTH_CHECK) { inclusive = true }
                }
            }
        }

        // Login screen
        composable(Routes.LOGIN) {
            SecureScreen()
            val authRepo = remember { AuthRepository() }
            val authViewModel: AuthViewModel = viewModel(
                factory = AuthViewModelFactory(authRepo)
            )
            val authState by authViewModel.state.collectAsState()
            val activity = LocalContext.current as android.app.Activity

            LaunchedEffect(authState.loginComplete) {
                if (authState.loginComplete) {
                    val dest = if (authRepo.isEmailVerified) Routes.HOME else Routes.EMAIL_VERIFICATION
                    navController.navigate(dest) { popUpTo(Routes.LOGIN) { inclusive = true } }
                }
            }
            LaunchedEffect(authState.mfaEnrollmentRequired) {
                if (authState.mfaEnrollmentRequired) { navController.navigate(Routes.MFA_ENROLL) }
            }
            LaunchedEffect(authState.mfaChallengeRequired) {
                if (authState.mfaChallengeRequired) { navController.navigate(Routes.MFA_VERIFY) }
            }

            LoginScreen(
                state = authState,
                onEmailChange = authViewModel::updateEmail,
                onPasswordChange = authViewModel::updatePassword,
                onLoginClick = { authViewModel.login(activity) },
                onRegisterClick = {
                    navController.navigate(Routes.REGISTER) { popUpTo(Routes.LOGIN) { inclusive = true } }
                }
            )
        }

        // MFA Enrollment (first-time 2FA setup after login)
        composable(Routes.MFA_ENROLL) {
            SecureScreen()
            val authRepo = remember { AuthRepository() }
            val authViewModel: AuthViewModel = viewModel(factory = AuthViewModelFactory(authRepo))
            val authState by authViewModel.state.collectAsState()
            val activity = LocalContext.current as android.app.Activity

            LaunchedEffect(Unit) {
                authRepo.getUserProfile().onSuccess { profile ->
                    if (profile.mobile.isNotBlank()) {
                        val phone = if (profile.mobile.startsWith("+")) profile.mobile else "+91${profile.mobile}"
                        authViewModel.updateMfaPhoneNumber(phone)
                    }
                }
            }
            LaunchedEffect(authState.loginComplete) {
                if (authState.loginComplete) {
                    val dest = if (authRepo.isEmailVerified) Routes.HOME else Routes.EMAIL_VERIFICATION
                    navController.navigate(dest) { popUpTo(Routes.LOGIN) { inclusive = true } }
                }
            }

            MfaEnrollScreen(
                state = authState,
                onPhoneNumberChange = authViewModel::updateMfaPhoneNumber,
                onSmsCodeChange = authViewModel::updateMfaSmsCode,
                onSendCode = { authViewModel.startMfaEnrollment(activity) },
                onVerifyCode = authViewModel::completeMfaEnrollment,
                onSkip = authViewModel::skipMfaEnrollment
            )
        }

        // MFA Verify (SMS code entry during login challenge)
        composable(Routes.MFA_VERIFY) {
            SecureScreen()
            val authRepo = remember { AuthRepository() }
            val authViewModel: AuthViewModel = viewModel(factory = AuthViewModelFactory(authRepo))
            val authState by authViewModel.state.collectAsState()

            LaunchedEffect(authState.loginComplete) {
                if (authState.loginComplete) {
                    val dest = if (authRepo.isEmailVerified) Routes.HOME else Routes.EMAIL_VERIFICATION
                    navController.navigate(dest) { popUpTo(Routes.LOGIN) { inclusive = true } }
                }
            }

            MfaVerifyScreen(
                state = authState,
                onSmsCodeChange = authViewModel::updateMfaSmsCode,
                onVerifyCode = authViewModel::completeMfaChallenge,
                onCancel = {
                    authViewModel.logout()
                    navController.navigate(Routes.LOGIN) { popUpTo(Routes.MFA_VERIFY) { inclusive = true } }
                }
            )
        }

        // Registration screen
        composable(Routes.REGISTER) {
            SecureScreen()
            val authRepo = remember { AuthRepository() }
            val authViewModel: AuthViewModel = viewModel(
                factory = AuthViewModelFactory(authRepo)
            )
            val authState by authViewModel.state.collectAsState()

            // Navigate after successful registration
            LaunchedEffect(authState.registrationComplete) {
                if (authState.registrationComplete) {
                    navController.navigate(Routes.EMAIL_VERIFICATION) {
                        popUpTo(Routes.REGISTER) { inclusive = true }
                    }
                }
            }

            RegistrationScreen(
                state = authState,
                onNameChange = authViewModel::updateName,
                onEmailChange = authViewModel::updateEmail,
                onMobileChange = authViewModel::updateMobile,
                onCompanyNameChange = authViewModel::updateCompanyName,
                onPasswordChange = authViewModel::updatePassword,
                onConfirmPasswordChange = authViewModel::updateConfirmPassword,
                onRegisterClick = authViewModel::register,
                onLoginClick = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.REGISTER) { inclusive = true }
                    }
                }
            )
        }

        // Email verification screen
        composable(Routes.EMAIL_VERIFICATION) {
            SecureScreen()
            val authRepo = remember { AuthRepository() }
            val authViewModel: AuthViewModel = viewModel(
                factory = AuthViewModelFactory(authRepo)
            )
            val authState by authViewModel.state.collectAsState()
            val userEmail = authRepo.currentUser?.email ?: ""

            // Navigate to Home when verified
            LaunchedEffect(authState.emailVerified) {
                if (authState.emailVerified) {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.EMAIL_VERIFICATION) { inclusive = true }
                    }
                }
            }

            EmailVerificationScreen(
                state = authState,
                email = userEmail,
                onCheckVerified = authViewModel::checkEmailVerified,
                onResendEmail = authViewModel::resendVerificationEmail,
                onLogout = {
                    authViewModel.logout()
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.EMAIL_VERIFICATION) { inclusive = true }
                    }
                }
            )
        }

        // ============================================
        // MAIN APP SCREENS (require auth)
        // ============================================

        composable(Routes.HOME) {
            val deviceState by sharedHomeViewModel.deviceState.collectAsState()

            var menuExpanded by rememberSaveable { mutableStateOf(false) }
            var showExitDialog by rememberSaveable { mutableStateOf(false) }
            val context = LocalContext.current
            val activity = context as? android.app.Activity

            // Fetch logged-in user's profile for the menu header
            val authRepo = remember { AuthRepository() }
            var userName by rememberSaveable { mutableStateOf("") }
            var userEmail by rememberSaveable { mutableStateOf(authRepo.currentUser?.email ?: "") }

            LaunchedEffect(Unit) {
                authRepo.getUserProfile().onSuccess { profile ->
                    userName = profile.name
                    userEmail = profile.email
                }
            }

            Box {
                HomeScreen(
                    deviceState = deviceState,
                    onMenuClick = { menuExpanded = true },
                    onConnectDeviceClick = { navController.navigate(Routes.CONNECT_DEVICE) },
                    onQuickScanClick = { navController.navigate(Routes.QUICK_SCAN) },
                    onInventoryClick = { navController.navigate(Routes.INVENTORY) },
                    onPickOrderClick = { navController.navigate(Routes.PICK_ORDER) },
                    onOrderPickingClick = { navController.navigate(Routes.ORDER_PICKING) },
                    onWriteTagClick = { navController.navigate(Routes.WRITE_TAG) },
                    onKillTagClick = { navController.navigate(Routes.KILL_TAG) },
                    onLocateTagClick = { navController.navigate(Routes.LOCATE_TAG) },
                    onShopClick = { navController.navigate(Routes.SHOP) },
                    onWmsClick = { navController.navigate(Routes.WMS_MENU) },
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
                        userName = userName,
                        userEmail = userEmail,
                        onAboutClick = { navController.navigate(Routes.ABOUT) },
                        onLogoutClick = {
                            AuthRepository().logout()
                            navController.navigate(Routes.LOGIN) {
                                popUpTo(0) { inclusive = true }
                            }
                        },
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
                val battery = deviceState.batteryPercent

                // E16: Check for battery recovery FIRST (works even when paused/not scanning)
                if (deviceState.isConnected) {
                    quickScanViewModel.checkBatteryRecovery(battery)
                }

                // Rest of the checks only fire during active scanning
                if (!scanState.isScanning || !deviceState.isConnected) return@LaunchedEffect

                when {
                    battery in 1..CRITICAL_BATTERY_THRESHOLD -> {
                        quickScanViewModel.handleCriticalBattery()
                    }
                    battery in (CRITICAL_BATTERY_THRESHOLD + 1)..LOW_BATTERY_THRESHOLD &&
                            !scanState.lowBatteryWarningAcknowledged -> {
                        quickScanViewModel.handleLowBattery()
                    }
                }
            }

            var showSaveDialog by rememberSaveable { mutableStateOf(false) }
            var showClearDialog by rememberSaveable { mutableStateOf(false) }
            var showPreSaveWarning by rememberSaveable { mutableStateOf(false) }

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
                factory = ConnectDeviceViewModelFactory(app.deviceRepository, app.rfidScanner)
            )

            val connectState by connectViewModel.state.collectAsState()
            val deviceState by sharedHomeViewModel.deviceState.collectAsState()
            val context = LocalContext.current

            // Sync visual state with shared connected device
            LaunchedEffect(deviceState.deviceId, deviceState.isConnected) {
                val connectedId = if (deviceState.isConnected) deviceState.deviceId else null
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
                        sharedHomeViewModel.startObservingConnection(app.rfidScanner)
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
                            sharedHomeViewModel.stopObservingConnection()
                            connectViewModel.disconnectCurrentDevice()
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
            var showSaveDialog by rememberSaveable { mutableStateOf(false) }
            var showPreSaveWarning by rememberSaveable { mutableStateOf(false) }
            val context = LocalContext.current

            // File picker — parses the chosen .xlsx into real inventory items
            val filePickerLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    val fileName = extractFileName(uri.toString())
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            when (val result = InventoryExcelParser.parse(input)) {
                                is InventoryExcelParser.ParseResult.Success ->
                                    inventoryViewModel.loadInventoryFile(result.items, fileName)
                                is InventoryExcelParser.ParseResult.Error ->
                                    android.widget.Toast.makeText(
                                        context, result.message, android.widget.Toast.LENGTH_LONG
                                    ).show()
                            }
                        }
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(
                            context,
                            "Could not read the file: ${e.message ?: e.javaClass.simpleName}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }

            // Save launcher — writes the reconciliation result to a real .xlsx
            val saveFileLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                )
            ) { uri ->
                if (uri != null) {
                    try {
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            InventoryExcelParser.write(output, inventoryState.inventoryItems)
                        }
                        inventoryViewModel.saveScan(extractFileName(uri.toString()))
                        inventoryViewModel.clearAllData()
                        android.widget.Toast.makeText(
                            context, "Inventory saved", android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(
                            context,
                            "Could not save the file: ${e.message ?: e.javaClass.simpleName}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            LaunchedEffect(deviceState.isConnected) {
                if (!deviceState.isConnected && (inventoryState.isScanning || inventoryState.isPaused)) {
                    inventoryViewModel.handleDeviceDisconnected()
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
                    filePickerLauncher.launch(
                        arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    )
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
            // Device disconnected dialog (E11)

            if (inventoryState.showDeviceDisconnectedDialog) {

                DeviceDisconnectedDialog(

                    onDismiss = { inventoryViewModel.dismissDeviceDisconnectedDialog() }

                )

            }

            // Session time warning at 30s remaining (previously never rendered —

            // the ViewModel set showTimeWarning but nothing displayed it, so

            // inventory scans auto-paused silently with no warning or extend option)

            if (inventoryState.showTimeWarning) {

                TimeWarningDialog(

                    onStopScan = {

                        inventoryViewModel.dismissTimeWarning()

                        inventoryViewModel.pauseScanning()

                    },

                    onExtend = { inventoryViewModel.extendTimer() }

                )

            }
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
                        showSaveDialog = false
                        // Launch the system save dialog; actual .xlsx write, DB save,
                        // and clear all happen in the saveFileLauncher callback.
                        val name = if (filename.endsWith(".xlsx")) filename else "$filename.xlsx"
                        saveFileLauncher.launch(name)
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

            // Auto-handle device disconnect (E11)
            LaunchedEffect(deviceState.isConnected) {
                if (!deviceState.isConnected && writeTagState.phase != WritePhase.ENTER_TARGET) {
                    writeTagViewModel.handleDeviceDisconnected()
                }
            }
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
            // Device disconnected dialog (E11)
            if (writeTagState.showDeviceDisconnectedDialog) {
                DeviceDisconnectedDialog(
                    onDismiss = { writeTagViewModel.dismissDeviceDisconnectedDialog() },
                    customMessage = "Your RFID reader has disconnected during the write operation."
                )
            }
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


            // Auto-handle device disconnect (E11)
            LaunchedEffect(deviceState.isConnected) {
                if (!deviceState.isConnected && killTagState.phase != KillPhase.ENTER_TARGET) {
                    killTagViewModel.handleDeviceDisconnected()
                }
            }
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
            // Device disconnected dialog (E11)
            if (killTagState.showDeviceDisconnectedDialog) {
                DeviceDisconnectedDialog(
                    onDismiss = { killTagViewModel.dismissDeviceDisconnectedDialog() },
                    customMessage = "Your RFID reader has disconnected. The kill operation was NOT completed — the tag is still active."
                )
            }
        }
        composable(Routes.PICK_ORDER) {
            val pickOrderViewModel: PickOrderViewModel = viewModel(
                factory = PickOrderViewModelFactory(
                    app.rfidScanner,
                    app.settingsRepository
                )
            )

            val pickOrderState by pickOrderViewModel.state.collectAsState()
            val deviceState by sharedHomeViewModel.deviceState.collectAsState()

            // Block back during active picking
            BackHandler(enabled = pickOrderState.isPicking) {
                // Do nothing
            }

            PickOrderScreen(
                state = pickOrderState,
                deviceName = deviceState.deviceName,
                serialNumber = deviceState.serialNumber,
                firmwareVersion = deviceState.firmwareVersion,
                batteryPercent = deviceState.batteryPercent,
                isDeviceConnected = deviceState.isConnected,
                onBackClick = { navController.popBackStack() },
                onMenuClick = { /* TODO */ },
                onDeviceStatusClick = { navController.navigate(Routes.DEVICE_CONFIG) },
                onLoadFileClick = {
                    // TEMPORARY: Skip file picker, load mock data
                    val mockItems = generateMockPickOrder()
                    pickOrderViewModel.loadPickOrder(mockItems, generateMockFilename())
                },
                onAntennaChange = { pickOrderViewModel.setAntennaStrength(it) },
                onTabSelect = { pickOrderViewModel.setSelectedTab(it) },
                onStartPicking = { pickOrderViewModel.startPicking() },
                onPausePicking = { pickOrderViewModel.pausePicking() },
                onResumePicking = { pickOrderViewModel.resumePicking() },
                onConfirmPick = { serialNo -> pickOrderViewModel.confirmPick(serialNo) },
                onLocateItem = { epc -> pickOrderViewModel.startLocateMode(epc) },
                onAddUnexpected = { epc -> pickOrderViewModel.addUnexpectedToOrder(epc) },
                onDismissSnackbar = { pickOrderViewModel.dismissWrongEpcSnackbar() },
                onCompleteOrder = { pickOrderViewModel.completeOrder() },
                onCancelOrder = { pickOrderViewModel.cancelOrder() },
                isItemReadyToConfirm = { item -> pickOrderViewModel.isItemReadyToConfirm(item) }
            )
        }
        composable(Routes.ORDER_PICKING) {
            val orderPickingViewModel: OrderPickingViewModel = viewModel(
                factory = OrderPickingViewModelFactory(
                    app.rfidScanner,
                    app.settingsRepository
                )
            )

            val orderState by orderPickingViewModel.state.collectAsState()
            val deviceState by sharedHomeViewModel.deviceState.collectAsState()
            val context = LocalContext.current

            // ============================================
            // FILE UPLOAD (User Story 1)
            // ============================================
            val openFileLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    orderPickingViewModel.beginFileParse()
                    val fileName = extractFileName(uri.toString())
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            when (val result = OrderPickingExcelParser.parse(input)) {
                                is OrderPickingExcelParser.ParseResult.Success ->
                                    orderPickingViewModel.onFileParsed(fileName, result.items)
                                is OrderPickingExcelParser.ParseResult.Error ->
                                    orderPickingViewModel.onFileParseFailed(result.message)
                            }
                        } ?: orderPickingViewModel.onFileParseFailed("Could not open the selected file.")
                    } catch (e: Exception) {
                        orderPickingViewModel.onFileParseFailed(
                            "Could not read the file: ${e.message ?: e.javaClass.simpleName}"
                        )
                    }
                }
            }

            // ============================================
            // SAVE RESULT FILE (User Story 5)
            // ============================================
            val saveFileLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                )
            ) { uri ->
                if (uri != null) {
                    orderPickingViewModel.beginSave()
                    try {
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            OrderPickingExcelParser.write(output, orderState.items)
                            orderPickingViewModel.onSaveSucceeded()
                        } ?: orderPickingViewModel.onSaveFailed("Could not create the output file.")
                    } catch (e: Exception) {
                        orderPickingViewModel.onSaveFailed(
                            "Could not save the file: ${e.message ?: e.javaClass.simpleName}"
                        )
                    }
                }
            }

            LaunchedEffect(deviceState.isConnected) {
                if (!deviceState.isConnected && (orderState.isScanning || orderState.isPaused)) {
                    orderPickingViewModel.handleDeviceDisconnected()
                }
            }

            // Block system back while actively scanning (User Story 4 — pause via the on-screen control instead)
            BackHandler(enabled = orderState.isScanning) {
                // Do nothing
            }

            OrderPickingScreen(
                state = orderState,
                deviceName = deviceState.deviceName,
                serialNumber = deviceState.serialNumber,
                firmwareVersion = deviceState.firmwareVersion,
                batteryPercent = deviceState.batteryPercent,
                isDeviceConnected = deviceState.isConnected,
                onBackClick = { navController.popBackStack() },
                onMenuClick = { /* TODO */ },
                onDeviceStatusClick = { navController.navigate(Routes.DEVICE_CONFIG) },
                onUploadFileClick = {
                    openFileLauncher.launch(
                        arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    )
                },
                onAntennaChange = { orderPickingViewModel.setAntennaStrength(it) },
                onPlayPauseClick = {
                    when {
                        orderState.isScanning -> orderPickingViewModel.pauseScanning()
                        orderState.isPaused -> orderPickingViewModel.resumeScanning()
                        else -> orderPickingViewModel.startScanning()
                    }
                },
                onSaveClick = {
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
                    saveFileLauncher.launch("pick_result_$stamp.xlsx")
                },
                onClearClick = { orderPickingViewModel.requestClear() }
            )

            // Pick confirmation dialog (User Story 3)
            orderState.proximityCandidate?.let { candidate ->
                ConfirmPickDialog(
                    candidate = candidate,
                    onConfirmPick = { orderPickingViewModel.confirmPick() },
                    onNotNow = { orderPickingViewModel.dismissProximityCandidate() }
                )
            }

            // Clear warning dialog (User Story 6)
            if (orderState.showClearWarningDialog) {
                ClearOrderWarningDialog(
                    onDismiss = { orderPickingViewModel.cancelClearRequest() },
                    onConfirmClear = { orderPickingViewModel.confirmClear() }
                )
            }

            // File parse error
            orderState.fileError?.let { message ->
                OrderFileErrorDialog(
                    message = message,
                    onDismiss = { orderPickingViewModel.dismissFileError() }
                )
            }

            // Save error
            orderState.saveError?.let { message ->
                OrderSaveErrorDialog(
                    message = message,
                    onDismiss = { orderPickingViewModel.dismissSaveError() }
                )
            }

            // Save success
            if (orderState.saveCompleted) {
                OrderSaveSuccessDialog(
                    fileName = orderState.fileName,
                    onDismiss = { orderPickingViewModel.acknowledgeSaveCompleted() }
                )
            }

            // Device disconnected dialog
            if (orderState.showDeviceDisconnectedDialog) {
                DeviceDisconnectedDialog(
                    onDismiss = { orderPickingViewModel.dismissDeviceDisconnectedDialog() },
                    customMessage = "Your RFID reader has disconnected. Scanning has been paused."
                )
            }
        }
        // ============================================
        // LOCATE TAG
        // ============================================
        composable(Routes.LOCATE_TAG) {
            val locateViewModel: LocateTagViewModel = viewModel(
                factory = LocateTagViewModelFactory(app.rfidScanner)
            )
            val locateState by locateViewModel.state.collectAsState()
            val deviceState by sharedHomeViewModel.deviceState.collectAsState()

            LocateTagScreen(
                state = locateState,
                isDeviceConnected = deviceState.isConnected,
                deviceName = deviceState.deviceName,
                serialNumber = deviceState.serialNumber,
                firmwareVersion = deviceState.firmwareVersion,
                batteryPercent = deviceState.batteryPercent,
                onBackClick = { navController.popBackStack() },
                onDeviceStatusClick = { navController.navigate(Routes.DEVICE_CONFIG) },
                onTargetEpcChange = locateViewModel::updateTargetEpc,
                onStartLocate = locateViewModel::startLocating,
                onStopLocate = locateViewModel::stopLocating
            )
        }

        // ============================================
        // SHOP / PRODUCT CATALOG
        // ============================================
        composable(Routes.SHOP) {
            val shopState by shopViewModel.state.collectAsState()

            ProductCatalogScreen(
                state = shopState,
                onBackClick = { navController.popBackStack() },
                onCartClick = { navController.navigate(Routes.CART) },
                onAddToCart = { product, qty -> shopViewModel.addToCart(product, qty) },
                onDismissMessage = shopViewModel::clearMessage,
                isInCart = shopViewModel::isInCart,
                getCartQuantity = shopViewModel::getCartQuantity
            )
        }

        composable(Routes.CART) {
            val shopState by shopViewModel.state.collectAsState()
            val context = LocalContext.current

            // Fetch user profile for the checkout email
            val authRepo = remember { AuthRepository() }
            var userName by rememberSaveable { mutableStateOf("") }
            var userEmail by rememberSaveable { mutableStateOf(authRepo.currentUser?.email ?: "") }
            var companyName by rememberSaveable { mutableStateOf("") }

            LaunchedEffect(Unit) {
                authRepo.getUserProfile().onSuccess { profile ->
                    userName = profile.name
                    userEmail = profile.email
                    companyName = profile.companyName
                }
            }

            CartScreen(
                state = shopState,
                onBackClick = { navController.popBackStack() },
                onRemoveItem = shopViewModel::removeFromCart,
                onUpdateQuantity = shopViewModel::updateCartQuantity,
                onCheckout = {
                    shopViewModel.initiateCheckout(userName, userEmail, companyName)
                },
                onDismissMessage = shopViewModel::clearMessage
            )
        }

        // ============================================
        // WMS — Warehouse Management System
        // ============================================
        composable(Routes.WMS_MENU) {
            WmsMenuScreen(
                onBackClick = { navController.popBackStack() },
                onWarehouseSetupClick = { navController.navigate(Routes.WMS_WAREHOUSE_SETUP) },
                onProductCatalogClick = { navController.navigate(Routes.WMS_PRODUCT_CATALOG) },
                onDashboardClick = { navController.navigate(Routes.WMS_DASHBOARD) }
                // Cycle Count, Pick List, Dispatch remain disabled placeholders.
            )
        }

        composable(Routes.WMS_WAREHOUSE_SETUP) {
            val vm: WarehouseSetupViewModel = viewModel(
                factory = WarehouseSetupViewModelFactory(WarehouseRepository())
            )
            val s by vm.state.collectAsState()
            WarehouseSetupScreen(
                state = s,
                onBackClick = { navController.popBackStack() },
                onSelectWarehouse = vm::selectWarehouse,
                onSelectRack = vm::selectRack,
                onCreateWarehouse = vm::createWarehouse,
                onCreateRack = vm::createRack,
                onCreateBin = vm::createBin,
                onDismissMessage = vm::clearMessage
            )
        }

        composable(Routes.WMS_PRODUCT_CATALOG) {
            val vm: ProductCatalogViewModel = viewModel(
                factory = ProductCatalogViewModelFactory(ProductRepository())
            )
            val s by vm.state.collectAsState()
            ProductCatalogScreen(
                state = s,
                onBackClick = { navController.popBackStack() },
                onCreateProduct = vm::createProduct,
                onDismissMessage = vm::clearMessage
            )
        }

        composable(Routes.WMS_DASHBOARD) {
            val vm: WmsDashboardViewModel = viewModel(
                factory = WmsDashboardViewModelFactory()
            )
            val s by vm.state.collectAsState()
            WmsDashboardScreen(
                state = s,
                onBackClick = { navController.popBackStack() },
                onSwitchView = vm::switchView,
                onSelectWarehouse = vm::selectWarehouse,
                onSelectRack = vm::selectRack,
                onSelectBin = vm::selectBin,
                onDismissMessage = vm::clearMessage
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