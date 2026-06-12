package com.snainfotech.tagscout.sdk

import kotlinx.coroutines.flow.Flow

// A single tag detected by the scanner
data class ScannedTag(
    val epc: String,         // Tag ID (e.g., "3004A1B2C3D4E5F6")
    val rssi: Int,           // Signal strength in dBm (e.g., -45)
    val tid: String = ""     // Optional TID (some tags have this)
)

// Features that an RFID scanner implementation may or may not support.
// Used so the UI can hide controls for unsupported features instead of crashing.
enum class ScannerFeature {
    ANTENNA_POWER_CONTROL,   // Can adjust antenna power dynamically
    BATTERY_REPORTING,        // Can report device battery level
    BUZZER_CONTROL,           // Has a configurable buzzer
    SLEEP_TIMEOUT,            // Has auto-sleep timeout
    FIRMWARE_UPDATE,          // Supports OTA firmware updates
    MULTI_TAG_READING,        // Can read multiple tags simultaneously
    TID_READING,              // Can read TID (Tag ID) memory bank
    SINGLE_TAG_LOCATING       // Can locate a specific tag (geiger mode)
}

// The contract for any RFID scanner (real or fake)
interface RfidScanner {

    // Identity — which vendor/model this scanner represents
    val vendorName: String       // e.g., "Bluebird", "Zebra", "Impinj", "Fake"
    val modelName: String        // e.g., "RFR-901", "RFD40", "R700", "Simulator"

    // What this scanner is capable of
    val supportedFeatures: Set<ScannerFeature>

    // Start scanning - returns a stream of detected tags
    fun startScanning(): Flow<ScannedTag>

    // Stop scanning
    fun stopScanning()

    // Set antenna power (1-10 typically)
    // Only meaningful if ANTENNA_POWER_CONTROL is in supportedFeatures
    fun setAntennaPower(power: Int)

    // Is currently scanning?
    fun isScanning(): Boolean
}