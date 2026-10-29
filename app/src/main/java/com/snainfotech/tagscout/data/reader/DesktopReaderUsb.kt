package com.snainfotech.tagscout.data.reader

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Watches USB for the Bluebird DR90X desktop reader (DR901, USB-C) and drives
 * ReaderAvailability.desktopConnected.
 *
 * The DR90X is a USB CDC virtual COM port (Artery MCU, 115200 8N1). IDs come from
 * Bluebird.Driver.dll: VID 0x2E3C, PID 0x5740 / 0x5750.
 *
 * desktopConnected = "a DR90X is plugged in". That holds for both input paths:
 *  - serial session (needs USB permission; Dr90xReader, later file), or
 *  - keyboard mode (no permission needed; KeyboardWedgeBuffer).
 * Whether the serial session is up is reported separately by the reader class.
 *
 * Call start() once from TagScoutApplication.onCreate().
 */
object DesktopReaderUsb {

    private const val TAG = "DesktopReaderUsb"

    const val VENDOR_ID = 0x2E3C
    val PRODUCT_IDS = setOf(0x5740, 0x5750)

    private const val ACTION_USB_PERMISSION = "com.snainfotech.tagscout.DR90X_USB_PERMISSION"

    enum class State {
        ABSENT,             // nothing plugged in
        NEEDS_PERMISSION,   // plugged in, permission dialog shown / pending
        PERMISSION_DENIED,  // plugged in, user said no (keyboard mode may still work)
        READY               // plugged in and the app may open it
    }

    data class Status(val state: State = State.ABSENT, val device: UsbDevice? = null) {
        val present: Boolean get() = state != State.ABSENT
    }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private var started = false
    private lateinit var appContext: Context

    /** Device we already asked about since it was plugged in, so a "no" is not re-asked in a loop. */
    private var askedFor: String? = null

    private val usb: UsbManager
        get() = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    fun isDr90x(device: UsbDevice): Boolean =
        device.vendorId == VENDOR_ID && device.productId in PRODUCT_IDS

    fun start(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        // System USB broadcasts still arrive with NOT_EXPORTED; the permission
        // broadcast is our own explicit intent.
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        refresh()
    }

    /** Re-scan attached devices. Safe to call any time (e.g. onResume, or after USB_DEVICE_ATTACHED reaches the activity). */
    fun refresh() {
        if (!started) return
        val device = usb.deviceList.values.firstOrNull(::isDr90x)
        when {
            device == null -> {
                askedFor = null
                publish(Status())
            }
            usb.hasPermission(device) -> publish(Status(State.READY, device))
            askedFor == device.deviceName -> {
                // Already asked since this plug-in; keep whatever the answer was.
                if (_status.value.device?.deviceName != device.deviceName) {
                    publish(Status(State.PERMISSION_DENIED, device))
                }
            }
            else -> requestPermission(device)
        }
    }

    /** For a "Grant access" button after the user declined once. */
    fun requestPermissionAgain() {
        if (!started) return
        val device = usb.deviceList.values.firstOrNull(::isDr90x) ?: return
        if (usb.hasPermission(device)) publish(Status(State.READY, device)) else requestPermission(device)
    }

    private fun requestPermission(device: UsbDevice) {
        askedFor = device.deviceName
        publish(Status(State.NEEDS_PERMISSION, device))
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName)
        // UsbManager adds extras to this intent, so it must be mutable (API 31+);
        // setPackage keeps it explicit, which API 34+ requires for mutable intents.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val pending = PendingIntent.getBroadcast(appContext, 0, intent, flags)
        runCatching { usb.requestPermission(device, pending) }
            .onFailure { Log.e(TAG, "requestPermission failed", it) }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.usbDevice() ?: return
            if (!isDr90x(device)) return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.i(TAG, "DR90X attached: ${device.deviceName}")
                    askedFor = null
                    refresh()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.i(TAG, "DR90X detached: ${device.deviceName}")
                    askedFor = null
                    publish(Status())
                }
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    Log.i(TAG, "DR90X permission granted=$granted")
                    publish(Status(if (granted) State.READY else State.PERMISSION_DENIED, device))
                }
            }
        }
    }

    private fun publish(status: Status) {
        _status.value = status
        ReaderAvailability.setDesktopConnected(status.present)
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
}