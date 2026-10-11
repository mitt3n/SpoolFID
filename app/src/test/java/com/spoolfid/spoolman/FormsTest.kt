package com.spoolfid.spoolman

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FormsTest {
    private fun message(block: () -> Unit): String = assertThrows(FormError::class.java) { block() }.message!!

    // ---- manufacturers ----

    @Test
    fun aManufacturerNeedsAName() {
        assertEquals("Give the manufacturer a name", message { VendorForm(name = "  ").toBody(false) })
    }

    @Test
    fun creatingLeavesBlankFieldsOutButUpdatingClearsThem() {
        val form = VendorForm(name = " Acme ", comment = "", emptySpoolWeight = "")
        val create = form.toBody(update = false)
        assertEquals("Acme", create.getString("name"))
        assertFalse(create.has("comment"))
        assertFalse(create.has("empty_spool_weight"))

        val update = form.toBody(update = true)
        assertTrue("an emptied box must reach Spoolman as null", update.isNull("comment"))
        assertTrue(update.isNull("empty_spool_weight"))
    }

    @Test
    fun spoolWeightAcceptsACommaDecimal() {
        assertEquals(212.5, VendorForm(name = "A", emptySpoolWeight = "212,5").toBody(false).getDouble("empty_spool_weight"), 0.0)
        assertEquals("Empty spool weight must be a number", message { VendorForm(name = "A", emptySpoolWeight = "heavy").toBody(false) })
        assertEquals("Empty spool weight can't be negative", message { VendorForm(name = "A", emptySpoolWeight = "-3").toBody(false) })
    }

    // ---- filaments ----

    @Test
    fun aFilamentFormBuildsTheBodySpoolmanExpects() {
        val body = FilamentForm(
            name = "Galaxy Black", vendorId = 4, material = "PETG", colorHex = "#3d3e3d",
            density = "1.27", diameter = "1.75", weight = "800", spoolWeight = "193", extruderTemp = "250", bedTemp = "80",
        ).toBody(update = false)
        assertEquals("Galaxy Black", body.getString("name"))
        assertEquals(4, body.getInt("vendor_id"))
        assertEquals("3D3E3D", body.getString("color_hex"))
        assertEquals(800.0, body.getDouble("weight"), 0.0)
        assertEquals(250, body.getInt("settings_extruder_temp"))
        assertFalse(body.has("multi_color_hexes"))
        assertFalse(body.has("price"))
    }

    @Test
    fun densityAndDiameterAreRequiredAndPositive() {
        assertEquals("Density is required", message { FilamentForm(density = "").toBody(false) })
        assertEquals("Diameter must be more than 0", message { FilamentForm(diameter = "0").toBody(false) })
        assertEquals("Density must be a number", message { FilamentForm(density = "x").toBody(false) })
    }

    @Test
    fun colorsMustBeHex() {
        assertEquals("\"red\" isn't a color. Use 6 hex digits, like FF8800", message { FilamentForm(colorHex = "red").toBody(false) })
        assertEquals("FF8800", FilamentForm(colorHex = "ff8800").toBody(false).getString("color_hex"))
        assertEquals("FF880080", FilamentForm(colorHex = "FF880080").toBody(false).getString("color_hex"))
        assertNull(normalizeHex("12345"))
    }

    @Test
    fun multiColorNeedsTwoColorsAndDropsTheSingleColor() {
        assertEquals(
            "A multi-color filament needs at least two colors",
            message { FilamentForm(multiColorHexes = "FF0000").toBody(false) },
        )
        val body = FilamentForm(colorHex = "112233", multiColorHexes = "ff0000, #00ff00", multiColorDirection = "longitudinal").toBody(update = true)
        assertEquals("FF0000,00FF00", body.getString("multi_color_hexes"))
        assertEquals("longitudinal", body.getString("multi_color_direction"))
        assertTrue("a multi-color filament has no single color", body.isNull("color_hex"))
    }

    @Test
    fun aSingleColorClearsTheMultiColorFields() {
        val body = FilamentForm(colorHex = "112233").toBody(update = true)
        assertTrue(body.isNull("multi_color_hexes"))
        assertTrue(body.isNull("multi_color_direction"))
    }

    @Test
    fun temperaturesAreWholeNumbers() {
        assertEquals("Nozzle temperature must be a whole number", message { FilamentForm(extruderTemp = "215.5").toBody(false) })
        assertEquals("Bed temperature must be a whole number", message { FilamentForm(bedTemp = "-5").toBody(false) })
    }

    @Test
    fun longTextIsRejectedBeforeSpoolmanDoes() {
        assertEquals("The name can be at most 64 characters", message { FilamentForm(name = "x".repeat(65)).toBody(false) })
    }

    @Test
    fun editingAFilamentRoundTripsThroughTheForm() {
        val f = Filament(
            id = 3, name = "Black", vendor = Vendor(2, "Acme", null, null), material = "PLA", colorHex = "1D1E1E",
            multiColorHexes = null, multiColorDirection = null, density = 1.24, diameter = 1.75, weight = 1000.0,
            spoolWeight = 140.0, price = 19.99, articleNumber = "A-1", comment = null, extruderTemp = 210, bedTemp = 60,
            externalId = null,
        )
        val form = FilamentForm.of(f)
        assertEquals("1000", form.weight)
        assertEquals("19.99", form.price)
        assertEquals(2, form.vendorId)
        val body = form.toBody(update = true)
        assertEquals(1.24, body.getDouble("density"), 0.0)
        assertTrue(body.isNull("comment"))
    }

    @Test
    fun aDatabaseEntryFillsTheForm() {
        val entry = CatalogFilament(
            "prusament_asa_black", "Prusament", "Black", "ASA", 1.07, 1.75, 800.0, 193.0, "000000", emptyList(), 260, 110,
        )
        val form = FilamentForm.of(entry, vendorId = 9)
        assertEquals("ASA", form.material)
        assertEquals("800", form.weight)
        assertEquals("prusament_asa_black", form.externalId)
        assertEquals(9, form.vendorId)
        assertFalse(form.isMultiColor)
    }

    @Test
    fun densityDefaultsFollowTheMaterial() {
        assertEquals(1.24, defaultDensity("PLA"), 0.0)
        assertEquals(1.27, defaultDensity("petg-cf"), 0.0)
        assertEquals(1.04, defaultDensity("ABS"), 0.0)
        assertEquals(1.21, defaultDensity("TPU 95A"), 0.0)
        assertEquals(1.24, defaultDensity(null), 0.0)
    }

    @Test
    fun numbersShowWithoutATrailingZero() {
        assertEquals("1000", formatNumber(1000.0))
        assertEquals("1.75", formatNumber(1.75))
    }

    // ---- spools ----

    private fun spool(remaining: Double? = 640.5) = Spool(
        id = 7, filamentName = "Black", vendor = "Acme", material = "PLA", colorHex = "000000", filamentWeight = 1000.0,
        initialWeight = 1000.0, remainingWeight = remaining, location = null, extra = emptyMap(), filamentId = 3,
        spoolWeight = 140.0, archived = false,
    )

    @Test
    fun aSpoolNeedsAFilament() {
        assertEquals("Choose a filament", message { SpoolForm().toBody(false) })
    }

    @Test
    fun remainingCannotExceedTheStartingWeight() {
        assertEquals(
            "Remaining weight can't be more than the starting weight",
            message { SpoolForm(filamentId = 1, initialWeight = "500", remainingWeight = "600").toBody(false) },
        )
    }

    @Test
    fun aNewSpoolStartsFromItsFilament() {
        val f = Filament(
            3, "Black", null, "PLA", "000000", null, null, 1.24, 1.75, 1000.0, 140.0, 19.0, null, null, null, null, null,
        )
        val form = SpoolForm.newOf(f)
        val body = form.toBody(update = false)
        assertEquals(3, body.getInt("filament_id"))
        assertEquals(1000.0, body.getDouble("initial_weight"), 0.0)
        assertEquals(19.0, body.getDouble("price"), 0.0)
        assertFalse("a full spool needs no remaining weight", body.has("remaining_weight"))
    }

    @Test
    fun anEditOnlySendsTheRemainingWeightIfItChanged() {
        val untouched = SpoolForm.of(spool())
        assertEquals("640.5", untouched.remainingWeight)
        assertFalse(untouched.toBody(update = true).has("remaining_weight"))

        val changed = untouched.copy(remainingWeight = "500")
        assertEquals(500.0, changed.toBody(update = true).getDouble("remaining_weight"), 0.0)
    }

    @Test
    fun anEditClearsEmptiedFieldsAndKeepsTheArchivedFlag() {
        val body: JSONObject = SpoolForm.of(spool()).copy(location = "", archived = true).toBody(update = true)
        assertTrue(body.isNull("location"))
        assertTrue(body.getBoolean("archived"))
        assertEquals(3, body.getInt("filament_id"))
    }
}

class SpoolCopiesTest {
    @Test
    fun copiesDefaultToOneAndAreLimited() {
        assertEquals(1, parseCopies(""))
        assertEquals(5, parseCopies(" 5 "))
        assertEquals("Create between 1 and 50 spools at a time", assertThrows(FormError::class.java) { parseCopies("0") }.message)
        assertEquals("Create between 1 and 50 spools at a time", assertThrows(FormError::class.java) { parseCopies("51") }.message)
        assertEquals("The number of spools must be a whole number", assertThrows(FormError::class.java) { parseCopies("two") }.message)
    }

    @Test
    fun aDuplicateStartsFullAndActive() {
        val s = Spool(
            id = 7, filamentName = "Black", vendor = null, material = "PLA", colorHex = "000000", filamentWeight = 1000.0,
            initialWeight = 1000.0, remainingWeight = 120.0, location = "Shelf", extra = emptyMap(), filamentId = 3,
            archived = true, lotNr = "L-1",
        )
        val body = SpoolForm.duplicateOf(s).toBody(update = false)
        assertEquals(3, body.getInt("filament_id"))
        assertEquals("Shelf", body.getString("location"))
        assertEquals("L-1", body.getString("lot_nr"))
        assertFalse(body.getBoolean("archived"))
        assertFalse("a copy is a full spool", body.has("remaining_weight"))
    }
}
