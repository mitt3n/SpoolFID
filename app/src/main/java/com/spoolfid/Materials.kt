package com.spoolfid

import com.spoolfid.nfc.CfsPayload
import com.spoolfid.nfc.WeightBucket
import com.spoolfid.spoolman.Spool

data class MaterialEntry(val id: String, val name: String)

/** What will be written for a spool, and anything approximate about it. */
data class Mapped(
    val payload: CfsPayload,
    val material: MaterialEntry,
    val materialNote: String?,
    val weightNote: String?,
    val colorNote: String?,
)

/**
 * Maps a Spoolman material (PLA, PETG, ...) to the Creality "generic" material ID that goes on the tag.
 *
 * Only the generic profiles are included. They are enough to pick the right print profile on printers that
 * use the tag's material, and firmware that resolves the spool through Spoolman ignores the tag's material
 * anyway.
 */
class MaterialCatalog {
    private fun norm(s: String) = s.uppercase().replace(" ", "").replace("_", "")

    private fun generic(type: String): MaterialEntry? =
        GENERIC[norm(type)]?.let { MaterialEntry(it, "Generic ${type.trim().uppercase()}") }

    /** Exact family matches only; anything else falls back to Generic PLA with a visible note. */
    private fun match(spool: Spool): Pair<MaterialEntry, String?> {
        val material = spool.material?.takeIf { it.isNotBlank() }
            ?: return fallback("No material set in Spoolman")
        generic(material)?.let { return it to null }
        val stripped = MODIFIERS.fold(material.trim()) { m, suffix ->
            if (m.length > suffix.length && m.endsWith(suffix, ignoreCase = true)) m.dropLast(suffix.length).trim() else m
        }
        if (!stripped.equals(material.trim(), ignoreCase = true)) {
            generic(stripped)?.let { return it to "'$material' treated as $stripped" }
        }
        return fallback("'$material' has no matching Creality material")
    }

    private fun fallback(reason: String): Pair<MaterialEntry, String?> =
        MaterialEntry(GENERIC.getValue("PLA"), "Generic PLA") to "$reason - using Generic PLA"

    fun map(spool: Spool): Mapped {
        val (material, note) = match(spool)
        val grams = spool.filamentWeight ?: spool.initialWeight
        val bucket = WeightBucket.nearest(grams ?: 1000.0)
        val weightNote = if (grams == null) "No weight in Spoolman - assuming 1000 g" else null
        val hex = spool.colorHex?.removePrefix("#")?.uppercase()
            ?.takeIf { it.length >= 6 && it.take(6).all { c -> c in "0123456789ABCDEF" } }
            ?.take(6)
        // Jacobean's firmware only links a spool whose Spoolman filament has a single color_hex.
        val colorNote = when {
            spool.multiColor ->
                "Multi-color filament - tag uses the first color. The printer needs a single color in Spoolman to link this spool"
            hex == null ->
                "No color in Spoolman - tag uses white. The printer needs a color in Spoolman to link this spool"
            else -> null
        }
        return Mapped(
            payload = CfsPayload(material.id, hex ?: "FFFFFF", bucket, spool.id),
            material = material,
            materialNote = note,
            weightNote = weightNote,
            colorNote = colorNote,
        )
    }

    companion object {
        private val MODIFIERS = listOf("+", " Plus", " Pro", " HF", " HS", " Basic", " Matte", " Lite")

        /** Material family (normalized) to Creality generic material ID. */
        private val GENERIC = mapOf(
            "PLA" to "00001",
            "PLA-SILK" to "00002",
            "PETG" to "00003",
            "ABS" to "00004",
            "TPU" to "00005",
            "PLA-CF" to "00006",
            "ASA" to "00007",
            "PA" to "00008",
            "PA-CF" to "00009",
            "BVOH" to "00010",
            "PVA" to "00011",
            "HIPS" to "00012",
            "PET-CF" to "00013",
            "PETG-CF" to "00014",
            "PA6-CF" to "00015",
            "PAHT-CF" to "00016",
            "PPS" to "00017",
            "PPS-CF" to "00018",
            "PP" to "00019",
            "PET" to "00020",
            "PC" to "00021",
            "PETG-GF" to "00027",
            "PP-CF" to "00031",
            "PCTG" to "00032",
            "ASA-CF" to "00033",
        )
    }
}
