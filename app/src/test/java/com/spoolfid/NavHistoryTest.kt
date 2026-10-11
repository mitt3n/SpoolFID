package com.spoolfid

import com.spoolfid.spoolman.CatalogFilament
import com.spoolfid.spoolman.Filament
import com.spoolfid.spoolman.Vendor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavHistoryTest {
    @Test
    fun backReturnsToThePreviousPlacesInReverse() {
        val h = NavHistory<String>()
        h.push("Write")
        h.push("Read")
        assertEquals("Read", h.pop())
        assertEquals("Write", h.pop())
        assertNull("nothing left: back should leave the app", h.pop())
    }

    @Test
    fun leavingTheSamePlaceTwiceIsRememberedOnce() {
        val h = NavHistory<String>()
        h.push("Write")
        h.push("Write")
        assertEquals(1, h.size)
    }

    @Test
    fun theOldestPlacesAreDroppedWhenItGetsLong() {
        val h = NavHistory<Int>(limit = 3)
        (1..5).forEach { h.push(it) }
        assertEquals(3, h.size)
        assertEquals(listOf(5, 4, 3), listOf(h.pop(), h.pop(), h.pop()))
    }
}

class CatalogBrowseTest {
    private fun entry(id: String, maker: String, name: String, material: String = "PLA") = CatalogFilament(
        id, maker, name, material, 1.24, 1.75, 1000.0, null, "000000", emptyList(), null, null,
    )

    private val catalog = listOf(
        entry("a1", "Prusament", "Galaxy Black", "ASA"),
        entry("a2", "Prusament", "Orange"),
        entry("b1", "esun", "Silk Gold", "PLA-SILK"),
        entry("c1", "Acme", "Black"),
    )

    @Test
    fun manufacturersAreCountedAndSortedIgnoringCase() {
        val list = CatalogBrowse.manufacturers(catalog, "")
        assertEquals(listOf("Acme", "esun", "Prusament"), list.map { it.name })
        assertEquals(2, list.first { it.name == "Prusament" }.count)
    }

    @Test
    fun searchingMatchesManufacturersByTheirFilamentsToo() {
        assertEquals(listOf("Prusament"), CatalogBrowse.manufacturers(catalog, "galaxy").map { it.name })
        assertEquals(listOf("esun"), CatalogBrowse.manufacturers(catalog, "SILK").map { it.name })
        assertEquals(listOf("Acme", "Prusament"), CatalogBrowse.manufacturers(catalog, "black").map { it.name })
        assertTrue(CatalogBrowse.manufacturers(catalog, "zzz").isEmpty())
    }

    @Test
    fun aManufacturersFilamentsCanBeSearchedAndNamingTheMakerShowsAll() {
        assertEquals(listOf("Galaxy Black", "Orange"), CatalogBrowse.filamentsOf(catalog, "Prusament", "").map { it.name })
        assertEquals(listOf("Orange"), CatalogBrowse.filamentsOf(catalog, "Prusament", "orange").map { it.name })
        assertEquals(2, CatalogBrowse.filamentsOf(catalog, "Prusament", "prus").size)
    }

    private fun filament(externalId: String?) = Filament(
        1, "X", null, "PLA", null, null, null, 1.24, 1.75, 1000.0, null, null, null, null, null, null, externalId,
    )

    @Test
    fun aFilamentIsRegisteredWhenItCameFromTheDatabaseEntry() {
        val have = listOf(filament("a1"), filament(null))
        assertTrue(CatalogBrowse.registered(catalog[0], have))
        assertFalse(CatalogBrowse.registered(catalog[1], have))
    }

    @Test
    fun manufacturersAreMatchedByNameIgnoringCase() {
        val vendors = listOf(Vendor(3, "Prusament", null, null))
        assertEquals(3, CatalogBrowse.vendorNamed("prusament", vendors)?.id)
        assertNull(CatalogBrowse.vendorNamed("esun", vendors))
    }
}
