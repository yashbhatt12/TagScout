package com.snainfotech.tagscout.sdk

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.Log
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import com.snainfotech.tagscout.BuildConfig
import com.snainfotech.tagscout.data.reader.DesktopReaderUsb
import com.snainfotech.tagscout.sdk.Dr90xProtocol.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** Serial session with the DR90X desktop reader. */
sealed interface Dr90xState {
    /** No session: reader unplugged, no USB permission, or not started. */
    data object Closed : Dr90xState

    /** Port opening / handshake / setup in progress. */
    data object Opening : Dr90xState

    /**
     * Reader answered; tags can be read through [Dr90xReader.readTags].
     * @property region   region code the reader reported after setup (India = 9), null if unknown
     * @property powerDbm read power the reader reported after setup, null if unknown
     */
    data class Ready(
        val version: String,
        val region: Int? = null,
        val powerDbm: Int? = null
    ) : Dr90xState {
        val regionOk: Boolean get() = region == Dr90xProtocol.REGION_INDIA
    }

    /** Plugged in but the serial route is not usable; keyboard mode is the fallback. */
    data class Failed(val reason: String) : Dr90xState
}

/**
 * Talks to the Bluebird DR90X desktop reader over its USB serial port, using
 * the protocol in [Dr90xProtocol].
 *
 * It follows [DesktopReaderUsb]: when the reader is plugged in and USB access
 * is granted, it opens the port, checks the reader answers, sets the region to
 * India and the read power for the pad; when the reader is unplugged, it
 * closes. Screens only watch [state] / [isReady] and collect [readTags] while
 * they are listening.
 *
 * In debug builds every frame sent and received is logged under the
 * "Dr90xReader" tag (adb logcat -s Dr90xReader).
 *
 * Create once (TagScoutApplication) and call [start].
 */
class Dr90xReader(context: Context) {

    private val usb = context.applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<Dr90xState>(Dr90xState.Closed)
    val state: StateFlow<Dr90xState> = _state.asStateFlow()

    val isReady: StateFlow<Boolean> =
        state.map { it is Dr90xState.Ready }.stateIn(scope, SharingStarted.Eagerly, false)

    /** Every complete frame from the reader, for whoever is listening. */
    private val frames = MutableSharedFlow<Dr90xProtocol.Frame>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private class Session(
        val device: UsbDevice,
        val port: UsbSerialPort,
        val io: SerialInputOutputManager,
        val encoder: Dr90xProtocol.Encoder = Dr90xProtocol.Encoder()
    )

    private val sessionLock = Mutex()
    private val writeLock = Mutex()
    @Volatile private var session: Session? = null
    private var started = false

    // ── Lifecycle ────────────────────────────────────────────────────────

    fun start() {
        if (started) return
        started = true
        scope.launch {
            DesktopReaderUsb.status.collect { status ->
                sessionLock.withLock {
                    val device = status.device
                    if (status.state == DesktopReaderUsb.State.READY && device != null) {
                        if (session?.device?.deviceName != device.deviceName) {
                            closeLocked()
                            openLocked(device)
                        }
                    } else {
                        closeLocked()
                    }
                }
            }
        }
    }

    /** Close and reopen the session, e.g. from a "Retry" button after Failed. */
    fun reconnect() {
        scope.launch {
            sessionLock.withLock {
                closeLocked()
                val status = DesktopReaderUsb.status.value
                val device = status.device
                if (status.state == DesktopReaderUsb.State.READY && device != null) openLocked(device)
            }
        }
    }

    private suspend fun openLocked(device: UsbDevice) {
        _state.value = Dr90xState.Opening
        Log.i(TAG, "Opening ${device.idString()}")

        val driver = PROBER.probeDevice(device)
            ?: return failLocked("No serial interface on ${device.idString()}")
        val port = driver.ports.firstOrNull()
            ?: return failLocked("No serial port on ${device.idString()}")
        val connection = usb.openDevice(device)
            ?: return failLocked("USB open refused (permission?)")

        try {
            port.open(connection)
            port.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            runCatching { port.setDTR(true) }
            runCatching { port.setRTS(true) }
        } catch (e: Exception) {
            runCatching { port.close() }
            runCatching { connection.close() }
            return failLocked("Serial port open failed: ${e.message}")
        }

        val splitter = Dr90xProtocol.FrameSplitter(
            onFrame = { frame ->
                if (LOG_FRAMES) Log.d(TAG, "RX $frame")
                frames.tryEmit(frame)
            },
            onNoise = { bytes -> if (LOG_FRAMES) Log.d(TAG, "RX noise ${bytes.toHex(" ")}") }
        )
        val io = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) = splitter.feed(data)

            override fun onRunError(e: Exception) {
                Log.w(TAG, "Serial read ended: ${e.message}")
                scope.launch {
                    sessionLock.withLock {
                        if (session?.port === port) failLocked("Connection lost: ${e.message}")
                    }
                }
            }
        })
        val s = Session(device, port, io)
        session = s
        io.start()

        // Handshake: any valid frame back proves the reader talks.
        val alive = AtomicBoolean(false)
        val version: String?
        try {
            version = coroutineScope {
                val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                    frames.collect { if (it.checksumOk) alive.set(true) }
                }
                // Stop any read left running (trigger press, previous app).
                command(s) { it.stopRead() }
                delay(150)
                val reply = request(s, Dr90xProtocol.CMD_GET_MCU_VERSION, HANDSHAKE_TIMEOUT_MS) { it.getMcuVersion() }
                watcher.cancel()
                reply?.let(Dr90xProtocol::describeMcuVersion)
            }
        } catch (e: Exception) {
            return failLocked("Handshake failed: ${e.message}")
        }
        if (version == null && !alive.get()) {
            return failLocked("No reply from reader; keyboard mode only")
        }

        val ready = try {
            configure(s, version ?: "unknown")
        } catch (e: Exception) {
            Log.w(TAG, "Setup failed: ${e.message}")
            Dr90xState.Ready(version ?: "unknown")
        }
        Log.i(TAG, "Ready: $ready")
        if (!ready.regionOk) Log.w(TAG, "Reader region is ${ready.region}, not India (${Dr90xProtocol.REGION_INDIA})")
        _state.value = ready
    }

    /**
     * Region and read power. Best effort: each step is logged, and a reader that
     * ignores a step still ends Ready (with what it reported) so reading works.
     */
    private suspend fun configure(s: Session, version: String): Dr90xState.Ready {
        // Region: India only. Set it if the reader reports anything else (or nothing).
        var region = request(s, Dr90xProtocol.CMD_GET_REGION) { it.getRegion() }
            ?.let(Dr90xProtocol::regionFrom)
        if (region != REGION) {
            Log.w(TAG, "Region reported $region; setting ${REGION} (India)")
            val ack = request(s, Dr90xProtocol.CMD_SET_REGION) { it.setRegion(REGION) }
            if (ack == null || Dr90xProtocol.failureCode(ack) != null) Log.w(TAG, "Set region not confirmed: $ack")
            region = request(s, Dr90xProtocol.CMD_GET_REGION) { it.getRegion() }
                ?.let(Dr90xProtocol::regionFrom)
        }

        // Read power: gain mode first, then power ~100 ms later, as the SDK does.
        request(s, Dr90xProtocol.CMD_SET_GAIN_MODE) { it.setGainModeFor(READ_POWER_DBM) }
        delay(100)
        val powerAck = request(s, Dr90xProtocol.CMD_SET_POWER) { it.setPower(READ_POWER_DBM) }
        if (powerAck == null || Dr90xProtocol.failureCode(powerAck) != null) {
            Log.w(TAG, "Set power not confirmed: $powerAck")
        }
        val power = request(s, Dr90xProtocol.CMD_GET_POWER) { it.getPower() }
            ?.let(Dr90xProtocol::powerDbmFrom)

        // Settings block (keyboard output, TID mode, RSSI tracking...): logged raw
        // in debug builds until its layout is confirmed.
        request(s, Dr90xProtocol.CMD_GET_USER_SETTINGS) { it.getUserSettings() }

        return Dr90xState.Ready(version = version, region = region, powerDbm = power)
    }

    private fun closeLocked() {
        val s = session
        session = null
        if (s != null) {
            Log.i(TAG, "Closing ${s.device.idString()}")
            // Best effort: leave the reader idle. Fails harmlessly if it was unplugged.
            runCatching { s.port.write(s.encoder.stopRead(), 200) }
            s.io.listener = null  // closing the port would otherwise report a "connection lost"
            runCatching { s.io.stop() }
            runCatching { s.port.close() }
        }
        _state.value = Dr90xState.Closed
    }

    private fun failLocked(reason: String) {
        Log.w(TAG, reason)
        closeLocked()
        _state.value = Dr90xState.Failed(reason)
    }

    // ── Reading ──────────────────────────────────────────────────────────

    /**
     * Reads tags until the collector cancels. Starts an inventory on the
     * reader when collection starts and stops it when collection ends.
     *
     * One piece at a time: when a tag is read, the reader keeps listening for
     * [pickWindowMs] and reports only the strongest tag of that moment, so a
     * tray beside the pad doesn't win over the piece on it.
     *
     * A tag that stays in the field is reported once; it is reported again
     * only after it has not been seen for [repeatWindowMs]. Tags that were
     * seen but not reported (weaker neighbours) follow the same rule, so they
     * don't take over when the piece is lifted off.
     *
     * Fails with IllegalStateException if the reader is not [Dr90xState.Ready].
     */
    fun readTags(
        withTid: Boolean = true,
        pickWindowMs: Long = PICK_WINDOW_MS,
        repeatWindowMs: Long = REPEAT_WINDOW_MS
    ): Flow<ScannedTag> = callbackFlow {
        val s = session ?: throw IllegalStateException("DR90X not connected")
        check(_state.value is Dr90xState.Ready) { "DR90X not ready" }

        val inbox = Channel<Dr90xProtocol.TagRead>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            frames.collect { frame ->
                Dr90xProtocol.failureCode(frame)?.let { code ->
                    Log.w(TAG, "Reader error 0x%02X on code 0x%02X".format(code, frame.code))
                }
                Dr90xProtocol.tagFrom(frame)?.let { inbox.trySend(it) }
            }
        }

        val picker = launch {
            val lastSeen = HashMap<String, Long>()
            while (true) {
                val first = inbox.receive()
                val windowStart = SystemClock.elapsedRealtime()
                if (pickWindowMs > 0) delay(pickWindowMs)
                val round = mutableListOf(first)
                while (true) round += (inbox.tryReceive().getOrNull() ?: break)

                val best = round.maxBy { it.rssi ?: Int.MIN_VALUE }
                val previous = lastSeen[best.epc]
                val now = SystemClock.elapsedRealtime()
                round.forEach { lastSeen[it.epc] = now }
                if (lastSeen.size > 500) lastSeen.entries.removeAll { now - it.value > repeatWindowMs * 4 }

                if (previous == null || windowStart - previous > repeatWindowMs) {
                    if (LOG_FRAMES && round.size > 1) {
                        val others = round.filter { it.epc != best.epc }.distinctBy { it.epc }
                            .joinToString { "…${it.epc.takeLast(6)} ${it.rssi}" }
                        Log.d(TAG, "Picked …${best.epc.takeLast(6)} ${best.rssi} dBm over: $others")
                    }
                    send(ScannedTag(epc = best.epc, rssi = best.rssi ?: 0, tid = best.tid))
                }
            }
        }

        command(s) { it.startRead(withTid) }

        awaitClose {
            collector.cancel()
            picker.cancel()
            inbox.close()
            scope.launch { runCatching { command(s) { it.stopRead() } } }
        }
    }

    // ── Writing ──────────────────────────────────────────────────────────

    /** Builds and writes one command; the lock keeps sequence numbers in order. */
    private suspend fun command(s: Session, build: (Dr90xProtocol.Encoder) -> ByteArray) {
        writeLock.withLock {
            val bytes = build(s.encoder)
            if (LOG_FRAMES) Log.d(TAG, "TX ${bytes.toHex(" ")}")
            s.port.write(bytes, WRITE_TIMEOUT_MS)
        }
    }

    /**
     * Sends a command and waits for the reply with [code] (replies carry the
     * command's code). Null if none arrives within [timeoutMs].
     */
    private suspend fun request(
        s: Session,
        code: Int,
        timeoutMs: Long = REPLY_TIMEOUT_MS,
        build: (Dr90xProtocol.Encoder) -> ByteArray
    ): Dr90xProtocol.Frame? = withTimeoutOrNull(timeoutMs) {
        coroutineScope {
            val reply = async(start = CoroutineStart.UNDISPATCHED) { frames.first { it.code == code } }
            command(s, build)
            reply.await()
        }
    }

    private fun UsbDevice.idString() = "%04X:%04X %s".format(vendorId, productId, deviceName)

    private companion object {
        const val TAG = "Dr90xReader"
        const val BAUD_RATE = 115_200
        const val WRITE_TIMEOUT_MS = 500
        const val HANDSHAKE_TIMEOUT_MS = 2_000L
        const val REPLY_TIMEOUT_MS = 800L

        /** India; the only region TagScout runs in for now. */
        const val REGION = Dr90xProtocol.REGION_INDIA

        /**
         * Read power for the pad, in dBm (5..27). Low so it reads the piece on
         * the pad, not the tray beside it. Tune on the real pad.
         */
        const val READ_POWER_DBM = 15

        /** How long to keep listening after the first read before picking the strongest tag. */
        const val PICK_WINDOW_MS = 300L

        /** A tag must be out of the field this long before it is reported again. */
        const val REPEAT_WINDOW_MS = 1_500L

        /** Log every frame in debug builds while the protocol is being verified. */
        val LOG_FRAMES: Boolean = BuildConfig.DEBUG

        /** The DR90X is a CDC ACM device; tell the library which driver to use for its IDs. */
        val PROBER = UsbSerialProber(
            ProbeTable().apply {
                DesktopReaderUsb.PRODUCT_IDS.forEach { pid ->
                    addProduct(DesktopReaderUsb.VENDOR_ID, pid, CdcAcmSerialDriver::class.java)
                }
            }
        )
    }
}