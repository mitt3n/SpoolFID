package com.spoolfid.nfc

import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.MifareClassic
import java.io.IOException

class TagException(message: String) : Exception(message)

/** The tag isn't blank and holds something other than the spool being written; needs user approval. */
class ExistingDataException(val existing: DecodedPayload?) : Exception("Tag already has data")

sealed interface TagContents {
    data object Blank : TagContents
    data class Written(val payload: DecodedPayload?) : TagContents
}

data class ReadResult(val uid: String, val contents: TagContents)
data class WriteResult(val uid: String, val previousSpoolId: Int?)

fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

/** The few MIFARE Classic operations [TagIo] needs, so the logic can be tested without a phone. */
interface MifareSession {
    fun connect()
    fun close()

    /** Authenticates [sector] with Key A. */
    fun authenticate(sector: Int, key: ByteArray): Boolean

    fun readBlock(block: Int): ByteArray
    fun writeBlock(block: Int, data: ByteArray)
}

private class AndroidMifareSession(private val mc: MifareClassic) : MifareSession {
    override fun connect() = mc.connect()
    override fun close() = mc.close()
    override fun authenticate(sector: Int, key: ByteArray) = mc.authenticateSectorWithKeyA(sector, key)
    override fun readBlock(block: Int): ByteArray = mc.readBlock(block)
    override fun writeBlock(block: Int, data: ByteArray) = mc.writeBlock(block, data)
}

object TagIo {
    private val DEFAULT_KEY = ByteArray(6) { 0xFF.toByte() }
    private const val SECTOR_PRIMARY = 1
    private const val SECTOR_SECONDARY = 2

    fun read(tag: Tag): ReadResult = read(sessionFor(tag), tag.id)

    /**
     * Writes [payload]. With [allowOverwrite] false, a tag that already holds anything other than this same
     * spool throws [ExistingDataException] before a single byte is changed.
     */
    fun write(tag: Tag, payload: CfsPayload, allowOverwrite: Boolean = true): WriteResult =
        write(sessionFor(tag), tag.id, payload, allowOverwrite)

    private fun sessionFor(tag: Tag): MifareSession {
        val mc = MifareClassic.get(tag) ?: throw TagException("Not a MIFARE Classic tag")
        return AndroidMifareSession(mc)
    }

    internal fun read(session: MifareSession, uid: ByteArray): ReadResult =
        withSession(session, uid) { derived ->
            val contents = when (probe(session, derived)) {
                Sector1.BLANK -> TagContents.Blank
                Sector1.SECURED -> TagContents.Written(readPayload(session))
            }
            ReadResult(uid.toHex(), contents)
        }

    internal fun write(
        session: MifareSession,
        uid: ByteArray,
        payload: CfsPayload,
        allowOverwrite: Boolean = true,
    ): WriteResult = withSession(session, uid) { derived ->
        val plain = payload.encode().toByteArray(Charsets.ISO_8859_1)
        val cipher = CfsCrypto.encryptPayload(plain)

        val state = probe(session, derived)
        val existing = if (state == Sector1.SECURED) readPayload(session) else null
        if (state == Sector1.SECURED && !allowOverwrite && existing?.spoolId != payload.spoolId) {
            throw ExistingDataException(existing)
        }
        val previous = existing?.spoolId

        for (i in 0 until 3) session.writeBlock(4 + i, cipher.copyOfRange(i * 16, i * 16 + 16))

        if (state == Sector1.BLANK) {
            // Install the derived key as Key A and Key B, keeping the existing access bits + GPB.
            val trailer = session.readBlock(7)
            session.writeBlock(7, derived + trailer.copyOfRange(6, 10) + derived)
            authenticate(session, SECTOR_PRIMARY, derived)
        }

        // Sector 2 is plaintext filler under the default key; its trailer is never touched.
        // Same as the community K2-RFID writer: printer model first, then space padding.
        authenticate(session, SECTOR_SECONDARY, DEFAULT_KEY)
        val spaces = ByteArray(16) { ' '.code.toByte() }
        val first = spaces.copyOf().also { "k2".toByteArray(Charsets.ISO_8859_1).copyInto(it) }
        session.writeBlock(8, first)
        session.writeBlock(9, spaces)
        session.writeBlock(10, spaces)

        // Read everything back and compare before reporting success.
        authenticate(session, SECTOR_PRIMARY, derived)
        val back = ByteArray(48)
        for (i in 0 until 3) session.readBlock(4 + i).copyInto(back, i * 16)
        if (!CfsCrypto.decryptPayload(back).contentEquals(plain)) {
            throw TagException("Verification failed - tag content didn't match. Try again.")
        }
        WriteResult(uid.toHex(), previous)
    }

    private enum class Sector1 { SECURED, BLANK }

    private fun <T> withSession(session: MifareSession, uid: ByteArray, block: (ByteArray) -> T): T {
        if (uid.size != 4) throw TagException("Unsupported tag (needs a 4-byte UID)")
        try {
            session.connect()
            return block(CfsCrypto.deriveSectorKey(uid))
        } catch (e: TagException) {
            throw e
        } catch (e: IOException) {
            throw TagException(
                if (e is TagLostException) {
                    "Lost contact with the tag - hold it steady and retry"
                } else {
                    "Tag communication failed - hold it steady and retry"
                },
            )
        } finally {
            try { session.close() } catch (_: IOException) {}
        }
    }

    /**
     * Auth is flaky on real tags, and a failed attempt can leave the tag unresponsive until reconnect,
     * so retry the whole probe, reconnecting between attempts.
     */
    private fun probe(session: MifareSession, derived: ByteArray): Sector1 {
        repeat(3) {
            if (session.authenticate(SECTOR_PRIMARY, derived)) return Sector1.SECURED
            reconnect(session)
            if (session.authenticate(SECTOR_PRIMARY, DEFAULT_KEY)) return Sector1.BLANK
            reconnect(session)
        }
        throw TagException("Tag uses an unknown key - it isn't blank or a CFS tag")
    }

    private fun authenticate(session: MifareSession, sector: Int, key: ByteArray) {
        if (session.authenticate(sector, key)) return
        reconnect(session)
        if (!session.authenticate(sector, key)) {
            throw TagException("Couldn't authenticate sector $sector")
        }
    }

    private fun reconnect(session: MifareSession) {
        try { session.close() } catch (_: IOException) {}
        session.connect()
    }

    private fun readPayload(session: MifareSession): DecodedPayload? {
        val cipher = ByteArray(48)
        for (i in 0 until 3) session.readBlock(4 + i).copyInto(cipher, i * 16)
        val text = String(CfsCrypto.decryptPayload(cipher), Charsets.ISO_8859_1)
        return CfsPayload.decode(text)
    }
}
