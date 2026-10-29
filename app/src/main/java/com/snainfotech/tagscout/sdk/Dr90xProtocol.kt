package com.snainfotech.tagscout.sdk

/**
 * Wire protocol of the Bluebird DR90X desktop reader (DR901) over its USB CDC
 * serial port (115200 8N1).
 *
 * Bluebird only ships a Windows .NET SDK for this reader. Everything here was
 * recovered from that SDK (Bluebird.Bcp.dll v1.5.7.2) and has NOT been checked
 * against a live reader yet. Lines marked VERIFY are the guesses; the raw-frame
 * log (Frame.hex) is how we check them on the tablet or against BLUE_DEMO's log.
 *
 * Pure Kotlin, no Android imports, so it can be unit-tested on the PC.
 *
 * Command frame (host -> reader):
 *   A3 A0 | seq | flag | LEN(u16 BE) | type | code | payload | SUM(u16 BE)
 *   LEN  = total frame length (payload + 10)
 *   flag = 0x03 when type == 1, else 0x01
 *   SUM  = 16-bit sum of all bytes before it
 *
 * Reply frame (reader -> host):
 *   B3 .. | LEN(u16 BE) at [4..5] | type at [6] | code at [7] | body | SUM(u16 BE)
 *   type 1 = acknowledgement; 2..4 = data / notifications.
 */
object Dr90xProtocol {

    // ── Command codes (host -> reader) ───────────────────────────────────
    const val CMD_STOP_READ = 0x02
    const val CMD_GET_MCU_VERSION = 0x6A
    const val CMD_START_READ = 0xA3            // EPC only
    const val CMD_START_READ_WITH_TID = 0xA4   // EPC + TID

    // ── Reply codes (reader -> host), from the SDK's reply dispatcher ────
    const val RSP_TAG = 0x22                   // PC + EPC
    const val RSP_TAG_WITH_TID = 0x25          // PC + EPC + TID
    const val RSP_MCU_VERSION = 0x6A
    const val RSP_FAILURE = 0xFF

    // ── Frame types ──────────────────────────────────────────────────────
    const val TYPE_COMMAND = 0x00
    const val TYPE_ACK = 0x01

    private const val CMD_PREAMBLE_0 = 0xA3
    private const val CMD_PREAMBLE_1 = 0xA0
    private const val RSP_PREAMBLE = 0xB3

    /** Header (8) + checksum (2). */
    const val FRAME_OVERHEAD = 10

    /** Upper bound for one reply frame; anything bigger is treated as line noise. */
    const val MAX_FRAME = 1024

    /** Most bytes the splitter will hold while waiting for a frame to complete. */
    private const val MAX_BUFFER = MAX_FRAME * 8

    /** The SDK only sums the first 266 bytes of a frame. */
    private const val CHECKSUM_SPAN = 266

    private val EMPTY = ByteArray(0)

    // ── Checksum ─────────────────────────────────────────────────────────

    /** 16-bit sum of the first [count] bytes (capped at 266, like the SDK). */
    fun checksum(bytes: ByteArray, count: Int = bytes.size): Int {
        val n = minOf(count, bytes.size, CHECKSUM_SPAN)
        var sum = 0
        for (i in 0 until n) sum += bytes[i].u
        return sum and 0xFFFF
    }

    // ── Encoding ─────────────────────────────────────────────────────────

    /**
     * Builds command frames. Keeps the rolling sequence number, so use one
     * Encoder per reader session.
     */
    class Encoder {
        private var seq = 0

        fun frame(code: Int, payload: ByteArray = EMPTY, type: Int = TYPE_COMMAND): ByteArray {
            val total = payload.size + FRAME_OVERHEAD
            val out = ByteArray(total)
            out[0] = CMD_PREAMBLE_0.toByte()
            out[1] = CMD_PREAMBLE_1.toByte()
            out[2] = seq.toByte()
            out[3] = (if (type == TYPE_ACK) 0x03 else 0x01).toByte()
            out[4] = (total shr 8).toByte()
            out[5] = total.toByte()
            out[6] = type.toByte()
            out[7] = code.toByte()
            payload.copyInto(out, destinationOffset = 8)
            val sum = checksum(out, total - 2)
            out[total - 2] = (sum shr 8).toByte()
            out[total - 1] = sum.toByte()
            seq = (seq + 1) and 0xFF
            return out
        }

        /**
         * Start an inventory. 0 means unlimited for each limit; with all three
         * at 0 the reader keeps reading until [stopRead].
         *
         * @param maxTimeSec stop after this many seconds
         * @param reCycle    stop after this many inventory rounds
         */
        fun startRead(withTid: Boolean, maxTags: Long = 0, maxTimeSec: Long = 0, reCycle: Long = 0): ByteArray {
            val payload = u32(maxTags) + u32(maxTimeSec) + u32(reCycle)
            return frame(if (withTid) CMD_START_READ_WITH_TID else CMD_START_READ, payload)
        }

        fun stopRead(): ByteArray = frame(CMD_STOP_READ)

        /** Doubles as the connect handshake: a reply proves the reader is alive. */
        fun getMcuVersion(): ByteArray = frame(CMD_GET_MCU_VERSION)

        private fun u32(v: Long) = byteArrayOf(
            (v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte()
        )
    }

    // ── Decoding ─────────────────────────────────────────────────────────

    /** One complete reply frame from the reader. */
    class Frame(val raw: ByteArray, val checksumOk: Boolean) {
        val seq: Int get() = raw[2].u
        val type: Int get() = raw[6].u
        val code: Int get() = raw[7].u
        val isAck: Boolean get() = type == TYPE_ACK

        /** Everything between the 8-byte header and the checksum. */
        val body: ByteArray get() = raw.copyOfRange(8, raw.size - 2)

        /**
         * The body without its inner u16 length prefix, if it carries one.
         * VERIFY: the SDK's dispatcher reads a length right before the data,
         * so the prefix is stripped only when it matches the body exactly.
         */
        val data: ByteArray
            get() {
                val b = body
                return if (b.size >= 2 && ((b[0].u shl 8) or b[1].u) == b.size - 2) {
                    b.copyOfRange(2, b.size)
                } else {
                    b
                }
            }

        fun hex(): String = raw.toHex(" ")

        override fun toString(): String =
            "Frame(type=0x%02X code=0x%02X seq=%d sumOk=%s raw=%s)".format(type, code, seq, checksumOk, hex())
    }

    /**
     * Turns the serial byte stream into frames. USB reads can split a frame or
     * carry several, so bytes are buffered until a full frame is present.
     * Bytes that are not part of a frame (noise, a half frame from before the
     * port opened) are passed to [onNoise] and dropped.
     *
     * Not thread-safe: feed it from the one serial read thread.
     */
    class FrameSplitter(
        private val onFrame: (Frame) -> Unit,
        private val onNoise: (ByteArray) -> Unit = {}
    ) {
        private var buf = ByteArray(2048)
        private var size = 0

        fun reset() {
            size = 0
        }

        fun feed(data: ByteArray, length: Int = data.size) {
            if (length <= 0) return
            if (size + length > MAX_BUFFER) {
                // Runaway buffer (no frame end in sight): start clean.
                onNoise(buf.copyOfRange(0, size))
                size = 0
            }
            if (size + length > buf.size) buf = buf.copyOf(maxOf(size + length, buf.size * 2))
            data.copyInto(buf, destinationOffset = size, startIndex = 0, endIndex = length)
            size += length
            drain()
        }

        private fun drain() {
            while (size > 0) {
                // Resync on the reply preamble.
                val start = indexOf(RSP_PREAMBLE)
                if (start < 0) {
                    onNoise(buf.copyOfRange(0, size))
                    size = 0
                    return
                }
                if (start > 0) {
                    onNoise(buf.copyOfRange(0, start))
                    consume(start)
                }

                if (size < 8) return  // need the header to know the length
                val total = (buf[4].u shl 8) or buf[5].u
                if (total < FRAME_OVERHEAD || total > MAX_FRAME) {
                    // Not a real frame start; skip this byte and look again.
                    onNoise(buf.copyOfRange(0, 1))
                    consume(1)
                    continue
                }
                if (size < total) return  // wait for the rest

                val raw = buf.copyOfRange(0, total)
                consume(total)
                val expected = (raw[total - 2].u shl 8) or raw[total - 1].u
                onFrame(Frame(raw, checksumOk = checksum(raw, total - 2) == expected))
            }
        }

        private fun indexOf(value: Int): Int {
            for (i in 0 until size) if (buf[i].u == value) return i
            return -1
        }

        private fun consume(n: Int) {
            buf.copyInto(buf, destinationOffset = 0, startIndex = n, endIndex = size)
            size -= n
        }
    }

    // ── Payload decoders ─────────────────────────────────────────────────

    /** One tag read. Hex strings, upper case; [tid] is empty for EPC-only reads. */
    data class TagRead(val pc: String, val epc: String, val tid: String)

    /**
     * Splits a tag payload into PC, EPC and (optionally) TID.
     * The EPC length comes from the PC word: top 5 bits of PC[0] = EPC length
     * in 16-bit words. Same rule as the SDK: ((PC[0] >> 2) & 0xFE) + 2 bytes
     * of PC + EPC. Anything after that is the TID (for 0x25 replies).
     * VERIFY: whether EPC-only (0x22) replies carry trailing bytes (e.g. RSSI).
     */
    fun decodeTag(data: ByteArray, withTid: Boolean): TagRead? {
        if (data.size < 2) return null
        val pcEpcLen = ((data[0].u shr 2) and 0xFE) + 2
        if (pcEpcLen <= 2 || pcEpcLen > data.size) return null
        return TagRead(
            pc = data.toHex(from = 0, to = 2),
            epc = data.toHex(from = 2, to = pcEpcLen),
            tid = if (withTid) data.toHex(from = pcEpcLen, to = data.size) else ""
        )
    }

    /** Tag carried by a frame, or null if the frame is not a tag reply. */
    fun tagFrom(frame: Frame): TagRead? = when (frame.code) {
        RSP_TAG_WITH_TID -> decodeTag(frame.data, withTid = true)
        RSP_TAG -> decodeTag(frame.data, withTid = false)
        else -> null
    }

    /**
     * Error code from a failure reply (0xFF). The SDK takes byte 2 of the data
     * when there are at least 3 bytes, else the first byte.
     * Command errors: 01 unknown, 02 busy, 03 not ready, 04 checksum, 06 bad parameter.
     * Tag-access errors: 80 no tag reply, 82 wrong password, 04 memory locked, 0B low power.
     */
    fun failureCode(frame: Frame): Int? {
        if (frame.code != RSP_FAILURE) return null
        val d = frame.data
        return when {
            d.size >= 3 -> d[2].u
            d.isNotEmpty() -> d[0].u
            else -> null
        }
    }

    /**
     * Readable form of the MCU version reply (serial number, hardware and
     * firmware versions). VERIFY: field layout unknown; printable ASCII is
     * kept and other bytes are shown as hex until the log shows the layout.
     */
    fun describeMcuVersion(frame: Frame): String {
        val d = frame.data
        val ascii = d.filter { it.u in 0x20..0x7E }
        return if (ascii.size >= d.size / 2) {
            ascii.map { it.toInt().toChar() }.joinToString("").trim()
        } else {
            d.toHex(" ")
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private val Byte.u: Int get() = toInt() and 0xFF

    fun ByteArray.toHex(separator: String = "", from: Int = 0, to: Int = size): String {
        val sb = StringBuilder()
        for (i in from until to) {
            if (i > from && separator.isNotEmpty()) sb.append(separator)
            sb.append("%02X".format(this[i].toInt() and 0xFF))
        }
        return sb.toString()
    }
}