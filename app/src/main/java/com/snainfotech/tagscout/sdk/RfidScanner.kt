package com.snainfotech.tagscout.sdk

import kotlinx.coroutines.flow.Flow

// A single tag detected by the scanner
data class ScannedTag(
    val epc: String,         // Tag ID (e.g., "3004A1B2C3D4E5F6")
    val rssi: Int,           // Signal strength in dBm (e.g., -45)
    val tid: String = ""     // Optional TID (some tags have this)
)

// The contract for any RFID scanner (real or fake)
interface RfidScanner {

    // Start scanning - returns a stream of detected tags
    fun startScanning(): Flow<ScannedTag>

    // Stop scanning
    fun stopScanning()

    // Set antenna power (1-10 typically)
    fun setAntennaPower(power: Int)

    // Is currently scanning?
    fun isScanning(): Boolean
}