package com.spoolfid

import com.spoolfid.nfc.CfsCrypto
import com.spoolfid.nfc.CfsPayload
import com.spoolfid.nfc.WeightBucket
import com.spoolfid.nfc.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CfsTagTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun sectorKeyDerivation() {
        val key = CfsCrypto.deriveSectorKey(hex("11223344"))
        assertEquals("1678509054" + "8E", key.toHex())
    }

    @Test
    fun payloadEncryptionMatchesReferenceVector() {
        val plain = "AB1240276A21010010FF0000033000004200004200000000".toByteArray(Charsets.ISO_8859_1)
        val expected = "57F25B78076D4C1797B1BE35CA269540" +
            "11A04C3E0D0BDF51734B011C49C7A9AB" +
            "8BB6FB2EF67726584C4EF227E4EB897C"
        val cipher = CfsCrypto.encryptPayload(plain)
        assertEquals(expected, cipher.toHex())
        assertEquals(plain.toList(), CfsCrypto.decryptPayload(cipher).toList())
    }

    @Test
    fun encodeMatchesSpecExample() {
        val p = CfsPayload("01001", "FF0000", WeightBucket.G1000, 42)
        assertEquals("AB1240276A21010010FF0000033000004200004200000000", p.encode())
    }

    @Test
    fun decodeRoundTrip() {
        val p = CfsPayload("00003", "12AB34", WeightBucket.G500, 123456)
        val d = CfsPayload.decode(p.encode())
        assertNotNull(d)
        assertEquals(123456, d!!.spoolId)
        assertEquals("12AB34", d.colorHex)
        assertEquals("00003", d.materialId)
        assertEquals(WeightBucket.G500, d.weight)
    }

    @Test
    fun weightBuckets() {
        assertEquals(WeightBucket.G1000, WeightBucket.nearest(1000.0))
        assertEquals(WeightBucket.G1000, WeightBucket.nearest(900.0))
        assertEquals(WeightBucket.G500, WeightBucket.nearest(480.0))
        assertEquals(WeightBucket.G600, WeightBucket.nearest(550.0)) // tie -> heavier
    }

    @Test
    fun weightBucketCodesRoundTrip() {
        for (b in WeightBucket.entries) assertEquals(b, WeightBucket.fromCode(b.code))
        assertNull(WeightBucket.fromCode("9999"))
        assertEquals(WeightBucket.G250, WeightBucket.nearest(1.0))
        assertEquals(WeightBucket.G1000, WeightBucket.nearest(5000.0))
    }

    @Test
    fun spoolIdIsWrittenToBothSerialAndReserve() {
        val text = CfsPayload("00001", "FF0000", WeightBucket.G1000, 123).encode()
        assertEquals("000123", text.substring(28, 34)) // serial
        assertEquals("000123", text.substring(34, 40)) // reserve: what the printer firmware reads
    }

    @Test
    fun encodedPayloadIsAlwaysFortyEightCharacters() {
        for (id in listOf(0, 1, 2, 42, 999_999)) {
            assertEquals(48, CfsPayload("00004", "0A0B0C", WeightBucket.G750, id).encode().length)
        }
    }

    @Test
    fun colorIsUppercasedAndPrefixedWithZero() {
        val text = CfsPayload("00001", "ff8800", WeightBucket.G1000, 5).encode()
        assertEquals("0FF8800", text.substring(17, 24))
    }

    @Test
    fun decodeRejectsTooShortText() {
        assertNull(CfsPayload.decode(""))
        assertNull(CfsPayload.decode("AB1240276A21010010FF0000033000004200004"))
    }

    @Test
    fun anUnreadableReserveGivesNoSpoolId() {
        val text = CfsPayload("00001", "FF0000", WeightBucket.G1000, 5).encode()
        val broken = text.substring(0, 34) + "ABCDEF" + text.substring(40)
        val d = CfsPayload.decode(broken)
        assertNotNull(d)
        assertNull(d!!.spoolId)
    }

    @Test
    fun spoolIdsBelowTwoAreStillDecodedFaithfully() {
        // The printer firmware ignores 0 and 1, but the tag itself must read back exactly.
        assertEquals(1, CfsPayload.decode(CfsPayload("00001", "FF0000", WeightBucket.G1000, 1).encode())!!.spoolId)
        assertEquals(0, CfsPayload.decode(CfsPayload("00001", "FF0000", WeightBucket.G1000, 0).encode())!!.spoolId)
    }

    @Test
    fun sectorKeyDiffersPerUid() {
        val a = CfsCrypto.deriveSectorKey(hex("11223344")).toHex()
        val b = CfsCrypto.deriveSectorKey(hex("11223345")).toHex()
        assertNotEquals(a, b)
    }
}
