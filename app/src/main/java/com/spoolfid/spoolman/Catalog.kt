package com.spoolfid.spoolman

import org.json.JSONObject
import java.util.Locale

/** A manufacturer. Spoolman calls these vendors. */
data class Vendor(
    val id: Int,
    val name: String,
    val comment: String?,
    val emptySpoolWeight: Double?,
)

data class Filament(
    val id: Int,
    val name: String?,
    val vendor: Vendor?,
    val material: String?,
    val colorHex: String?,
    /** Comma-separated colors of a multi-color filament. */
    val multiColorHexes: String?,
    val multiColorDirection: String?,
    val density: Double,
    val diameter: Double,
    val weight: Double?,
    val spoolWeight: Double?,
    val price: Double?,
    val articleNumber: String?,
    val comment: String?,
    val extruderTemp: Int?,
    val bedTemp: Int?,
    val externalId: String?,
    val tags: List<SpoolTag> = emptyList(),
    val tagsSupported: Boolean = false,
) {
    val title: String get() = name?.takeIf { it.isNotBlank() } ?: "Filament $id"

    /** The name with its manufacturer and material, as the lists show it. */
    val subtitle: String
        get() = listOfNotNull(vendor?.name, material, weight?.let { "${formatNumber(it)} g" }).joinToString(" · ")
}

/** An entry of the community filament database that Spoolman serves (SpoolmanDB). */
data class CatalogFilament(
    val id: String,
    val manufacturer: String,
    val name: String,
    val material: String,
    val density: Double,
    val diameter: Double,
    val weight: Double,
    val spoolWeight: Double?,
    val colorHex: String?,
    val colorHexes: List<String>,
    val extruderTemp: Int?,
    val bedTemp: Int?,
)

/** Something the user typed that Spoolman would refuse; the message says what to fix. */
class FormError(message: String) : Exception(message)

/** Number text in the forms, shown the way people write it: no trailing ".0". */
fun formatNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else String.format(Locale.ROOT, "%s", value)

/** Typical densities (g/cm³), used to prefill a new filament once its material is known. */
fun defaultDensity(material: String?): Double {
    val m = material.orEmpty().uppercase(Locale.ROOT)
    return when {
        m.startsWith("PETG") || m.startsWith("PCTG") -> 1.27
        m.startsWith("ABS") -> 1.04
        m.startsWith("ASA") -> 1.07
        m.startsWith("TPU") || m.contains("TPU") || m.startsWith("FLEX") -> 1.21
        m.startsWith("PC") -> 1.2
        m.startsWith("PA") || m.startsWith("NYLON") -> 1.14
        m.startsWith("PVA") -> 1.23
        m.startsWith("PP") -> 0.9
        else -> 1.24
    }
}

private fun number(label: String, text: String, min: Double = 0.0, positive: Boolean = false): Double? {
    val t = text.trim().replace(',', '.')
    if (t.isEmpty()) return null
    val v = t.toDoubleOrNull() ?: throw FormError("$label must be a number")
    if (v.isNaN() || v.isInfinite()) throw FormError("$label must be a number")
    if (positive && v <= 0.0) throw FormError("$label must be more than 0")
    if (v < min) throw FormError("$label can't be negative")
    return v
}

private fun whole(label: String, text: String): Int? {
    val t = text.trim()
    if (t.isEmpty()) return null
    return t.toIntOrNull()?.takeIf { it >= 0 } ?: throw FormError("$label must be a whole number")
}

private fun limited(label: String, text: String, max: Int): String? {
    val t = text.trim()
    if (t.length > max) throw FormError("$label can be at most $max characters")
    return t.ifEmpty { null }
}

/** One color as Spoolman stores it: 6 (or 8, with alpha) hex digits, upper case, no '#'. */
fun normalizeHex(text: String): String? {
    val t = text.trim().removePrefix("#").uppercase(Locale.ROOT)
    return t.takeIf { (it.length == 6 || it.length == 8) && it.all { c -> c in '0'..'9' || c in 'A'..'F' } }
}

/**
 * Create sends only what was filled in. Update sends every field, with a null for any that was cleared, so that
 * emptying a box in the form really removes the value in Spoolman.
 */
private fun JSONObject.field(key: String, value: Any?, update: Boolean): JSONObject {
    if (value != null) put(key, value) else if (update) put(key, JSONObject.NULL)
    return this
}

data class VendorForm(
    val name: String = "",
    val comment: String = "",
    val emptySpoolWeight: String = "",
) {
    fun toBody(update: Boolean): JSONObject {
        val n = name.trim()
        if (n.isEmpty()) throw FormError("Give the manufacturer a name")
        if (n.length > 64) throw FormError("The name can be at most 64 characters")
        return JSONObject().put("name", n)
            .field("comment", limited("The comment", comment, 1024), update)
            .field("empty_spool_weight", number("Empty spool weight", emptySpoolWeight), update)
    }

    companion object {
        fun of(v: Vendor) = VendorForm(v.name, v.comment.orEmpty(), v.emptySpoolWeight?.let(::formatNumber).orEmpty())
    }
}

data class FilamentForm(
    val name: String = "",
    val vendorId: Int? = null,
    val material: String = "",
    val colorHex: String = "",
    /** Comma-separated; two or more make it a multi-color filament. */
    val multiColorHexes: String = "",
    val multiColorDirection: String = "coaxial",
    val density: String = "1.24",
    val diameter: String = "1.75",
    val weight: String = "1000",
    val spoolWeight: String = "",
    val price: String = "",
    val articleNumber: String = "",
    val comment: String = "",
    val extruderTemp: String = "",
    val bedTemp: String = "",
    val externalId: String = "",
) {
    val isMultiColor: Boolean get() = multiColorHexes.isNotBlank()

    fun toBody(update: Boolean): JSONObject {
        val density = number("Density", density, positive = true) ?: throw FormError("Density is required")
        val diameter = number("Diameter", diameter, positive = true) ?: throw FormError("Diameter is required")

        var single: String? = null
        var multi: String? = null
        if (isMultiColor) {
            val parts = multiColorHexes.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val colors = parts.map { normalizeHex(it) ?: throw FormError("\"$it\" isn't a color. Use 6 hex digits, like FF8800") }
            if (colors.size < 2) throw FormError("A multi-color filament needs at least two colors")
            multi = colors.joinToString(",")
        } else if (colorHex.isNotBlank()) {
            single = normalizeHex(colorHex) ?: throw FormError("\"${colorHex.trim()}\" isn't a color. Use 6 hex digits, like FF8800")
        }

        return JSONObject()
            .field("name", limited("The name", name, 64), update)
            .field("vendor_id", vendorId, update)
            .field("material", limited("The material", material, 64), update)
            .field("price", number("Price", price), update)
            .put("density", density)
            .put("diameter", diameter)
            .field("weight", number("Net weight", weight, positive = true), update)
            .field("spool_weight", number("Spool weight", spoolWeight), update)
            .field("article_number", limited("The article number", articleNumber, 64), update)
            .field("comment", limited("The comment", comment, 1024), update)
            .field("settings_extruder_temp", whole("Nozzle temperature", extruderTemp), update)
            .field("settings_bed_temp", whole("Bed temperature", bedTemp), update)
            .field("color_hex", single, update)
            .field("multi_color_hexes", multi, update)
            .field("multi_color_direction", if (multi != null) multiColorDirection else null, update)
            .field("external_id", limited("The external ID", externalId, 256), update)
    }

    companion object {
        fun of(f: Filament) = FilamentForm(
            name = f.name.orEmpty(),
            vendorId = f.vendor?.id,
            material = f.material.orEmpty(),
            colorHex = if (f.multiColorHexes.isNullOrBlank()) f.colorHex.orEmpty() else "",
            multiColorHexes = f.multiColorHexes.orEmpty(),
            multiColorDirection = f.multiColorDirection ?: "coaxial",
            density = formatNumber(f.density),
            diameter = formatNumber(f.diameter),
            weight = f.weight?.let(::formatNumber).orEmpty(),
            spoolWeight = f.spoolWeight?.let(::formatNumber).orEmpty(),
            price = f.price?.let(::formatNumber).orEmpty(),
            articleNumber = f.articleNumber.orEmpty(),
            comment = f.comment.orEmpty(),
            extruderTemp = f.extruderTemp?.toString().orEmpty(),
            bedTemp = f.bedTemp?.toString().orEmpty(),
            externalId = f.externalId.orEmpty(),
        )

        /** A filament from the community database, ready to save once its manufacturer is known. */
        fun of(c: CatalogFilament, vendorId: Int?) = FilamentForm(
            name = c.name,
            vendorId = vendorId,
            material = c.material,
            colorHex = if (c.colorHexes.size > 1) "" else c.colorHex.orEmpty(),
            multiColorHexes = if (c.colorHexes.size > 1) c.colorHexes.joinToString(",") else "",
            density = formatNumber(c.density),
            diameter = formatNumber(c.diameter),
            weight = formatNumber(c.weight),
            spoolWeight = c.spoolWeight?.let(::formatNumber).orEmpty(),
            extruderTemp = c.extruderTemp?.toString().orEmpty(),
            bedTemp = c.bedTemp?.toString().orEmpty(),
            externalId = c.id,
        )
    }
}

data class SpoolForm(
    val filamentId: Int? = null,
    /** Net weight of the filament on a full spool; empty falls back to the filament's own. */
    val initialWeight: String = "",
    val spoolWeight: String = "",
    val remainingWeight: String = "",
    val price: String = "",
    val location: String = "",
    val lotNr: String = "",
    val comment: String = "",
    val archived: Boolean = false,
    /** What the remaining weight said when the form opened, so an untouched value isn't sent back. */
    val originalRemaining: String = "",
) {
    fun toBody(update: Boolean): JSONObject {
        val filament = filamentId ?: throw FormError("Choose a filament")
        val initial = number("Starting weight", initialWeight)
        val remaining = number("Remaining weight", remainingWeight)
        if (initial != null && remaining != null && remaining > initial) {
            throw FormError("Remaining weight can't be more than the starting weight")
        }
        // On an edit, Spoolman works out the used weight from the remaining weight, so only send it if it was changed.
        val sendRemaining = !update || remainingWeight.trim() != originalRemaining.trim()
        return JSONObject()
            .put("filament_id", filament)
            .field("initial_weight", initial, update)
            .field("spool_weight", number("Spool weight", spoolWeight), update)
            .field("price", number("Price", price), update)
            .field("location", limited("The location", location, 64), update)
            .field("lot_nr", limited("The lot number", lotNr, 64), update)
            .field("comment", limited("The comment", comment, 1024), update)
            .put("archived", archived)
            .also { if (remaining != null && sendRemaining) it.put("remaining_weight", remaining) }
    }

    companion object {
        fun of(s: Spool) = SpoolForm(
            filamentId = s.filamentId,
            initialWeight = s.initialWeight?.let(::formatNumber).orEmpty(),
            spoolWeight = s.spoolWeight?.let(::formatNumber).orEmpty(),
            remainingWeight = s.remainingWeight?.let { formatNumber(Math.round(it * 10) / 10.0) }.orEmpty(),
            price = s.price?.let(::formatNumber).orEmpty(),
            location = s.location.orEmpty(),
            lotNr = s.lotNr.orEmpty(),
            comment = s.comment.orEmpty(),
            archived = s.archived,
            originalRemaining = s.remainingWeight?.let { formatNumber(Math.round(it * 10) / 10.0) }.orEmpty(),
        )

        /** A new spool of [f], starting full. */
        fun newOf(f: Filament) = SpoolForm(
            filamentId = f.id,
            initialWeight = f.weight?.let(::formatNumber).orEmpty(),
            spoolWeight = f.spoolWeight?.let(::formatNumber).orEmpty(),
            price = f.price?.let(::formatNumber).orEmpty(),
        )
    }
}
