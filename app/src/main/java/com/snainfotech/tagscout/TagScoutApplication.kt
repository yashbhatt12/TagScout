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
    // RFID Scanner — fake for now, will be Bluebird SDK later
    val rfidScanner: com.snainfotech.tagscout.sdk.RfidScanner by lazy {
        com.snainfotech.tagscout.sdk.FakeRfidScanner()
    }
}