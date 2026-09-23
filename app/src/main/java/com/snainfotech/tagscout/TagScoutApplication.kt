package com.snainfotech.tagscout

import android.app.Application
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.snainfotech.tagscout.data.AppDatabase
import com.snainfotech.tagscout.data.repository.DeviceRepository
import com.snainfotech.tagscout.data.repository.InventoryScanRepository
import com.snainfotech.tagscout.data.repository.QuickScanRepository
import com.snainfotech.tagscout.data.repository.SettingsRepository

class TagScoutApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Crashlytics: enable reporting only in release builds. Dev-side
        // crashes and stack traces from active development would otherwise
        // pollute the production console. Toggle here rather than at the
        // Gradle level so the plugin stays uniformly applied across variants
        // (mapping-file uploads etc. are still handled per build type).
        FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = !BuildConfig.DEBUG
    }

    // Database (created when first accessed)
    private val database by lazy {
        AppDatabase.getInstance(this)
    }

    // Repositories (created when first accessed)
    val quickScanRepository by lazy {
        QuickScanRepository(database.quickScanDao())
    }

    val inventoryScanRepository by lazy {
        InventoryScanRepository(database.inventoryScanDao())
    }

    val deviceRepository by lazy {
        DeviceRepository(database.deviceConnectionDao())
    }

    val settingsRepository by lazy {
        SettingsRepository(this)
    }

    // RFID Scanner — flip this one flag once the sled is in hand and BluebirdRfidScanner
    // is ready to test. Keeping Fake as the default until then so nothing regresses.
    // RFID Scanner — determined by build type:
    //   Debug builds  → FakeRfidScanner (safe for UI work without hardware)
    //   Release builds → BluebirdRfidScanner (real hardware)
    // Controlled via BuildConfig.USE_REAL_HARDWARE in app/build.gradle.kts.
    // No manual editing needed — just Run (debug) vs Build APK (release).
    val rfidScanner: com.snainfotech.tagscout.sdk.RfidScanner by lazy {
        if (BuildConfig.USE_REAL_HARDWARE) {
            com.snainfotech.tagscout.sdk.BluebirdRfidScanner(this)
        } else {
            com.snainfotech.tagscout.sdk.FakeRfidScanner()
        }
    }
}