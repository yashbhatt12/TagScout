package com.snainfotech.tagscout.sdk

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.random.Random

class FakeRfidScanner : RfidScanner {

    override val vendorName: String = "Fake"
    override val modelName: String = "Simulator v1"
    override val supportedFeatures: Set<ScannerFeature> = setOf(
        ScannerFeature.ANTENNA_POWER_CONTROL,
        ScannerFeature.MULTI_TAG_READING,
        ScannerFeature.TID_READING,
        ScannerFeature.SINGLE_TAG_SCAN,
        ScannerFeature.EPC_WRITING,
        ScannerFeature.KILL_TAG
    )

    // Mutable list of "tags currently in range".
    // Operations like write and kill modify this list, simulating real behavior.
    private val activeTags = mutableListOf(
        "3004A1B2C3D4E5F600000001",
        "3004A2B3C4D5E6F700000002",
        "3004A3B4C5D6E7F800000003",
        "3004A4B5C6D7E8F900000004",
        "3004A5B6C7D8E9F000000005",
        "3004A6B7C8D9E0F100000006",
        "3004A7B8C9D0E1F200000007",
        "3004A8B9C0D1E2F300000008",
        "3004A9B0C1D2E3F400000009",
        "3004B0C1D2E3F4A50000000A",
        "3004B1C2D3E4F5A60000000B",
        "3004B2C3D4E5F6A70000000C"
    )

    private var scanning = false
    private var currentPower = 5

    // Simulated "factory default password" for all fake tags
    private val factoryPassword = RfidConstants.DEFAULT_PASSWORD

    // ============================================
    // CONTINUOUS SCANNING
    // ============================================

    override fun startScanning(): Flow<ScannedTag> = flow {
        scanning = true

        while (scanning) {
            val delayMs = Random.nextLong(50, 300)
            delay(delayMs)

            // Bail out if list is empty (all tags killed)
            if (activeTags.isEmpty()) {
                continue
            }

            // Pick a random tag from currently-active tags
            val randomTag = activeTags.random()

            val baseSignal = -65 + (currentPower * 3)
            val signalVariation = Random.nextInt(-5, 5)
            val rssi = baseSignal + signalVariation

            emit(
                ScannedTag(
                    epc = randomTag,
                    rssi = rssi,
                    tid = "TID${randomTag.takeLast(6)}"
                )
            )
        }
    }

    override fun stopScanning() {
        scanning = false
    }

    override fun setAntennaPower(power: Int) {
        currentPower = power.coerceIn(1, 10)
    }

    override fun isScanning(): Boolean = scanning

    // ============================================
    // SINGLE TAG OPERATIONS
    // ============================================

    override suspend fun findTag(targetEpc: String, timeoutMs: Long): FindTagResult {
        // Validate input
        if (!RfidConstants.isValidEpc(targetEpc)) {
            return FindTagResult.Error("Invalid EPC format")
        }

        val cleanTarget = targetEpc.trim().uppercase()

        // Simulate scanning delay (1-3 seconds)
        val scanDelay = Random.nextLong(1000, 3000).coerceAtMost(timeoutMs)
        delay(scanDelay)

        // Check if tag exists in our active list
        val exists = activeTags.any { it.equals(cleanTarget, ignoreCase = true) }

        return if (exists) {
            // Generate realistic signal based on current power
            val baseSignal = -65 + (currentPower * 3)
            val rssi = baseSignal + Random.nextInt(-5, 5)

            FindTagResult.Found(
                ScannedTag(
                    epc = cleanTarget,
                    rssi = rssi,
                    tid = "TID${cleanTarget.takeLast(6)}"
                )
            )
        } else {
            FindTagResult.NotFound
        }
    }

    override suspend fun writeEpc(
        targetEpc: String,
        newEpc: String,
        accessPassword: String
    ): WriteTagResult {
        // Validate inputs
        if (!RfidConstants.isValidEpc(targetEpc)) {
            return WriteTagResult.Failure("Invalid target EPC format")
        }
        if (!RfidConstants.isValidEpc(newEpc)) {
            return WriteTagResult.Failure("Invalid new EPC format")
        }

        val cleanTarget = targetEpc.trim().uppercase()
        val cleanNew = newEpc.trim().uppercase()

        // Simulate writing delay
        delay(Random.nextLong(1500, 3000))

        // Check tag exists
        val tagIndex = activeTags.indexOfFirst { it.equals(cleanTarget, ignoreCase = true) }
        if (tagIndex == -1) {
            return WriteTagResult.TagNotFound
        }

        // Check password (fake tags use factory default; any other password fails)
        val passwordToUse = if (accessPassword.isBlank()) factoryPassword else accessPassword
        if (passwordToUse != factoryPassword) {
            return WriteTagResult.WrongPassword
        }

        // Reject if newEpc already exists (would create duplicate)
        val duplicate = activeTags.any { it.equals(cleanNew, ignoreCase = true) }
        if (duplicate && !cleanNew.equals(cleanTarget, ignoreCase = true)) {
            return WriteTagResult.Failure("A tag with that EPC already exists nearby")
        }

        // Perform the "write"
        activeTags[tagIndex] = cleanNew

        return WriteTagResult.Success(verifiedEpc = cleanNew)
    }

    override suspend fun killTag(
        targetEpc: String,
        killPassword: String
    ): KillTagResult {
        // Validate input
        if (!RfidConstants.isValidEpc(targetEpc)) {
            return KillTagResult.Failure("Invalid EPC format")
        }

        val cleanTarget = targetEpc.trim().uppercase()

        // Simulate kill delay
        delay(Random.nextLong(1500, 3000))

        // Check tag exists
        val tagIndex = activeTags.indexOfFirst { it.equals(cleanTarget, ignoreCase = true) }
        if (tagIndex == -1) {
            return KillTagResult.TagNotFound
        }

        // Check password
        val passwordToUse = if (killPassword.isBlank()) factoryPassword else killPassword
        if (passwordToUse != factoryPassword) {
            return KillTagResult.WrongPassword
        }

        // Permanently remove
        activeTags.removeAt(tagIndex)

        return KillTagResult.Success
    }

    // ============================================
    // TEST UTILITIES
    // Visible only to tests / debugging
    // ============================================

    fun resetTagsForTesting() {
        activeTags.clear()
        activeTags.addAll(
            listOf(
                "3004A1B2C3D4E5F600000001", "3004A2B3C4D5E6F700000002", "3004A3B4C5D6E7F800000003",
                "3004A4B5C6D7E8F900000004", "3004A5B6C7D8E9F000000005", "3004A6B7C8D9E0F100000006",
                "3004A7B8C9D0E1F200000007", "3004A8B9C0D1E2F300000008", "3004A9B0C1D2E3F400000009",
                "3004B0C1D2E3F4A50000000A", "3004B1C2D3E4F5A60000000B", "3004B2C3D4E5F6A70000000C"
            )
        )
    }

    fun getActiveTagsForTesting(): List<String> = activeTags.toList()
}