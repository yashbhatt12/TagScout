package com.snainfotech.tagscout.sdk

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import co.kr.bluebird.sled.BTReader
import co.kr.bluebird.sled.SDConsts
import co.kr.bluebird.sled.SelectionCriterias
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

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
 * STATUS: connection lifecycle, continuous scanning, locate, findTag, writeEpc,
 * and killTag are all implemented and verified against the sample.
 *
 * Targeting one specific tag for write/kill (rather than whatever tag happens
 * to be in range) requires a SelectionCriterias filter, verified against
 * RFSelectionFragment.java and the PDF's documented RFResult codes:
 *   - memPos = SDConsts.RFMemType.EPC (matches the PDF's RFMemType.EPC = 1)
 *   - mask = the target EPC hex string
 *   - selectStartPosByte = 2 (byte offset into the EPC bank, skipping the PC
 *     word — inferred from the consistent "origin" RF_WRITE/RF_WriteTagID
 *     examples throughout the sample, all using startPos=2 for full-EPC ops;
 *     recommend confirming with one real write on day 1 of hardware access)
 *   - selectMaskLengthBit = EPC hex length in bits
 *   - actionPos = 0 (ASLINVA_DSLINVB — the sample's default "select this tag" action)
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

    // One-shot correlations for single-tag operations. Like the callbacks
    // above, these assume one active operation of each kind at a time —
    // true for how the app's screens are structured (Write Tag and Kill Tag
    // are separate, mutually-exclusive flows).
    private var pendingWriteResult: ((resultCode: Int) -> Unit)? = null
    private var pendingKillResult: ((resultCode: Int) -> Unit)? = null

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
            SDConsts.RFCmdMsg.WRITE -> pendingWriteResult?.invoke(msg.arg2)
            SDConsts.RFCmdMsg.KILL -> pendingKillResult?.invoke(msg.arg2)
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
    // EPC SELECTION (targets one specific tag for write/kill/find)
    // ============================================

    // Arms a selection filter matching one exact EPC, runs the given block,
    // then always clears the selection afterward — leaving it armed would
    // silently filter every subsequent scan/operation to just this one tag.
    private suspend fun <T> withEpcSelection(epc: String, block: suspend () -> T): T {
        val criteria = SelectionCriterias()
        criteria.makeCriteria(
            SDConsts.RFMemType.EPC,      // memPos — matches RFMemType.EPC = 1
            epc,                         // mask — the exact EPC to match
            2,                           // selectStartPosByte — see class doc
            epc.length * 4,              // selectMaskLengthBit — hex chars -> bits
            0                            // actionPos — ASLINVA_DSLINVB ("select this tag")
        )
        getReader().RF_SetSelection(criteria)
        try {
            return block()
        } finally {
            runCatching { getReader().RF_RemoveSelection() }
        }
    }

    private fun mapWriteResult(resultCode: Int?, newEpc: String): WriteTagResult = when (resultCode) {
        null -> WriteTagResult.Timeout
        SDConsts.RFResult.SUCCESS -> WriteTagResult.Success(verifiedEpc = newEpc)
        SDConsts.RFResult.INVALID_PASSWORD -> WriteTagResult.WrongPassword
        SDConsts.RFResult.NO_TAG_REPLY, SDConsts.RFResult.TAG_LOST -> WriteTagResult.TagNotFound
        SDConsts.RFResult.OUT_OF_RETRIES -> WriteTagResult.Timeout
        else -> WriteTagResult.Failure("Write failed with code $resultCode")
    }

    private fun mapKillResult(resultCode: Int?): KillTagResult = when (resultCode) {
        null -> KillTagResult.Timeout
        SDConsts.RFResult.SUCCESS -> KillTagResult.Success
        SDConsts.RFResult.INVALID_PASSWORD, SDConsts.RFResult.ZERO_KILL_PASSWORD -> KillTagResult.WrongPassword
        SDConsts.RFResult.NO_TAG_REPLY, SDConsts.RFResult.TAG_LOST -> KillTagResult.TagNotFound
        SDConsts.RFResult.OUT_OF_RETRIES -> KillTagResult.Timeout
        else -> KillTagResult.Failure("Kill failed with code $resultCode")
    }

    // ============================================
    // SINGLE TAG OPERATIONS
    // ============================================

    override suspend fun findTag(targetEpc: String, timeoutMs: Long): FindTagResult {
        val cleanTarget = targetEpc.trim().uppercase()
        return try {
            withEpcSelection(cleanTarget) {
                val found = withTimeoutOrNull(timeoutMs) {
                    callbackFlow {
                        scanningCallback = { tag -> trySend(tag) }
                        val result = getReader().RF_PerformInventory(true, true, false)
                        if (result != SDConsts.RFResult.SUCCESS) {
                            close(IllegalStateException("RF_PerformInventory failed with code $result"))
                        }
                        awaitClose {
                            scanningCallback = null
                            runCatching { getReader().RF_StopInventory() }
                        }
                    }.first { it.epc.equals(cleanTarget, ignoreCase = true) }
                }
                if (found != null) FindTagResult.Found(found) else FindTagResult.NotFound
            }
        } catch (e: Exception) {
            FindTagResult.Error(e.message ?: "Unknown error while finding tag")
        }
    }

    override suspend fun writeEpc(
        targetEpc: String,
        newEpc: String,
        accessPassword: String
    ): WriteTagResult {
        val cleanTarget = targetEpc.trim().uppercase()
        val cleanNew = newEpc.trim().uppercase()
        if (!RfidConstants.isValidEpc(cleanNew)) {
            return WriteTagResult.Failure("New EPC is not a valid EPC value")
        }
        return try {
            withEpcSelection(cleanTarget) {
                val deferred = CompletableDeferred<Int>()
                pendingWriteResult = { code -> if (!deferred.isCompleted) deferred.complete(code) }

                val callResult = getReader().RF_WRITE(
                    SDConsts.RFMemType.EPC,
                    2, // byte offset into the EPC bank, skipping the PC word — see class doc
                    cleanNew,
                    accessPassword,
                    true // enableSelection — only the tag matching our selection responds
                )

                if (callResult != SDConsts.RFResult.SUCCESS) {
                    pendingWriteResult = null
                    WriteTagResult.Failure("RF_WRITE call rejected with code $callResult")
                } else {
                    val resultCode = withTimeoutOrNull(10_000) { deferred.await() }
                    pendingWriteResult = null
                    mapWriteResult(resultCode, cleanNew)
                }
            }
        } catch (e: Exception) {
            WriteTagResult.Failure(e.message ?: "Unknown error while writing tag")
        }
    }

    override suspend fun killTag(targetEpc: String, killPassword: String): KillTagResult {
        val cleanTarget = targetEpc.trim().uppercase()
        return try {
            withEpcSelection(cleanTarget) {
                val deferred = CompletableDeferred<Int>()
                pendingKillResult = { code -> if (!deferred.isCompleted) deferred.complete(code) }

                val callResult = getReader().RF_KILL(
                    killPassword,
                    RfidConstants.DEFAULT_PASSWORD,
                    true // enableSelection — only the tag matching our selection responds
                )

                if (callResult != SDConsts.RFResult.SUCCESS) {
                    pendingKillResult = null
                    KillTagResult.Failure("RF_KILL call rejected with code $callResult")
                } else {
                    val resultCode = withTimeoutOrNull(10_000) { deferred.await() }
                    pendingKillResult = null
                    mapKillResult(resultCode)
                }
            }
        } catch (e: Exception) {
            KillTagResult.Failure(e.message ?: "Unknown error while killing tag")
        }
    }

    // ============================================
    // DEVICE DISCOVERY & CONNECT (Phase 2)
    // ============================================
    // Lists devices already paired via Android's own Bluetooth settings —
    // pair the sled there first. This doesn't perform discovery/pairing itself.

    override fun getPairedDevices(): List<PairedDevice> {
        return try {
            getReader().BT_GetPairedDevices()
                ?.map { device -> PairedDevice(name = device.name ?: modelName, address = device.address) }
                ?: emptyList()
        } catch (e: SecurityException) {
            // BLUETOOTH_CONNECT not granted at runtime yet
            emptyList()
        }
    }

    override fun connect(address: String) {
        getReader().BT_Connect(address)
    }

    override fun disconnect() {
        getReader().BT_Disconnect()
    }
}