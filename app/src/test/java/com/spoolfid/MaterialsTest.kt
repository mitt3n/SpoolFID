package com.spoolfid

import com.spoolfid.nfc.WeightBucket
import com.spoolfid.spoolman.Spool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MaterialsTest {
    private val catalog = MaterialCatalog()

    private fun spool(
        material: String?,
        color: String? = "FF0000",
        weight: Double? = 1000.0,
        multiColor: Boolean = false,
    ) = Spool(
        id = 7, filamentName = "Test", vendor = null, material = material, colorHex = color,
        filamentWeight = weight, initialWeight = null, remainingWeight = null, location = null,
        extra = emptyMap(), multiColor = multiColor,
    )

    @Test
    fun multiColorUsesFirstColorAndExplainsWhy() {
        val m = catalog.map(spool("PLA", color = "00FF00", multiColor = true))
        assertEquals("00FF00", m.payload.colorHex)
        assertNotNull(m.colorNote)
    }

    @Test
    fun missingColorFallsBackToWhiteWithNote() {
        val m = catalog.map(spool("PLA", color = null))
        assertEquals("FFFFFF", m.payload.colorHex)
        assertNotNull(m.colorNote)
        assertNull(catalog.map(spool("PLA", color = "FF0000")).colorNote)
    }

    @Test
    fun exactFamilies() {
        assertEquals("00001", catalog.map(spool("PLA")).material.id)
        assertEquals("00003", catalog.map(spool("petg")).material.id)
        assertEquals("00004", catalog.map(spool("ABS")).material.id)
        assertEquals("00027", catalog.map(spool("PETG-GF")).material.id)
        assertNull(catalog.map(spool("PLA")).materialNote)
    }

    @Test
    fun modifiersAreStrippedWithANote() {
        val m = catalog.map(spool("PLA+"))
        assertEquals("00001", m.material.id)
        assertNotNull(m.materialNote)
    }

    @Test
    fun unknownMaterialFallsBackToGenericPlaWithNote() {
        val m = catalog.map(spool("Unobtainium"))
        assertEquals("00001", m.material.id)
        assertNotNull(m.materialNote)
        assertNotNull(catalog.map(spool(null)).materialNote)
    }

    @Test
    fun colorAndWeightMapping() {
        assertEquals("FFFFFF", catalog.map(spool("PLA", color = null)).payload.colorHex)
        assertEquals("1D1E1E", catalog.map(spool("PLA", color = "#1d1e1e")).payload.colorHex)
        assertEquals(WeightBucket.G1000, catalog.map(spool("PLA", weight = 1000.0)).payload.weight)
        assertNotNull(catalog.map(spool("PLA", weight = null)).weightNote)
    }
}
