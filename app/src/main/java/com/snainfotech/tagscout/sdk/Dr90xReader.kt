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
import com.snainfotech.tagscout.data.reader.DesktopReaderUsb
import com.snainfotech.tagscout.sdk.Dr90xProtocol.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Serial session with the DR90X desktop reader. */
sealed interface Dr90xState {
    /** No session: reader unplugged, no USB permission, or not started. */
    data object Closed : Dr90xState

    /** Port opening / handshake in progress. */
    data object Opening : Dr90xState

    /** Reader answered; tags can be read through [Dr90xReader.readTags]. */
    data class Ready(val version: String) : Dr90xState

    /** Plugged in but the serial route is not usable; keyboard mode is the fallback. */
    data class Failed(val reason: String) : Dr90xState
}

/**
 * Talks to the Bluebird DR90X desktop reader over its USB serial port, using
 * the protocol in [Dr90xProtocol].
 *
 * It follows [DesktopReaderUsb]: when the reader is plugged in and USB access
 * is granted, it opens the port and checks the reader answers; when the reader
 * is unplugged, it closes. Screens only watch [state] / [isReady] and collect
 * [readTags] while they are listening.
 *
 * Every frame sent and received is logged under the "Dr90xReader" tag while
 * the protocol is being verified (adb logcat -s Dr90xReader).
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

        // Handshake: stop any read left running (trigger press, previous app),
        // then ask for the version. Any valid frame back proves the reader talks.
        val alive = AtomicBoolean(false)
        val version = AtomicReference<String?>(null)
        try {
            withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) {
                coroutineScope {
                    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                        frames.collect { f ->
                            if (f.checksumOk || f.code == Dr90xProtocol.RSP_MCU_VERSION) alive.set(true)
                            if (f.code == Dr90xProtocol.RSP_MCU_VERSION && f.data.isNotEmpty()) {
                                version.set(Dr90xProtocol.describeMcuVersion(f))
                            }
                        }
                    }
                    command(s) { it.stopRead() }
                    delay(150)
                    command(s) { it.getMcuVersion() }
                    while (version.get() == null) delay(50)
                    watcher.cancel()
                }
            }
        } catch (e: Exception) {
            return failLocked("Handshake failed: ${e.message}")
        }

        if (alive.get() || version.get() != null) {
            val v = version.get() ?: "unknown"
            Log.i(TAG, "Ready, version: $v")
            _state.value = Dr90xState.Ready(v)
        } else {
            failLocked("No reply from reader; keyboard mode only")
        }
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
     * A tag that stays on the pad is reported once: it is reported again only
     * after it has not been seen for [repeatWindowMs] (lifted off and put back).
     *
     * Fails with IllegalStateException if the reader is not [Dr90xState.Ready].
     */
    fun readTags(withTid: Boolean = true, repeatWindowMs: Long = 1500): Flow<ScannedTag> = callbackFlow {
        val s = session ?: throw IllegalStateException("DR90X not connected")
        check(_state.value is Dr90xState.Ready) { "DR90X not ready" }

        val lastSeen = HashMap<String, Long>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            frames.collect { frame ->
                Dr90xProtocol.failureCode(frame)?.let { code ->
                    Log.w(TAG, "Reader error 0x%02X".format(code))
                }
                val tag = Dr90xProtocol.tagFrom(frame) ?: return@collect
                val now = SystemClock.elapsedRealtime()
                val previous = lastSeen.put(tag.epc, now)
                if (previous == null || now - previous > repeatWindowMs) {
                    send(ScannedTag(epc = tag.epc, rssi = 0, tid = tag.tid))
                }
            }
        }

        command(s) { it.startRead(withTid) }

        awaitClose {
            collector.cancel()
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

    private fun UsbDevice.idString() = "%04X:%04X %s".format(vendorId, productId, deviceName)

    private companion object {
        const val TAG = "Dr90xReader"
        const val BAUD_RATE = 115_200
        const val WRITE_TIMEOUT_MS = 500
        const val HANDSHAKE_TIMEOUT_MS = 2_000L

        /** Log every frame while the protocol is unverified. Turn off once it is. */
        const val LOG_FRAMES = true

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
