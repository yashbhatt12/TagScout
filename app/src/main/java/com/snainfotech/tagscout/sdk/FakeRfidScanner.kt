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
        ScannerFeature.TID_READING
    )

    // A "pool" of fake tags. The scanner will pretend to detect these.
    private val fakeTagPool = listOf(
        "3004A1B2C3D4E5F6",
        "3004A2B3C4D5E6F7",
        "3004A3B4C5D6E7F8",
        "3004A4B5C6D7E8F9",
        "3004A5B6C7D8E9F0",
        "3004A6B7C8D9E0F1",
        "3004A7B8C9D0E1F2",
        "3004A8B9C0D1E2F3",
        "3004A9B0C1D2E3F4",
        "3004B0C1D2E3F4A5",
        "3004B1C2D3E4F5A6",
        "3004B2C3D4E5F6A7"
    )

    private var scanning = false
    private var currentPower = 5

    // Returns a "stream" of tags — emits new tags at random intervals
    override fun startScanning(): Flow<ScannedTag> = flow {
        scanning = true

        while (scanning) {
            // Wait a random short amount of time (50-300ms)
            val delayMs = Random.nextLong(50, 300)
            delay(delayMs)

            // Pick a random tag from the pool
            val randomTag = fakeTagPool.random()

            // Generate a realistic signal strength
            // Range: -65 dBm (weak) to -35 dBm (strong)
            // Higher power → stronger signal
            val baseSignal = -65 + (currentPower * 3)
            val signalVariation = Random.nextInt(-5, 5)
            val rssi = baseSignal + signalVariation

            // Emit (send out) the detected tag
            emit(
                ScannedTag(
                    epc = randomTag,
                    rssi = rssi,
                    tid = "TID${randomTag.takeLast(6)}"  // Fake TID
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
}