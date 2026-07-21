package com.snainfotech.tagscout.sdk

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
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
    // BTReader.getReader(context, handler) hands back ONE shared instance keyed
    // to this (context, handler) pair — every call in this class routes through it.
    //
    // The Handler runs on its OWN dedicated background thread, not the main/UI
    // thread. This turned out to matter: some SDK calls (observed with BT_Connect)
    // appear to be blocking wrappers that internally pump this same message queue
    // while waiting for a reply — if that queue were the main thread's, a message
    // handled during that internal pump could trigger another blocking call and
    // recurse into the same call stack, eventually overflowing it. Isolating the
    // SDK's callback thread from the UI thread avoids that entirely.
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var reader: BTReader? = null

    private fun getReader(): BTReader {
        reader?.let { return it }

        val thread = HandlerThread("BluebirdRfidScannerThread").apply { start() }
        handlerThread = thread

        val h = object : Handler(thread.looper) {
            override fun handleMessage(msg: Message) {
                routeMessage(msg)
            }
        }
        handler = h
        val r = BTReader.getReader(context, h)

        // Required once, right after getReader() — nothing else works reliably
        // until this succeeds (this was the missing piece that made BT_Enable()
        // and BT_GetPairedDevices() silently fail/return null).
        val opened = r.SD_Open()
        android.util.Log.d("BluebirdRfidScanner", "SD_Open() returned: $opened")

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

    // Fires once when BT_Enable() actually finishes (it's async — returns
    // immediately, real completion arrives later as SLED_BT_STATE_CHANGED).
    private var pendingBtStateChanged: (() -> Unit)? = null

    // Fires once when a BT_Disconnect() we asked for is actually confirmed
    // (also async — see connect() below for why this matters).
    private var pendingDisconnectConfirm: (() -> Unit)? = null

    private fun routeMessage(msg: Message) {
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
                val address = bundle?.getString(SDConsts.BT_BUNDLE_ADDR_KEY) ?: ""
                // IMPORTANT: don't call SD_GetSerialNumber()/SD_GetVersion() synchronously
                // here — calling them from directly inside handleMessage caused infinite
                // recursion (the SDK's blocking calls appear to pump this same message
                // queue while waiting for a reply). Defer to a fresh dispatch instead.
                handler?.post {
                    val serial = runCatching { getReader().SD_GetSerialNumber() }.getOrNull().orEmpty()
                    val firmware = runCatching { getReader().SD_GetVersion() }.getOrNull().orEmpty()
                    connectionCallback?.invoke(
                        ConnectionEvent.Connected(
                            deviceName = name,
                            address = address,
                            serialNumber = serial,
                            firmwareVersion = firmware
                        )
                    )
                }
            }
            SDConsts.BTCmdMsg.SLED_BT_ACL_DISCONNECTED -> {
                connectionCallback?.invoke(ConnectionEvent.Disconnected)
                pendingDisconnectConfirm?.invoke()
            }
            SDConsts.BTCmdMsg.SLED_BT_STATE_CHANGED -> {
                pendingBtStateChanged?.invoke()
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
                    android.util.Log.d("BluebirdRfidScanner", "Raw tag data: [$data]")
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
        val result = getReader().RF_SetRadioPowerState(power)
        if (result != SDConsts.RFResult.SUCCESS) {
            android.util.Log.e("BluebirdRfidScanner", "RF_SetRadioPowerState($power) failed with code $result")
        }
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
                android.util.Log.d(
                    "BluebirdRfidScanner",
                    "RF_WRITE call for target=$cleanTarget new=$cleanNew returned code $callResult"
                )

                if (callResult != SDConsts.RFResult.SUCCESS) {
                    pendingWriteResult = null
                    WriteTagResult.Failure("RF_WRITE call rejected with code $callResult")
                } else {
                    val resultCode = withTimeoutOrNull(10_000) { deferred.await() }
                    android.util.Log.d("BluebirdRfidScanner", "RF_WRITE async result code: $resultCode")
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

    override suspend fun getPairedDevices(): List<PairedDevice> {
        return try {
            val reader = getReader()
            if (!reader.BT_IsEnabled()) {
                android.util.Log.d("BluebirdRfidScanner", "BT not enabled at SDK level, calling BT_Enable() and waiting")
                val deferred = CompletableDeferred<Unit>()
                pendingBtStateChanged = { if (!deferred.isCompleted) deferred.complete(Unit) }
                reader.BT_Enable()
                withTimeoutOrNull(5000) { deferred.await() }
                pendingBtStateChanged = null
                android.util.Log.d("BluebirdRfidScanner", "BT_IsEnabled now: ${reader.BT_IsEnabled()}")
            }
            val paired = reader.BT_GetPairedDevices()
            android.util.Log.d("BluebirdRfidScanner", "BT_GetPairedDevices returned: $paired")
            paired
                ?.map { device -> PairedDevice(name = device.name ?: modelName, address = device.address) }
                ?: emptyList()
        } catch (e: Exception) {
            android.util.Log.e("BluebirdRfidScanner", "getPairedDevices failed", e)
            emptyList()
        }
    }

    override suspend fun connect(address: String) {
        val reader = getReader()
        // Always attempt to clear any stale connection first — even on what
        // looks like a completely fresh reader/session. reader.BT_GetConnectState()
        // is unreliable for deciding this: it's always "not connected" on a
        // freshly-created reader object (e.g. right after the app restarts),
        // regardless of what the sled or Android's Bluetooth stack actually
        // think. If the app was previously killed without a clean disconnect,
        // the sled can still consider itself connected and silently ignore or
        // lose a fresh BT_Connect() call.
        //
        // Instead, just call BT_Disconnect() unconditionally and check its
        // own result: SUCCESS means a real disconnect was actually initiated
        // (and, like BT_Enable(), it's async — wait for SLED_BT_ACL_DISCONNECTED
        // before reconnecting). Any other result means there was nothing to
        // disconnect, so we can proceed straight to connecting.
        val disconnectResult = reader.BT_Disconnect()
        if (disconnectResult == SDConsts.BTResult.SUCCESS) {
            android.util.Log.d("BluebirdRfidScanner", "Clearing stale connection before reconnecting")
            val deferred = CompletableDeferred<Unit>()
            pendingDisconnectConfirm = { if (!deferred.isCompleted) deferred.complete(Unit) }
            withTimeoutOrNull(5000) { deferred.await() }
            pendingDisconnectConfirm = null
        }
        reader.BT_Connect(address)
    }

    override fun disconnect() {
        getReader().BT_Disconnect()
    }
}