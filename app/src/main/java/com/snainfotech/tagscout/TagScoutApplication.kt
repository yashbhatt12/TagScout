package com.snainfotech.tagscout

import android.app.Application
import com.snainfotech.tagscout.data.AppDatabase
import com.snainfotech.tagscout.data.repository.DeviceRepository
import com.snainfotech.tagscout.data.repository.InventoryScanRepository
import com.snainfotech.tagscout.data.repository.QuickScanRepository
import com.snainfotech.tagscout.data.repository.SettingsRepository

class TagScoutApplication : Application() {

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
    private val useRealHardware = false

    val rfidScanner: com.snainfotech.tagscout.sdk.RfidScanner by lazy {
        if (useRealHardware) {
            com.snainfotech.tagscout.sdk.BluebirdRfidScanner(this)
        } else {
            com.snainfotech.tagscout.sdk.FakeRfidScanner()
        }
    }
}