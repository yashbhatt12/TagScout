package com.snainfotech.tagscout.sdk

import com.snainfotech.tagscout.sdk.Dr90xProtocol.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * PC-side tests for the DR90X wire protocol (no device needed):
 *   .\gradlew :app:testDebugUnitTest --tests "*Dr90xProtocolTest*"
 *
 * Expected bytes come from the Windows SDK (Bluebird.Bcp.dll) as decompiled.
 * When the live reader disagrees, fix Dr90xProtocol and the expectation here
 * together, so the fix is pinned.
 */
class Dr90xProtocolTest {

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun hexOf(b: ByteArray) = b.toHex(" ")

    /** A reply frame from the reader, with a correct checksum unless [badSum]. */
    private fun reply(code: Int, payload: ByteArray, type: Int = 2, badSum: Boolean = false): ByteArray {
        val total = payload.size + Dr90xProtocol.FRAME_OVERHEAD
        val head = bytes(0xB3, 0xB0, 0x01, 0x01, total shr 8, total and 0xFF, type, code) + payload
        val sum = Dr90xProtocol.checksum(head) + if (badSum) 1 else 0
        return head + bytes((sum shr 8) and 0xFF, sum and 0xFF)
    }

    private fun splitAll(vararg chunks: ByteArray): Pair<List<Dr90xProtocol.Frame>, List<ByteArray>> {
        val frames = mutableListOf<Dr90xProtocol.Frame>()
        val noise = mutableListOf<ByteArray>()
        val splitter = Dr90xProtocol.FrameSplitter(onFrame = { frames += it }, onNoise = { noise += it })
        chunks.forEach { splitter.feed(it) }
        return frames to noise
    }

    private fun frameOf(code: Int, payload: ByteArray, type: Int = 2): Dr90xProtocol.Frame =
        splitAll(reply(code, payload, type)).first.single()

    private val epc = ByteArray(12) { (0xE2 + it).toByte() }
    private val tid = ByteArray(12) { (0x10 + it).toByte() }

    /** Tag payload as the SDK reads it: len, PC, EPC, RSSI (LE), 7 bytes, TID len, TID. */
    private fun tagPayload(rssiTenths: Int = -553, withTid: Boolean = true): ByteArray {
        val rssi = rssiTenths and 0xFFFF
        val base = bytes(epc.size, 0x30, 0x00) + epc + bytes(rssi and 0xFF, rssi shr 8) + ByteArray(7)
        return if (withTid) base + bytes(tid.size) + tid else base
    }

    // ── Checksum ─────────────────────────────────────────────────────────

    @Test
    fun checksum_isSixteenBitSum() {
        assertEquals(0x01DD, Dr90xProtocol.checksum("123456789".toByteArray()))
        assertEquals(0x0150, Dr90xProtocol.checksum(bytes(0xA3, 0xA0, 0x00, 0x01, 0x00, 0x0A, 0x00, 0x02)))
    }

    @Test
    fun checksum_onlyCoversFirst266Bytes() {
        val data = ByteArray(300) { 1 }
        assertEquals(266, Dr90xProtocol.checksum(data))
    }

    // ── Encoding ─────────────────────────────────────────────────────────

    @Test
    fun stopRead_matchesSdkFrame() {
        val e = Dr90xProtocol.Encoder()
        assertEquals("A3 A0 00 01 00 0A 00 02 01 50", hexOf(e.stopRead()))
    }

    @Test
    fun sequence_incrementsAndWraps() {
        val e = Dr90xProtocol.Encoder()
        repeat(255) { e.stopRead() }
        assertEquals(0xFF, e.stopRead()[2].toInt() and 0xFF)
        assertEquals(0x00, e.stopRead()[2].toInt() and 0xFF)
    }

    @Test
    fun startRead_withTid_usesA4AndThreeU32Limits() {
        val f = Dr90xProtocol.Encoder().startRead(withTid = true, maxTags = 1, maxTimeSec = 2, reCycle = 3)
        assertEquals(22, f.size)
        assertEquals(0xA4, f[7].toInt() and 0xFF)
        assertEquals("00 00 00 01 00 00 00 02 00 00 00 03", f.toHex(" ", from = 8, to = 20))
    }

    @Test
    fun startRead_epcOnly_usesA3() {
        val f = Dr90xProtocol.Encoder().startRead(withTid = false)
        assertEquals(0xA3, f[7].toInt() and 0xFF)
    }

    @Test
    fun setRegion_india() {
        val f = Dr90xProtocol.Encoder().setRegion(Dr90xProtocol.REGION_INDIA)
        assertEquals("A3 A0 00 01 00 0E 00 24 00 00 00 09 01 7F", hexOf(f))
    }

    @Test
    fun setRegion_rejectsOutOfRange() {
        try {
            Dr90xProtocol.Encoder().setRegion(Dr90xProtocol.REGION_MAX + 1)
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun setPower_sendsCentiDbm() {
        val f = Dr90xProtocol.Encoder().setPower(15)
        assertEquals(0x05, f[7].toInt() and 0xFF)
        assertEquals("00 00 05 DC", f.toHex(" ", from = 8, to = 12))  // 1500
    }

    @Test
    fun setPower_rejectsOutOfRange() {
        for (dbm in listOf(Dr90xProtocol.POWER_MIN_DBM - 1, Dr90xProtocol.POWER_MAX_DBM + 1)) {
            try {
                Dr90xProtocol.Encoder().setPower(dbm)
                fail("expected IllegalArgumentException for $dbm dBm")
            } catch (expected: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun gainMode_lowBelow20_highFrom20() {
        val e = Dr90xProtocol.Encoder()
        assertEquals(0x01, e.setGainModeFor(19)[8].toInt())
        assertEquals(0x00, e.setGainModeFor(20)[8].toInt())
        assertEquals(0x76, e.setGainModeFor(20)[7].toInt() and 0xFF)
    }

    // ── Frame splitting ──────────────────────────────────────────────────

    @Test
    fun splitter_reassemblesAFrameSplitAcrossReads() {
        val whole = reply(0x23, bytes(0, 0, 0, 0, 9))
        val (frames, noise) = splitAll(whole.copyOfRange(0, 3), whole.copyOfRange(3, 9), whole.copyOfRange(9, whole.size))
        assertEquals(1, frames.size)
        assertTrue(frames[0].checksumOk)
        assertEquals(0x23, frames[0].code)
        assertTrue(noise.isEmpty())
    }

    @Test
    fun splitter_dropsNoiseAndHandlesBackToBackFrames() {
        val stream = bytes(0x11, 0x22) + reply(0x02, bytes(0x00), type = 1) + reply(0x6A, bytes(0x00))
        val (frames, noise) = splitAll(stream)
        assertEquals(listOf(0x02, 0x6A), frames.map { it.code })
        assertTrue(frames[0].isAck)
        assertEquals("11 22", hexOf(noise.single()))
    }

    @Test
    fun splitter_skipsAFalseStartWithImpossibleLength() {
        val stream = bytes(0xB3, 0x00, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00) + reply(0x04, bytes(0))
        val (frames, _) = splitAll(stream)
        assertEquals(listOf(0x04), frames.map { it.code })
    }

    @Test
    fun splitter_flagsBadChecksum() {
        val (frames, _) = splitAll(reply(0x23, bytes(0, 0, 0, 0, 9), badSum = true))
        assertFalse(frames.single().checksumOk)
    }

    @Test
    fun frameData_isEverythingBetweenHeaderAndChecksum() {
        val f = frameOf(0x23, bytes(0, 0, 0, 0, 9))
        assertEquals("00 00 00 00 09", hexOf(f.data))
    }

    // ── Tag decoding ─────────────────────────────────────────────────────

    @Test
    fun tag_withTidAndRssi() {
        val tag = Dr90xProtocol.tagFrom(frameOf(Dr90xProtocol.RSP_TAG, tagPayload()))
        assertNotNull(tag)
        tag!!
        assertEquals("3000", tag.pc)
        assertEquals(epc.toHex(), tag.epc)
        assertEquals(tid.toHex(), tag.tid)
        assertEquals(-55, tag.rssi)
    }

    @Test
    fun tag_withoutTid() {
        val tag = Dr90xProtocol.decodeTag(tagPayload(withTid = false))!!
        assertEquals(epc.toHex(), tag.epc)
        assertEquals("", tag.tid)
        assertEquals(-55, tag.rssi)
    }

    @Test
    fun tag_epcOnlyPayload_hasNoRssi() {
        val tag = Dr90xProtocol.decodeTag(bytes(epc.size, 0x30, 0x00) + epc)!!
        assertEquals(epc.toHex(), tag.epc)
        assertNull(tag.rssi)
        assertEquals("", tag.tid)
    }

    @Test
    fun tag_strongerSignalHasHigherRssi() {
        val near = Dr90xProtocol.decodeTag(tagPayload(rssiTenths = -420))!!
        val far = Dr90xProtocol.decodeTag(tagPayload(rssiTenths = -700))!!
        assertTrue(near.rssi!! > far.rssi!!)
    }

    @Test
    fun tag_rejectsEmptyOrTruncatedPayload() {
        assertNull(Dr90xProtocol.decodeTag(bytes()))
        assertNull(Dr90xProtocol.decodeTag(bytes(0, 0x30, 0x00)))                 // EPC length 0
        assertNull(Dr90xProtocol.decodeTag(bytes(12, 0x30, 0x00) + ByteArray(5))) // EPC cut short
    }

    @Test
    fun tag_truncatedTidIsDropped() {
        val payload = tagPayload().copyOfRange(0, tagPayload().size - 4)
        val tag = Dr90xProtocol.decodeTag(payload)!!
        assertEquals(epc.toHex(), tag.epc)
        assertEquals("", tag.tid)
    }

    @Test
    fun tagFrom_ignoresOtherCodes() {
        assertNull(Dr90xProtocol.tagFrom(frameOf(Dr90xProtocol.CMD_START_READ_WITH_TID, tagPayload())))
    }

    // ── Other replies ────────────────────────────────────────────────────

    @Test
    fun region_fromGetRegionReply() {
        assertEquals(9, Dr90xProtocol.regionFrom(frameOf(Dr90xProtocol.CMD_GET_REGION, bytes(0, 0, 0, 0, 9))))
        assertNull(Dr90xProtocol.regionFrom(frameOf(Dr90xProtocol.CMD_GET_POWER, bytes(0, 0, 0, 0, 9))))
        assertNull(Dr90xProtocol.regionFrom(frameOf(Dr90xProtocol.CMD_GET_REGION, bytes(0, 0))))
    }

    @Test
    fun power_fromGetPowerReply() {
        // current 1500, min 500, max 2700 (dBm x 100), after a result byte
        val payload = bytes(0, 0, 0, 0x05, 0xDC, 0, 0, 0x01, 0xF4, 0, 0, 0x0A, 0x8C)
        assertEquals(15, Dr90xProtocol.powerDbmFrom(frameOf(Dr90xProtocol.CMD_GET_POWER, payload)))
    }

    @Test
    fun failure_fromFfAndFromFailedStart() {
        assertEquals(0x02, Dr90xProtocol.failureCode(frameOf(Dr90xProtocol.RSP_FAILURE, bytes(0x00, 0x02))))
        assertEquals(0x03, Dr90xProtocol.failureCode(frameOf(Dr90xProtocol.CMD_START_READ_WITH_TID, bytes(0x02, 0x03))))
        assertNull(Dr90xProtocol.failureCode(frameOf(Dr90xProtocol.CMD_START_READ_WITH_TID, bytes(0x00))))
        assertNull(Dr90xProtocol.failureCode(frameOf(Dr90xProtocol.CMD_GET_REGION, bytes(0x02, 0, 0, 0, 9))))
    }

    @Test
    fun mcuVersion_readsThreeAsciiFields() {
        fun field(s: String) = s.toByteArray().copyOf(32)
        val payload = bytes(0x00) + field("DR901ABC123") + field("HW1.0") + field("FW2.3")
        assertEquals(
            "SN DR901ABC123 HW HW1.0 FW FW2.3",
            Dr90xProtocol.describeMcuVersion(frameOf(Dr90xProtocol.CMD_GET_MCU_VERSION, payload))
        )
    }

    @Test
    fun mcuVersion_fallsBackToHex() {
        val f = frameOf(Dr90xProtocol.CMD_GET_MCU_VERSION, bytes(0x00, 0x01, 0x02))
        assertEquals("00 01 02", Dr90xProtocol.describeMcuVersion(f))
    }
}