package com.spoolfid.spoolman

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The manufacturer, filament, spool and tag-management calls, against a fake Spoolman. */
class ManageClientTest {
    private val fake = FakeSpoolman()
    private val client = fake.client()

    @After
    fun stop() = fake.close()

    private val vendorJson = """{"id":2,"name":"Acme","comment":"Good stuff","empty_spool_weight":140.0,"extra":{}}"""

    private fun filamentJson(id: Int = 3, tags: String? = "[]") =
        """{"id":$id,"name":"Black","vendor":$vendorJson,"material":"PLA","color_hex":"1D1E1E","density":1.24,"diameter":1.75,""" +
            """"weight":1000.0,"spool_weight":140.0,"price":19.5,"settings_extruder_temp":210,"settings_bed_temp":60,""" +
            """"multi_color_hexes":null,"extra":{}${tags?.let { ""","tags":$it""" } ?: ""}}"""

    private fun spoolJson(id: Int = 7, archived: Boolean = false) =
        """{"id":$id,"filament":${filamentJson()},"initial_weight":1000.0,"remaining_weight":640.5,"used_weight":359.5,""" +
            """"spool_weight":140.0,"price":19.5,"location":"Shelf A","lot_nr":"L-9","comment":"Opened","archived":$archived,"extra":{},"tags":[]}"""

    // ---- manufacturers ----

    @Test
    fun manufacturersAreListed() {
        fake.respond("GET /api/v1/vendor", 200 to "[$vendorJson]")
        val v = client.listVendors().single()
        assertEquals(Vendor(2, "Acme", "Good stuff", 140.0), v)
    }

    @Test
    fun creatingAManufacturerPostsOnlyWhatWasFilledIn() {
        fake.respond("POST /api/v1/vendor", 201 to vendorJson)
        val v = client.createVendor(VendorForm(name = "Acme"))
        assertEquals(2, v.id)
        val body = JSONObject(fake.lastBody("POST", "/api/v1/vendor"))
        assertEquals(listOf("name"), body.keys().asSequence().toList())
    }

    @Test
    fun updatingAManufacturerPatchesIt() {
        fake.respond("PATCH /api/v1/vendor/2", 200 to vendorJson)
        client.updateVendor(2, VendorForm(name = "Acme", comment = ""))
        val body = JSONObject(fake.lastBody("PATCH", "/api/v1/vendor/2"))
        assertTrue(body.isNull("comment"))
    }

    @Test
    fun deletingAManufacturerAcceptsNoContent() {
        fake.respond("DELETE /api/v1/vendor/2", 204 to "")
        client.deleteVendor(2)
        assertEquals("DELETE", fake.requests.single().method)
    }

    @Test
    fun aRefusedDeleteSurfacesSpoolmansReason() {
        fake.respond("DELETE /api/v1/vendor/2", 409 to """{"message":"Vendor is in use"}""")
        val e = assertThrows(SpoolmanException::class.java) { client.deleteVendor(2) }
        assertEquals("Spoolman returned HTTP 409: Vendor is in use", e.message)
    }

    // ---- filaments ----

    @Test
    fun filamentsAreParsedWithTheirManufacturerAndTags() {
        fake.respond("GET /api/v1/filament", 200 to "[${filamentJson(tags = """[{"uid":"AA11BB22","format":null,"added":"x"}]""")}]")
        val f = client.listFilaments().single()
        assertEquals("Black", f.title)
        assertEquals("Acme", f.vendor?.name)
        assertEquals(1.24, f.density, 0.0)
        assertEquals(210, f.extruderTemp)
        assertEquals(listOf(SpoolTag("AA11BB22", null)), f.tags)
        assertTrue(f.tagsSupported)
        assertEquals("Acme · PLA · 1000 g", f.subtitle)
    }

    @Test
    fun anOlderServerHasNoFilamentTags() {
        fake.respond("GET /api/v1/filament", 200 to "[${filamentJson(tags = null)}]")
        assertFalse(client.listFilaments().single().tagsSupported)
    }

    @Test
    fun creatingAFilamentPostsTheForm() {
        fake.respond("POST /api/v1/filament", 201 to filamentJson(id = 12))
        val f = client.createFilament(FilamentForm(name = "Black", vendorId = 2, material = "PLA", colorHex = "1d1e1e"))
        assertEquals(12, f.id)
        val body = JSONObject(fake.lastBody("POST", "/api/v1/filament"))
        assertEquals(2, body.getInt("vendor_id"))
        assertEquals("1D1E1E", body.getString("color_hex"))
        assertEquals(1.24, body.getDouble("density"), 0.0)
    }

    @Test
    fun updatingAFilamentCanRemoveItsManufacturer() {
        fake.respond("PATCH /api/v1/filament/3", 200 to filamentJson())
        client.updateFilament(3, FilamentForm(name = "Black", vendorId = null))
        assertTrue(JSONObject(fake.lastBody("PATCH", "/api/v1/filament/3")).isNull("vendor_id"))
    }

    @Test
    fun validationErrorsAreReadableNotJustAStatus() {
        fake.respond(
            "POST /api/v1/filament",
            422 to """{"detail":[{"type":"greater_than","loc":["body","weight"],"msg":"Input should be greater than 0"}]}""",
        )
        val e = assertThrows(SpoolmanException::class.java) { client.createFilament(FilamentForm()) }
        assertEquals("Spoolman returned HTTP 422: weight: Input should be greater than 0", e.message)
    }

    @Test
    fun getFilamentReturnsNullWhenGone() {
        fake.respond("GET /api/v1/filament/3", 200 to filamentJson())
        assertEquals(3, client.getFilament(3)!!.id)
        assertNull(client.getFilament(4))
    }

    // ---- spools ----

    @Test
    fun spoolsCarryTheirFilamentAndDetails() {
        fake.respond("GET /api/v1/spool", 200 to "[${spoolJson(archived = true)}]")
        val s = client.listSpools(includeArchived = true).single()
        assertEquals("allow_archived=true", fake.requests.single().query)
        assertEquals(3, s.filamentId)
        assertTrue(s.archived)
        assertEquals("L-9", s.lotNr)
        assertEquals("Opened", s.comment)
        assertEquals(19.5, s.price!!, 0.0)
        assertEquals(359.5, s.usedWeight!!, 0.0)
    }

    @Test
    fun creatingASpoolPostsItsFilament() {
        fake.respond("POST /api/v1/spool", 201 to spoolJson(id = 21))
        val s = client.createSpool(SpoolForm(filamentId = 3, initialWeight = "1000", location = "Shelf A"))
        assertEquals(21, s.id)
        val body = JSONObject(fake.lastBody("POST", "/api/v1/spool"))
        assertEquals(3, body.getInt("filament_id"))
        assertEquals("Shelf A", body.getString("location"))
        assertFalse(body.has("remaining_weight"))
    }

    @Test
    fun archivingASpoolPatchesTheFlag() {
        fake.respond("PATCH /api/v1/spool/7", 200 to spoolJson(archived = true))
        val s = client.updateSpool(7, SpoolForm(filamentId = 3, archived = true))
        assertTrue(s.archived)
        assertTrue(JSONObject(fake.lastBody("PATCH", "/api/v1/spool/7")).getBoolean("archived"))
    }

    @Test
    fun deletingASpool() {
        fake.respond("DELETE /api/v1/spool/7", 200 to """{"message":"Success!"}""")
        client.deleteSpool(7)
        assertEquals("DELETE /api/v1/spool/7", fake.requests.single().let { "${it.method} ${it.path}" })
    }

    // ---- suggestions and the filament database ----

    @Test
    fun materialsAndLocationsComeAsPlainLists() {
        fake.respond("GET /api/v1/material", 200 to """["PLA","PETG",""]""")
        fake.respond("GET /api/v1/location", 200 to """["Shelf A"]""")
        assertEquals(listOf("PLA", "PETG"), client.materials())
        assertEquals(listOf("Shelf A"), client.locations())
    }

    @Test
    fun theFilamentDatabaseIsSearched() {
        fake.respond(
            "GET /api/v1/external/filament/search",
            200 to """[{"id":"prusament_asa_black","manufacturer":"Prusament","name":"Black","material":"ASA","density":1.07,""" +
                """"weight":800.0,"spool_weight":193.0,"diameter":1.75,"color_hex":"000000","extruder_temp":260,"bed_temp":110}]""",
        )
        val c = client.searchCatalog("prusa mid")
        assertEquals("query=prusa+mid&limit=30", fake.requests.single().query)
        assertEquals("Prusament", c.single().manufacturer)
        assertEquals(800.0, c.single().weight, 0.0)
        assertEquals(260, c.single().extruderTemp)
        assertTrue(c.single().colorHexes.isEmpty())
    }

    // ---- tags on filaments and spools ----

    @Test
    fun aFilamentTagIsLinkedWithoutAFormatUnlessGiven() {
        fake.respond("POST /api/v1/filament/3/tag", 201 to """{"uid":"AA11BB22","format":null,"added":"x"}""")
        assertEquals(TagMove.None, client.linkFilamentTag(3, "AA11BB22"))
        assertFalse(JSONObject(fake.lastBody("POST", "/api/v1/filament/3/tag")).has("format"))
    }

    @Test
    fun linkingATagTakesItFromWhereverItWas() {
        fake.respond(
            "POST /api/v1/filament/3/tag",
            409 to """{"message":"in use","spool_id":9,"filament_id":null}""",
            201 to """{"uid":"AA11BB22","format":null,"added":"x"}""",
        )
        fake.respond("DELETE /api/v1/spool/9/tag/AA11BB22", 204 to "")
        assertEquals(TagMove.FromSpool(9), client.linkFilamentTag(3, "AA11BB22"))
    }

    @Test
    fun aTagAlreadyOnThisFilamentIsLeftAlone() {
        fake.respond("POST /api/v1/filament/3/tag", 409 to """{"message":"in use","spool_id":null,"filament_id":3}""")
        assertEquals(TagMove.None, client.linkFilamentTag(3, "AA11BB22"))
        assertTrue(fake.requests.none { it.method == "DELETE" })
    }

    @Test
    fun aSpoolIdIsNotMistakenForAFilamentId() {
        // Spool 3 and filament 3 are different things: a tag on filament 3 must be moved to spool 3, not skipped.
        fake.respond(
            "POST /api/v1/spool/3/tag",
            409 to """{"message":"in use","spool_id":null,"filament_id":3}""",
            201 to """{"uid":"AA11BB22","format":"creality","added":"x"}""",
        )
        fake.respond("DELETE /api/v1/filament/3/tag/AA11BB22", 204 to "")
        assertEquals(TagMove.FromFilament(3), client.linkTag(3, "AA11BB22"))
    }

    @Test
    fun scanLinkedSpoolTagsCanOmitTheFormat() {
        fake.respond("POST /api/v1/spool/5/tag", 201 to """{"uid":"AA11BB22","format":null,"added":"x"}""")
        client.linkTag(5, "AA11BB22", format = null)
        assertFalse(JSONObject(fake.lastBody("POST", "/api/v1/spool/5/tag")).has("format"))
    }

    @Test
    fun aFilamentTagIsUnlinked() {
        fake.respond("DELETE /api/v1/filament/3/tag/AA11BB22", 204 to "")
        client.unlinkFilamentTag(3, "AA11BB22")
        assertEquals("/api/v1/filament/3/tag/AA11BB22", fake.requests.single().path)
    }

    @Test
    fun weighingInSendsTheGrossWeight() {
        fake.respond("PUT /api/v1/spool/7/measure", 200 to spoolJson())
        val s = client.measureSpool(7, 812.5)
        assertEquals(812.5, JSONObject(fake.lastBody("PUT", "/api/v1/spool/7/measure")).getDouble("weight"), 0.0)
        assertEquals(640.5, s.remainingWeight!!, 0.0)
    }

    @Test
    fun weighingInFailsClearlyWhenTheSpoolWeightIsUnknown() {
        fake.respond("PUT /api/v1/spool/7/measure", 400 to """{"message":"Spool weight is not set"}""")
        val e = assertThrows(SpoolmanException::class.java) { client.measureSpool(7, 800.0) }
        assertEquals("Spoolman returned HTTP 400: Spool weight is not set", e.message)
    }
}

class ScanReportTest {
    private val fake = FakeSpoolman()

    @org.junit.After
    fun stop() = fake.close()

    @Test
    fun aScanIsReportedAsATagReader() {
        fake.respond("POST /api/v1/tag/scan", 200 to """{"uid":"AA11BB22","reader_id":"spoolfid-1a2b3c4d","matched_spool_id":12,"matched_filament_id":null}""")
        val m = fake.client().reportScan("AA11BB22", "spoolfid-1a2b3c4d", "SpoolFID", "creality")
        assertEquals(ScanMatch(12, null), m)
        val body = JSONObject(fake.lastBody("POST", "/api/v1/tag/scan"))
        assertEquals("AA11BB22", body.getString("uid"))
        assertEquals("spoolfid-1a2b3c4d", body.getString("reader_id"))
        assertEquals("SpoolFID", body.getString("name"))
        assertEquals("creality", body.getString("format"))
    }

    @Test
    fun anUnlinkedTagComesBackWithNoMatchAndNoFormatIsSentWhenUnknown() {
        fake.respond("POST /api/v1/tag/scan", 200 to """{"uid":"AA11BB22","reader_id":"r","matched_spool_id":null,"matched_filament_id":null}""")
        assertEquals(ScanMatch(null, null), fake.client().reportScan("AA11BB22", "r", "SpoolFID"))
        assertFalse(JSONObject(fake.lastBody("POST", "/api/v1/tag/scan")).has("format"))
    }

    @Test
    fun anOlderSpoolmanWithoutTheEndpointFailsClearly() {
        val e = assertThrows(SpoolmanException::class.java) { fake.client().reportScan("AA11BB22", "r", "SpoolFID") }
        assertTrue(e.message!!.startsWith("Spoolman returned HTTP 404"))
    }
}
