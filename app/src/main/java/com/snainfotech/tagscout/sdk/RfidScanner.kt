package com.snainfotech.tagscout.sdk

import kotlinx.coroutines.flow.Flow

// ============================================
// DATA MODELS
// ============================================

// A single tag detected by the scanner
data class ScannedTag(
    val epc: String,         // Tag ID (e.g., "3004A1B2C3D4E5F6")
    val rssi: Int,           // Signal strength in dBm (e.g., -45)
    val tid: String = ""     // Optional TID (some tags have this)
)

// Result of attempting to find a specific tag
sealed class FindTagResult {
    data class Found(val tag: ScannedTag) : FindTagResult()
    object NotFound : FindTagResult()
    data class Error(val reason: String) : FindTagResult()
}

// Result of writing data to a tag
sealed class WriteTagResult {
    data class Success(val verifiedEpc: String) : WriteTagResult()
    object TagNotFound : WriteTagResult()
    object WrongPassword : WriteTagResult()
    object Timeout : WriteTagResult()
    data class Failure(val reason: String) : WriteTagResult()
}

// Result of killing a tag
sealed class KillTagResult {
    object Success : KillTagResult()
    object TagNotFound : KillTagResult()
    object WrongPassword : KillTagResult()
    object Timeout : KillTagResult()
    data class Failure(val reason: String) : KillTagResult()
}

// ============================================
// CONNECTION LIFECYCLE (Phase 2)
// ============================================

// A change in connection state or an ongoing update from the sled, arriving
// over time — not just a single point-in-time check like the app relied on before.
sealed class ConnectionEvent {
    data class Connected(
        val deviceName: String,
        val serialNumber: String,
        val firmwareVersion: String
    ) : ConnectionEvent()

    object Disconnected : ConnectionEvent()

    data class BatteryUpdate(val percent: Int) : ConnectionEvent()
}

// ============================================
// LOCATE / PROXIMITY (Phase 2)
// ============================================

// A single proximity reading while actively locating one specific tag.
// proximity is 0 (not detected) to 100 (very close) — implementations derive
// this from RSSI, or from a vendor-native proximity value where available.
data class ProximityReading(
    val epc: String,
    val proximity: Int
)

// ============================================
// FEATURES
// ============================================

// Features that an RFID scanner implementation may or may not support.
// Used so the UI can hide controls for unsupported features instead of crashing.
enum class ScannerFeature {
    ANTENNA_POWER_CONTROL,   // Can adjust antenna power dynamically
    BATTERY_REPORTING,        // Can report device battery level
    BUZZER_CONTROL,           // Has a configurable buzzer
    SLEEP_TIMEOUT,            // Has auto-sleep timeout
    FIRMWARE_UPDATE,          // Supports OTA firmware updates
    MULTI_TAG_READING,        // Can read multiple tags simultaneously
    TID_READING,              // Can read TID memory bank
    SINGLE_TAG_LOCATING,      // Can locate a specific tag (geiger mode)
    SINGLE_TAG_SCAN,          // Can scan and stop at first matching tag
    EPC_WRITING,              // Can write EPC memory bank
    USER_MEMORY_WRITING,      // Can write user memory bank
    KILL_TAG                  // Can permanently kill tags
}

// ============================================
// CONSTANTS
// ============================================

object RfidConstants {
    // Default password used by tags that haven't been password-protected
    const val DEFAULT_PASSWORD = "00000000"

    // Valid EPC lengths in hex characters
    const val EPC_LENGTH_96_BIT = 24
    const val EPC_LENGTH_128_BIT = 32

    // Validates that an EPC string is well-formed
    fun isValidEpc(epc: String): Boolean {
        val cleaned = epc.trim().uppercase()
        if (cleaned.length != EPC_LENGTH_96_BIT && cleaned.length != EPC_LENGTH_128_BIT) {
            return false
        }
        return cleaned.matches(Regex("[0-9A-F]+"))
    }
}

// ============================================
// SCANNER INTERFACE
// ============================================

// The contract for any RFID scanner (real or fake)
interface RfidScanner {

    // Identity — which vendor/model this scanner represents
    val vendorName: String       // e.g., "Bluebird", "Zebra", "Impinj", "Fake"
    val modelName: String        // e.g., "RFR-901", "RFD40", "R700", "Simulator"

    // What this scanner is capable of
    val supportedFeatures: Set<ScannerFeature>

    // ============================================
    // CONTINUOUS SCANNING (existing)
    // ============================================

    // Start scanning - returns a stream of detected tags
    fun startScanning(): Flow<ScannedTag>

    // Stop scanning
    fun stopScanning()

    // Set antenna power (1-10 typically)
    fun setAntennaPower(power: Int)

    // Is currently scanning?
    fun isScanning(): Boolean

    // ============================================
    // SINGLE TAG OPERATIONS (new)
    // ============================================

    // Find a specific tag by EPC. Scans for up to timeoutMs milliseconds.
    // Returns Found with signal info, NotFound, or Error.
    suspend fun findTag(targetEpc: String, timeoutMs: Long = 5000): FindTagResult

    // Write a new EPC to a tag identified by its current EPC.
    // Uses access password (or default if empty).
    // Returns Success with verified new EPC, or specific failure reason.
    suspend fun writeEpc(
        targetEpc: String,
        newEpc: String,
        accessPassword: String = RfidConstants.DEFAULT_PASSWORD
    ): WriteTagResult

    // Permanently kill a tag identified by its EPC.
    // Uses kill password (or default if empty).
    // PERMANENT AND IRREVERSIBLE.
    suspend fun killTag(
        targetEpc: String,
        killPassword: String = RfidConstants.DEFAULT_PASSWORD
    ): KillTagResult

    // ============================================
    // CONNECTION LIFECYCLE (new — Phase 2)
    // ============================================

    // Continuous stream of connection/battery events. Screens observe this
    // instead of relying on a single point-in-time status check like before —
    // this is what makes disconnect detection and live battery reporting real.
    fun connectionEvents(): Flow<ConnectionEvent>

    // ============================================
    // LOCATE / PROXIMITY (new — Phase 2)
    // ============================================

    // Continuously reports proximity to one specific tag until stopLocating()
    // is called. Backs the "Locate" feature in Order Picking / Pick Order.
    fun locateTag(epc: String): Flow<ProximityReading>

    // Stops an active locate session started by locateTag().
    fun stopLocating()
}