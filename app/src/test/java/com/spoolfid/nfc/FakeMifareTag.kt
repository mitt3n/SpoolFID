package com.spoolfid.nfc

import java.io.IOException

/**
 * An in-memory MIFARE Classic 1K tag. It models the parts of the real thing that [TagIo] depends on:
 * per-sector Key A in the trailer, reads/writes only after authenticating that sector, trailer keys that read
 * back as zeros, and (like real tags) a failed authentication leaves the tag unresponsive until reconnected.
 */
class FakeMifareTag : MifareSession {
    private val blocks = Array(64) { ByteArray(16) }
    private var authenticatedSector = -1
    private var needsReconnect = false

    var connected = false
        private set
    var connectCount = 0
        private set

    /** Fail this many authentication calls before behaving normally. */
    var failAuthAttempts = 0

    /** Silently ignore writes to these blocks (simulates a tag that doesn't take a write). */
    var droppedWriteBlocks: Set<Int> = emptySet()

    /** Throw an I/O error on any write (simulates the tag leaving the field). */
    var ioErrorOnWrite = false

    init {
        // Factory state: every sector on the default key, with the stock access bits.
        for (sector in 0 until 16) {
            blocks[sector * 4 + 3] = DEFAULT_KEY + byteArrayOf(0xFF.toByte(), 0x07, 0x80.toByte(), 0x69) + DEFAULT_KEY
        }
    }

    /** Contents of a block as stored, bypassing authentication. */
    fun raw(block: Int): ByteArray = blocks[block].copyOf()

    /** Every block as stored, for before/after comparisons. */
    fun snapshot(): List<List<Byte>> = blocks.map { it.toList() }

    fun setKeyA(sector: Int, key: ByteArray) {
        key.copyInto(blocks[sector * 4 + 3], 0)
    }

    override fun connect() {
        connected = true
        connectCount++
        needsReconnect = false
        authenticatedSector = -1
    }

    override fun close() {
        connected = false
        authenticatedSector = -1
    }

    override fun authenticate(sector: Int, key: ByteArray): Boolean {
        authenticatedSector = -1
        if (needsReconnect) return false
        if (failAuthAttempts > 0) {
            failAuthAttempts--
            needsReconnect = true
            return false
        }
        val ok = blocks[sector * 4 + 3].copyOfRange(0, 6).contentEquals(key)
        if (ok) authenticatedSector = sector else needsReconnect = true
        return ok
    }

    override fun readBlock(block: Int): ByteArray {
        requireAuthenticated(block)
        val data = blocks[block].copyOf()
        if (block % 4 == 3) {
            // Keys never read back; access bits and GPB do.
            for (i in 0 until 6) data[i] = 0
            for (i in 10 until 16) data[i] = 0
        }
        return data
    }

    override fun writeBlock(block: Int, data: ByteArray) {
        requireAuthenticated(block)
        require(data.size == 16) { "a block is 16 bytes" }
        if (ioErrorOnWrite) throw IOException("tag lost")
        if (block in droppedWriteBlocks) return
        data.copyInto(blocks[block])
    }

    private fun requireAuthenticated(block: Int) {
        if (authenticatedSector != block / 4) throw IOException("sector ${block / 4} is not authenticated")
    }

    companion object {
        val DEFAULT_KEY = ByteArray(6) { 0xFF.toByte() }
    }
}
