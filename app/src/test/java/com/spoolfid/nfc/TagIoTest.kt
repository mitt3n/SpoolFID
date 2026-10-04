package com.spoolfid.nfc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TagIoTest {
    private val uid = byteArrayOf(0x11, 0x22, 0x33, 0x44)
    private val derivedKey get() = CfsCrypto.deriveSectorKey(uid)

    private val spool42 = CfsPayload("00001", "FF0000", WeightBucket.G1000, 42)
    private val spool7 = CfsPayload("00003", "00FF00", WeightBucket.G500, 7)

    private fun written(tag: FakeMifareTag): DecodedPayload {
        val contents = TagIo.read(tag, uid).contents
        assertTrue("expected a written tag but was $contents", contents is TagContents.Written)
        return (contents as TagContents.Written).payload!!
    }

    // ---- reading ----

    @Test
    fun blankTagReadsAsBlank() {
        val result = TagIo.read(FakeMifareTag(), uid)
        assertEquals(TagContents.Blank, result.contents)
        assertEquals("11223344", result.uid)
    }

    @Test
    fun unknownKeyIsAnErrorNotBlank() {
        val tag = FakeMifareTag().apply { setKeyA(1, byteArrayOf(1, 2, 3, 4, 5, 6)) }
        val e = assertThrows(TagException::class.java) { TagIo.read(tag, uid) }
        assertTrue(e.message!!.contains("unknown key"))
    }

    @Test
    fun sessionIsAlwaysClosed() {
        val tag = FakeMifareTag()
        TagIo.read(tag, uid)
        assertFalse(tag.connected)
        TagIo.write(tag, uid, spool42)
        assertFalse(tag.connected)
        tag.setKeyA(1, byteArrayOf(9, 9, 9, 9, 9, 9))
        assertThrows(TagException::class.java) { TagIo.read(tag, uid) }
        assertFalse(tag.connected)
    }

    // ---- writing ----

    @Test
    fun writingABlankTagSecuresItAndRoundTrips() {
        val tag = FakeMifareTag()
        val result = TagIo.write(tag, uid, spool42)
        assertNull(result.previousSpoolId)
        assertEquals("11223344", result.uid)

        // The derived key is now Key A and Key B of sector 1; access bits are untouched.
        val trailer = tag.raw(7)
        assertArrayEquals(derivedKey, trailer.copyOfRange(0, 6))
        assertArrayEquals(derivedKey, trailer.copyOfRange(10, 16))
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0x07, 0x80.toByte(), 0x69), trailer.copyOfRange(6, 10))

        val payload = written(tag)
        assertEquals(42, payload.spoolId)
        assertEquals("00001", payload.materialId)
        assertEquals("FF0000", payload.colorHex)
        assertEquals(WeightBucket.G1000, payload.weight)
        assertEquals("000042", payload.serial)
    }

    @Test
    fun theStoredBytesAreTheEncryptedPayload() {
        val tag = FakeMifareTag()
        TagIo.write(tag, uid, spool42)
        val stored = ByteArray(48)
        for (i in 0 until 3) tag.raw(4 + i).copyInto(stored, i * 16)
        val plain = CfsCrypto.decryptPayload(stored)
        assertEquals(spool42.encode(), String(plain, Charsets.ISO_8859_1))
    }

    @Test
    fun sectorTwoCarriesTheK2MarkerAndSpaces() {
        val tag = FakeMifareTag()
        TagIo.write(tag, uid, spool42)
        assertEquals("k2" + " ".repeat(14), String(tag.raw(8), Charsets.ISO_8859_1))
        assertEquals(" ".repeat(16), String(tag.raw(9), Charsets.ISO_8859_1))
        assertEquals(" ".repeat(16), String(tag.raw(10), Charsets.ISO_8859_1))
        // Sector 2's trailer keys stay at the default.
        assertArrayEquals(FakeMifareTag.DEFAULT_KEY, tag.raw(11).copyOfRange(0, 6))
    }

    @Test
    fun onlyTheCfsBlocksAreTouched() {
        val fresh = FakeMifareTag().snapshot()
        val tag = FakeMifareTag()
        TagIo.write(tag, uid, spool42)
        val after = tag.snapshot()
        val touched = (0 until 64).filter { fresh[it] != after[it] }.toSet()
        assertEquals(setOf(4, 5, 6, 7, 8, 9, 10), touched)
    }

    @Test
    fun rewritingASecuredTagReportsThePreviousSpool() {
        val tag = FakeMifareTag()
        TagIo.write(tag, uid, spool42)
        val second = TagIo.write(tag, uid, spool7)
        assertEquals(42, second.previousSpoolId)
        assertEquals(7, written(tag).spoolId)
    }

    @Test
    fun aSecuredTagKeepsItsKeysWhenRewritten() {
        val tag = FakeMifareTag()
        TagIo.write(tag, uid, spool42)
        val trailerBefore = tag.raw(7)
        TagIo.write(tag, uid, spool7)
        assertArrayEquals(trailerBefore, tag.raw(7))
    }

    // ---- overwrite protection ----

    @Test
    fun overwriteProtectionStopsADifferentSpoolBeforeChangingAnything() {
        val tag = FakeMifareTag()
        TagIo.write(tag, uid, spool42)
        val before = tag.snapshot()

        val e = assertThrows(ExistingDataException::class.java) {
            TagIo.write(tag, uid, spool7, allowOverwrite = false)
        }
        assertEquals(42, e.existing?.spoolId)
        assertEquals(before, tag.snapshot())
        assertFalse(tag.connected)
    }

    @Test
    fun overwriteProtectionAllowsRewritingTheSameSpool() {
        val tag = FakeMifareTag()
        TagIo.write(tag, uid, spool42)
        val result = TagIo.write(tag, uid, spool42, allowOverwrite = false)
        assertEquals(42, result.previousSpoolId)
    }

    @Test
    fun overwriteIsAllowedWhenApproved() {
        val tag = FakeMifareTag()
        TagIo.write(tag, uid, spool42)
        TagIo.write(tag, uid, spool7, allowOverwrite = true)
        assertEquals(7, written(tag).spoolId)
    }

    @Test
    fun aBlankTagNeverTriggersTheOverwritePrompt() {
        val result = TagIo.write(FakeMifareTag(), uid, spool42, allowOverwrite = false)
        assertNull(result.previousSpoolId)
    }

    @Test
    fun aTagWithUndecodableDataStillPromptsBeforeOverwriting() {
        val tag = FakeMifareTag()
        TagIo.write(tag, uid, spool42)
        // Corrupt the stored payload so it no longer decodes to a record.
        val garbage = CfsCrypto.encryptPayload(ByteArray(48) { 0 })
        for (i in 0 until 3) {
            // Write through the same API a real session would use.
            tag.authenticate(1, derivedKey)
            tag.writeBlock(4 + i, garbage.copyOfRange(i * 16, i * 16 + 16))
        }
        val e = assertThrows(ExistingDataException::class.java) {
            TagIo.write(tag, uid, spool7, allowOverwrite = false)
        }
        assertNotNull(e)
        assertNull(e.existing?.spoolId)
    }

    // ---- unreliable tags and errors ----

    @Test
    fun flakyAuthenticationIsRetried() {
        val tag = FakeMifareTag().apply { failAuthAttempts = 2 }
        TagIo.write(tag, uid, spool42)
        assertEquals(42, written(tag).spoolId)
        assertTrue("should have reconnected between attempts", tag.connectCount > 1)
    }

    @Test
    fun persistentAuthenticationFailureGivesUp() {
        val tag = FakeMifareTag().apply { failAuthAttempts = 1000 }
        val e = assertThrows(TagException::class.java) { TagIo.write(tag, uid, spool42) }
        assertTrue(e.message!!.contains("unknown key"))
    }

    @Test
    fun aSevenByteUidIsRejectedWithoutTouchingTheTag() {
        val tag = FakeMifareTag()
        val e = assertThrows(TagException::class.java) { TagIo.write(tag, ByteArray(7), spool42) }
        assertTrue(e.message!!.contains("4-byte"))
        assertEquals(0, tag.connectCount)
    }

    @Test
    fun aCommunicationErrorBecomesAFriendlyTagException() {
        val tag = FakeMifareTag().apply { ioErrorOnWrite = true }
        val e = assertThrows(TagException::class.java) { TagIo.write(tag, uid, spool42) }
        assertTrue(e.message!!.contains("communication failed"))
        assertFalse(tag.connected)
    }

    @Test
    fun aWriteThatDidNotStickIsCaughtByVerification() {
        val tag = FakeMifareTag().apply { droppedWriteBlocks = setOf(5) }
        val e = assertThrows(TagException::class.java) { TagIo.write(tag, uid, spool42) }
        assertTrue(e.message!!.contains("Verification failed"))
    }
}
