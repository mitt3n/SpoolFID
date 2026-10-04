package com.spoolfid.nfc

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Creality CFS tag format (MIFARE Classic 1K).
 *
 * Sector 1 (blocks 4-6) holds a 48-char ASCII payload, AES-128-ECB encrypted with a fixed key, behind a
 * per-tag sector key derived from the 4-byte UID. Sector 2 is plaintext filler.
 */
object CfsCrypto {
    private val K1 = "q3bu^t1nqfZ(pf\$1".toByteArray(Charsets.ISO_8859_1)
    private val K2 = "H@CFkRnz@KAtBJp2".toByteArray(Charsets.ISO_8859_1)

    private fun aes(mode: Int, key: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"))
        return c.doFinal(data)
    }

    /** Per-tag 6-byte sector key: first 6 bytes of AES(K1, UID tiled to 16 bytes). */
    fun deriveSectorKey(uid: ByteArray): ByteArray {
        require(uid.size == 4) { "UID must be 4 bytes" }
        val tiled = ByteArray(16) { uid[it % 4] }
        return aes(Cipher.ENCRYPT_MODE, K1, tiled).copyOf(6)
    }

    fun encryptPayload(plain48: ByteArray): ByteArray = aes(Cipher.ENCRYPT_MODE, K2, plain48)
    fun decryptPayload(cipher48: ByteArray): ByteArray = aes(Cipher.DECRYPT_MODE, K2, cipher48)
}

enum class WeightBucket(val code: String, val grams: Int) {
    G250("0082", 250),
    G500("0165", 500),
    G600("0198", 600),
    G750("0247", 750),
    G1000("0330", 1000);

    companion object {
        /** Nearest bucket by grams; ties go to the heavier one. */
        fun nearest(grams: Double): WeightBucket =
            entries.minWith(compareBy({ kotlin.math.abs(it.grams - grams) }, { -it.grams }))

        fun fromCode(code: String): WeightBucket? = entries.firstOrNull { it.code == code }
    }
}

/** The 48 meaningful characters of the payload. */
data class CfsPayload(
    val materialId: String,
    val colorHex: String, // RRGGBB
    val weight: WeightBucket,
    val spoolId: Int,
    val batch: String = "AB1",
    val date: String = "24027",
    val supplier: String = "6A21",
) {
    /** Spool ID goes in both `serial` and `reserve`; the printer firmware resolves Spoolman via `reserve`. */
    fun encode(): String {
        val id = spoolId.toString().padStart(6, '0')
        val s = batch + date + supplier + materialId + "0" + colorHex.uppercase() + weight.code + id + id + "00000000"
        check(s.length == 48) { "payload is ${s.length} chars" }
        return s
    }

    companion object {
        fun decode(text: String): DecodedPayload? {
            if (text.length < 40) return null
            return DecodedPayload(
                batch = text.substring(0, 3),
                date = text.substring(3, 8),
                supplier = text.substring(8, 12),
                materialId = text.substring(12, 17),
                colorHex = text.substring(18, 24),
                weightCode = text.substring(24, 28),
                serial = text.substring(28, 34),
                spoolId = text.substring(34, 40).toIntOrNull(),
            )
        }
    }
}

data class DecodedPayload(
    val batch: String,
    val date: String,
    val supplier: String,
    val materialId: String,
    val colorHex: String,
    val weightCode: String,
    val serial: String,
    /** From the `reserve` field. 0 and 1 mean "no ID" to the printer firmware. */
    val spoolId: Int?,
) {
    val weight: WeightBucket? get() = WeightBucket.fromCode(weightCode)
}
