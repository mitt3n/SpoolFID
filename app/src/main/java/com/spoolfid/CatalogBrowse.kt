package com.spoolfid

import com.spoolfid.spoolman.CatalogFilament
import com.spoolfid.spoolman.Filament
import com.spoolfid.spoolman.Vendor

/** A manufacturer in the public filament database, with how many filaments it lists. */
data class ManufacturerEntry(val name: String, val count: Int)

/** Browsing the public filament database (SpoolmanDB) against what Spoolman already has. */
object CatalogBrowse {
    private fun matches(c: CatalogFilament, q: String) =
        c.name.contains(q, ignoreCase = true) || c.material.contains(q, ignoreCase = true)

    /** The manufacturers whose name, or any of whose filaments' names or materials, match [query]; all if it's blank. */
    fun manufacturers(catalog: List<CatalogFilament>, query: String): List<ManufacturerEntry> {
        val q = query.trim()
        return catalog.groupBy { it.manufacturer }
            .filter { (name, items) -> q.isEmpty() || name.contains(q, ignoreCase = true) || items.any { matches(it, q) } }
            .map { (name, items) -> ManufacturerEntry(name, items.size) }
            .sortedBy { it.name.lowercase() }
    }

    /** A manufacturer's filaments matching [query]; all of them when the query names the manufacturer or is blank. */
    fun filamentsOf(catalog: List<CatalogFilament>, manufacturer: String, query: String): List<CatalogFilament> {
        val q = query.trim()
        val all = catalog.filter { it.manufacturer == manufacturer }
        val shown = if (q.isEmpty() || manufacturer.contains(q, ignoreCase = true)) all else all.filter { matches(it, q) }
        return shown.sortedWith(compareBy({ it.name.lowercase() }, { it.material.lowercase() }))
    }

    /** Whether this database entry is already a filament in Spoolman: added from the database before, by its ID. */
    fun registered(entry: CatalogFilament, filaments: List<Filament>): Boolean =
        filaments.any { it.externalId == entry.id }

    /** Your manufacturer of that name, if you have one. Names are compared ignoring case. */
    fun vendorNamed(name: String, vendors: List<Vendor>): Vendor? = vendors.firstOrNull { it.name.equals(name, ignoreCase = true) }
}
