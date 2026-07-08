package com.snainfotech.tagscout.sdk

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import co.kr.bluebird.sled.BTReader
import co.kr.bluebird.sled.SDConsts
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Real Bluebird BTReader-backed implementation of RfidScanner.
 *
 * VERIFIED against the vendor's own BBRFIDSampleBT sample app (handleMessage
 * logic in BTConnectivityFragment.java / InventoryFragment.java) rather than
 * guessed from the PDF manual alone — the manual documents message *types* but
 * defers the actual extraction code to "the sample," so this class's message
 * parsing is grounded in that real source rather than invented.
 *
 * Key mechanism: BTReader doesn't use a listener interface. You get one shared
 * reader via BTReader.getReader(context, handler), and every async event —
 * connect/disconnect, battery changes, tag reads, locate proximity — arrives as
 * an Android Handler Message:
 *   msg.what  = the message category (SDConsts.Msg.BTMsg / SDMsg / RFMsg)
 *   msg.arg1  = the specific command (e.g. RFCmdMsg.INVENTORY, BTCmdMsg.SLED_BT_ACL_CONNECTED)
 *   msg.arg2  = a result code or numeric value (e.g. RFResult.SUCCESS, battery percent)
 *   msg.obj   = payload — a Bundle for BT connect/disconnect events, or a raw
 *               String for tag/RF data (e.g. "3004A1B2...;rssi:-54.8")
 *
 * STATUS: connection lifecycle, continuous scanning, and locate are implemented
 * and verified. findTag/writeEpc/killTag are still TODO pending the same kind
 * of verification against RFAccessFragment.java in the sample — not guessed.
 */
class BluebirdRfidScanner(private val context: Context) : RfidScanner {

    override val vendorName: String = "Bluebird"
    override val modelName: String = "RFR-901"

    override val supportedFeatures: Set<ScannerFeature> = setOf(
        ScannerFeature.ANTENNA_POWER_CONTROL,
        ScannerFeature.BATTERY_REPORTING,
        ScannerFeature.BUZZER_CONTROL,
        ScannerFeature.SLEEP_TIMEOUT,
        ScannerFeature.FIRMWARE_UPDATE,
        ScannerFeature.MULTI_TAG_READING,
        ScannerFeature.TID_READING,
        ScannerFeature.SINGLE_TAG_LOCATING,
        ScannerFeature.SINGLE_TAG_SCAN,
        ScannerFeature.EPC_WRITING,
        ScannerFeature.USER_MEMORY_WRITING,
        ScannerFeature.KILL_TAG
    )

    // BTReader.getReader(context, handler) hands back ONE shared instance keyed
    // to this (context, handler) pair — every call in this class routes through it.
    private var handler: Handler? = null
    private var reader: BTReader? = null

    private fun getReader(): BTReader {
        reader?.let { return it }

        val h = object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                dispatchMessage(msg)
            }
        }
        handler = h
        val r = BTReader.getReader(context, h)
        reader = r
        return r
    }

    // Simple single-subscriber callback slots. The app only ever has one active
    // collector of each kind at a time (one screen scanning, one connection
    // observer, one locate session), so this is deliberately not a broadcast/bus.
    private var scanningCallback: ((ScannedTag) -> Unit)? = null
    private var connectionCallback: ((ConnectionEvent) -> Unit)? = null
    private var locateCallback: ((ProximityReading) -> Unit)? = null

    private fun dispatchMessage(msg: Message) {
        when (msg.what) {
            SDConsts.Msg.BTMsg -> handleBtMessage(msg)
            SDConsts.Msg.SDMsg -> handleSdMessage(msg)
            SDConsts.Msg.RFMsg -> handleRfMessage(msg)
        }
    }

    private fun handleBtMessage(msg: Message) {
        when (msg.arg1) {
            SDConsts.BTCmdMsg.SLED_BT_ACL_CONNECTED -> {
                val bundle = msg.obj as? Bundle
                val name = bundle?.getString(SDConsts.BT_BUNDLE_NAME_KEY) ?: modelName
                connectionCallback?.invoke(
                    ConnectionEvent.Connected(
                        deviceName = name,
                        serialNumber = runCatching { getReader().SD_GetSerialNumber() }.getOrNull().orEmpty(),
                        // SD_GetVersion() = the sled's own firmware; RF_GetRFIDVersion()
                        // is the RFID module's version if that's wanted alongside it later.
                        firmwareVersion = runCatching { getReader().SD_GetVersion() }.getOrNull().orEmpty()
                    )
                )
            }
            SDConsts.BTCmdMsg.SLED_BT_ACL_DISCONNECTED -> {
                connectionCallback?.invoke(ConnectionEvent.Disconnected)
            }
        }
    }

    private fun handleSdMessage(msg: Message) {
        if (msg.arg1 == SDConsts.SDCmdMsg.SLED_BATTERY_STATE_CHANGED) {
            connectionCallback?.invoke(ConnectionEvent.BatteryUpdate(msg.arg2))
        }
    }

    private fun handleRfMessage(msg: Message) {
        when (msg.arg1) {
            SDConsts.RFCmdMsg.INVENTORY, SDConsts.RFCmdMsg.READ -> {
                if (msg.arg2 == SDConsts.RFResult.SUCCESS) {
                    val data = msg.obj as? String ?: return
                    parseTagData(data)?.let { scanningCallback?.invoke(it) }
                }
            }
            SDConsts.RFCmdMsg.LOCATE -> {
                if (msg.arg2 == SDConsts.RFResult.SUCCESS) {
                    val data = msg.obj as? String ?: return
                    parseLocateData(data)?.let { locateCallback?.invoke(it) }
                }
            }
        }
    }

    // Parses the vendor's tag-data string format, e.g. "3004A1B2...;rssi:-54.8"
    // (verified against InventoryFragment.processReadData in the sample app).
    private fun parseTagData(raw: String): ScannedTag? {
        if (!raw.contains(";")) {
            return if (raw.isNotBlank()) ScannedTag(epc = raw, rssi = 0) else null
        }
        var epc = ""
        var rssi = 0
        for (part in raw.split(";")) {
            when {
                part.startsWith("rssi:") -> rssi = part.removePrefix("rssi:").toFloatOrNull()?.toInt() ?: 0
                epc.isEmpty() -> epc = part
            }
        }
        return if (epc.isNotBlank()) ScannedTag(epc = epc, rssi = rssi) else null
    }

    // Parses locate-mode data, e.g. "3004A1B2...;loc:64"
    private fun parseLocateData(raw: String): ProximityReading? {
        if (!raw.contains(";")) return null
        var epc = ""
        var proximity = 0
        for (part in raw.split(";")) {
            when {
                part.startsWith("loc:") -> proximity = part.removePrefix("loc:").toIntOrNull() ?: 0
                epc.isEmpty() -> epc = part
            }
        }
        return if (epc.isNotBlank()) ProximityReading(epc = epc, proximity = proximity) else null
    }

    // ============================================
    // CONTINUOUS SCANNING
    // ============================================

    override fun startScanning(): Flow<ScannedTag> = callbackFlow {
        scanningCallback = { tag -> trySend(tag) }
        val result = getReader().RF_PerformInventory(true, false, false)
        if (result != SDConsts.RFResult.SUCCESS) {
            close(IllegalStateException("RF_PerformInventory failed with code $result"))
        }
        awaitClose {
            scanningCallback = null
            runCatching { getReader().RF_StopInventory() }
        }
    }

    override fun stopScanning() {
        runCatching { getReader().RF_StopInventory() }
    }

    override fun setAntennaPower(power: Int) {
        getReader().RF_SetRadioPowerState(power)
    }

    override fun isScanning(): Boolean = scanningCallback != null

    // ============================================
    // CONNECTION LIFECYCLE
    // ============================================

    override fun connectionEvents(): Flow<ConnectionEvent> = callbackFlow {
        connectionCallback = { event -> trySend(event) }
        awaitClose { connectionCallback = null }
    }

    // ============================================
    // LOCATE / PROXIMITY
    // ============================================

    override fun locateTag(epc: String): Flow<ProximityReading> = callbackFlow {
        locateCallback = { reading -> trySend(reading) }
        val result = getReader().RF_PerformInventoryForLocating(epc)
        if (result != SDConsts.RFResult.SUCCESS) {
            close(IllegalStateException("RF_PerformInventoryForLocating failed with code $result"))
        }
        awaitClose {
            locateCallback = null
            runCatching { getReader().RF_StopInventory() }
        }
    }

    override fun stopLocating() {
        runCatching { getReader().RF_StopInventory() }
    }

    // ============================================
    // SINGLE TAG OPERATIONS — pending verification
    // ============================================
    // Not implementing these yet by design: the manual defers to "sample code"
    // for the exact call/callback shape here too, same as scanning did, and I'd
    // rather verify against RFAccessFragment.java (as we just did for scanning
    // and connection) than guess at write/kill on a permanent, irreversible operation.

    override suspend fun findTag(targetEpc: String, timeoutMs: Long): FindTagResult {
        TODO("Pending verification against RFAccessFragment.java in the sample app")
    }

    override suspend fun writeEpc(
        targetEpc: String,
        newEpc: String,
        accessPassword: String
    ): WriteTagResult {
        TODO("Pending verification against RFAccessFragment.java in the sample app")
    }

    override suspend fun killTag(targetEpc: String, killPassword: String): KillTagResult {
        TODO("Pending verification against RFAccessFragment.java in the sample app")
    }
}