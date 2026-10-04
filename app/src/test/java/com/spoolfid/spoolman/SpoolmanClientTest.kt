package com.spoolfid.spoolman

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.util.Collections

/** Runs the real client against a tiny in-process HTTP server that plays Spoolman. */
class SpoolmanClientTest {
    private class Request(val method: String, val path: String, val query: String?, val body: String)

    private lateinit var server: HttpServer
    private val requests = Collections.synchronizedList(mutableListOf<Request>())

    /** Canned responses per "METHOD path"; when several are queued they are served in order and the last repeats. */
    private val responses = mutableMapOf<String, ArrayDeque<Pair<Int, String>>>()

    private fun respond(key: String, vararg answers: Pair<Int, String>) {
        responses[key] = ArrayDeque(answers.toList())
    }

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            requests += Request(ex.requestMethod, ex.requestURI.path, ex.requestURI.query, body)
            val queue = responses["${ex.requestMethod} ${ex.requestURI.path}"]
            val (code, text) = when {
                queue == null -> 404 to """{"message":"nope"}"""
                queue.size > 1 -> queue.removeFirst()
                else -> queue.first()
            }
            ex.responseHeaders.add("Content-Type", "application/json")
            if (code == 204) {
                ex.sendResponseHeaders(204, -1)
            } else {
                val bytes = text.toByteArray()
                ex.sendResponseHeaders(code, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            ex.close()
        }
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    private fun client(transport: HttpTransport = UrlConnectionTransport) =
        SpoolmanClient("127.0.0.1:${server.address.port}", transport)

    /**
     * The desktop JDK's HttpURLConnection refuses PATCH (Android's accepts it), so the legacy count tests use the
     * JDK's modern HTTP client instead.
     */
    private val patchCapable = HttpTransport { method, url, body ->
        val publisher = if (body == null) BodyPublishers.noBody() else BodyPublishers.ofString(body)
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/json")
            .method(method, publisher)
            .build()
        val response = HttpClient.newHttpClient().send(request, BodyHandlers.ofString())
        HttpResponse(response.statusCode(), response.body())
    }

    /** A spool as Spoolman 0.27 returns it. Pass tags = null to mimic an older server that has no `tags` field. */
    private fun spoolJson(
        id: Int,
        filament: String = """{"name":"Black","material":"PLA","color_hex":"1D1E1E","weight":1000,"vendor":{"name":"Acme"}}""",
        extra: String = "{}",
        tags: String? = "[]",
    ) = """{"id":$id,"initial_weight":1000.0,"remaining_weight":640.5,"location":"Shelf","archived":false,""" +
        """"extra":$extra,${tags?.let { """"tags":$it,""" } ?: ""}"filament":$filament}"""

    private fun tagJson(uid: String, format: String? = "creality") =
        """{"uid":"$uid","format":${format?.let { "\"$it\"" } ?: "null"},"added":"2026-10-04T00:00:00Z"}"""

    // ---- URL handling and server info ----

    @Test
    fun normalizeAddsSchemeAndStripsTrailingBits() {
        assertEquals("http://192.168.1.5:7912", SpoolmanClient.normalize("192.168.1.5:7912"))
        assertEquals("http://host:7912", SpoolmanClient.normalize("  http://host:7912/  "))
        assertEquals("https://spools.example", SpoolmanClient.normalize("https://spools.example/"))
        assertEquals("http://host:7912", SpoolmanClient.normalize("host:7912/api/v1"))
        assertEquals("", SpoolmanClient.normalize("   "))
    }

    @Test
    fun infoReportsTheServerVersion() {
        respond("GET /api/v1/info", 200 to """{"version":"0.27.0","debug_mode":false}""")
        assertEquals("0.27.0", client().info().version)
    }

    // ---- reading spools ----

    @Test
    fun listSpoolsParsesTheFieldsWeUse() {
        respond("GET /api/v1/spool", 200 to "[${spoolJson(3)}]")
        val s = client().listSpools().single()
        assertEquals(3, s.id)
        assertEquals("Black", s.filamentName)
        assertEquals("Acme", s.vendor)
        assertEquals("PLA", s.material)
        assertEquals("1D1E1E", s.colorHex)
        assertFalse(s.multiColor)
        assertEquals(1000.0, s.filamentWeight!!, 0.0)
        assertEquals(640.5, s.remainingWeight!!, 0.0)
        assertEquals("Shelf", s.location)
        assertEquals(0, s.tagCount)
    }

    @Test
    fun listSpoolsExcludesArchivedSpools() {
        respond("GET /api/v1/spool", 200 to "[]")
        client().listSpools()
        assertEquals("allow_archived=false", requests.single().query)
    }

    @Test
    fun missingOptionalFieldsBecomeNull() {
        respond(
            "GET /api/v1/spool",
            200 to """[{"id":9,"initial_weight":null,"remaining_weight":null,"location":null,"extra":{},
               "filament":{"name":null,"material":null,"color_hex":null,"weight":null,"vendor":null}}]""",
        )
        val s = client().listSpools().single()
        assertNull(s.filamentName)
        assertNull(s.vendor)
        assertNull(s.material)
        assertNull(s.colorHex)
        assertNull(s.filamentWeight)
        assertNull(s.remainingWeight)
        assertNull(s.location)
        assertEquals("Spool 9", s.title)
    }

    @Test
    fun multiColorFilamentUsesItsFirstColor() {
        respond(
            "GET /api/v1/spool",
            200 to "[${spoolJson(4, """{"name":"Rainbow","material":"PLA","color_hex":null,"multi_color_hexes":"FF0000, 00FF00,0000FF"}""")}]",
        )
        val s = client().listSpools().single()
        assertEquals("FF0000", s.colorHex)
        assertTrue(s.multiColor)
    }

    @Test
    fun aSingleColorWinsOverTheMultiColorList() {
        respond(
            "GET /api/v1/spool",
            200 to "[${spoolJson(4, """{"name":"X","material":"PLA","color_hex":"112233","multi_color_hexes":"FF0000,00FF00"}""")}]",
        )
        val s = client().listSpools().single()
        assertEquals("112233", s.colorHex)
        assertFalse(s.multiColor)
    }

    @Test
    fun getSpoolReturnsNullWhenNotFound() {
        respond("GET /api/v1/spool/5", 200 to spoolJson(5))
        assertEquals(5, client().getSpool(5)!!.id)
        assertNull(client().getSpool(6)) // unknown path -> 404
    }

    // ---- native tags: Spoolman is the source of truth ----

    @Test
    fun linkedTagsAreParsedAndCounted() {
        respond(
            "GET /api/v1/spool",
            200 to "[${spoolJson(2, tags = "[${tagJson("AA11BB22")},${tagJson("CC33DD44", format = null)}]")}]",
        )
        val s = client().listSpools().single()
        assertTrue(s.tagsSupported)
        assertEquals(2, s.tagCount)
        assertEquals(listOf(SpoolTag("AA11BB22", "creality"), SpoolTag("CC33DD44", null)), s.tags)
        assertTrue(s.hasTag("aa11bb22")) // case-insensitive
        assertFalse(s.hasTag("FFFFFFFF"))
    }

    @Test
    fun tagsWrittenByVersionOneAreRememberedButMarkedNotLinked() {
        // SpoolFID 1.0 only kept a count in a custom field. Until tags are linked, that count stands in for them,
        // so already-tagged spools aren't shown as untagged.
        respond("GET /api/v1/spool", 200 to "[${spoolJson(2, extra = """{"cfs_tags":"2"}""", tags = "[]")}]")
        val s = client().listSpools().single()
        assertTrue(s.tagsSupported)
        assertEquals(2, s.tagCount)
        assertEquals(2, s.unlinkedCount)
    }

    @Test
    fun onceTagsAreLinkedSpoolmansListTakesOverFromTheOldCount() {
        respond(
            "GET /api/v1/spool",
            200 to "[${spoolJson(2, extra = """{"cfs_tags":"2"}""", tags = "[${tagJson("AA11BB22")}]")}]",
        )
        val s = client().listSpools().single()
        assertEquals(1, s.tagCount)
        assertEquals(0, s.unlinkedCount)
    }

    @Test
    fun aSpoolWithNoRecordAtAllIsUntagged() {
        respond("GET /api/v1/spool", 200 to "[${spoolJson(2)}]")
        val s = client().listSpools().single()
        assertEquals(0, s.tagCount)
        assertEquals(0, s.unlinkedCount)
    }

    @Test
    fun onOlderServersNothingCountsAsUnlinked() {
        respond("GET /api/v1/spool", 200 to "[${spoolJson(2, extra = """{"cfs_tags":"2"}""", tags = null)}]")
        val s = client().listSpools().single()
        assertEquals(2, s.tagCount)
        assertEquals(0, s.unlinkedCount)
    }

    @Test
    fun olderServersFallBackToTheCustomField() {
        respond(
            "GET /api/v1/spool",
            200 to "[" + listOf(
                spoolJson(1, tags = null),
                spoolJson(2, extra = """{"cfs_tags":"2"}""", tags = null),
                spoolJson(3, extra = """{"cfs_tag":"true"}""", tags = null),
                spoolJson(4, extra = """{"cfs_tag":"false"}""", tags = null),
                spoolJson(5, extra = """{"cfs_tags":"oops"}""", tags = null),
            ).joinToString(",") + "]",
        )
        val spools = client().listSpools()
        assertTrue(spools.none { it.tagsSupported })
        assertEquals(mapOf(1 to 0, 2 to 2, 3 to 1, 4 to 0, 5 to 0), spools.associate { it.id to it.tagCount })
    }

    @Test
    fun spoolsWithTagFiltersOnTheServerAndDoubleChecksTheAnswer() {
        // A server that ignores the filter returns everything; only the spool really holding the tag may come back.
        respond(
            "GET /api/v1/spool",
            200 to "[${spoolJson(1)},${spoolJson(2, tags = "[${tagJson("AA11BB22")}]")}]",
        )
        val found = client().spoolsWithTag("AA11BB22")
        assertEquals(listOf(2), found.map { it.id })
        assertEquals("allow_archived=true&tag=AA11BB22", requests.single().query)
    }

    // ---- linking tags ----

    @Test
    fun linkingATagSendsItsUidAndTheCrealityFormat() {
        respond("POST /api/v1/spool/5/tag", 201 to tagJson("11223344"))
        assertEquals(TagMove.None, client().linkTag(5, "11223344"))
        val body = JSONObject(requests.single().body)
        assertEquals("11223344", body.getString("uid"))
        assertEquals("creality", body.getString("format"))
    }

    @Test
    fun relinkingATagThatThisSpoolAlreadyHoldsChangesNothing() {
        respond("POST /api/v1/spool/5/tag", 409 to """{"message":"already linked","spool_id":5,"filament_id":null}""")
        assertEquals(TagMove.None, client().linkTag(5, "11223344"))
        assertTrue(requests.none { it.method == "DELETE" })
    }

    @Test
    fun aTagHeldByAnotherSpoolIsMovedHere() {
        respond(
            "POST /api/v1/spool/5/tag",
            409 to """{"message":"already linked","spool_id":9,"filament_id":null}""",
            201 to tagJson("11223344"),
        )
        respond("DELETE /api/v1/spool/9/tag/11223344", 204 to "")
        assertEquals(TagMove.FromSpool(9), client().linkTag(5, "11223344"))
        assertEquals(
            listOf("POST /api/v1/spool/5/tag", "DELETE /api/v1/spool/9/tag/11223344", "POST /api/v1/spool/5/tag"),
            requests.map { "${it.method} ${it.path}" },
        )
    }

    @Test
    fun aTagHeldByAFilamentIsMovedHere() {
        respond(
            "POST /api/v1/spool/5/tag",
            409 to """{"message":"already linked","spool_id":null,"filament_id":3}""",
            201 to tagJson("11223344"),
        )
        respond("DELETE /api/v1/filament/3/tag/11223344", 204 to "")
        assertEquals(TagMove.FromFilament(3), client().linkTag(5, "11223344"))
    }

    @Test
    fun aConflictThatNamesNoHolderIsAnError() {
        respond("POST /api/v1/spool/5/tag", 409 to """{"message":"conflict","spool_id":null,"filament_id":null}""")
        assertThrows(SpoolmanException::class.java) { client().linkTag(5, "11223344") }
    }

    @Test
    fun linkingToAMissingSpoolExplainsWhy() {
        respond("POST /api/v1/spool/77/tag", 404 to """{"message":"No spool with ID 77 found."}""")
        val e = assertThrows(SpoolmanException::class.java) { client().linkTag(77, "11223344") }
        assertTrue(e.message!!.contains("HTTP 404"))
        assertTrue(e.message!!.contains("No spool with ID 77"))
    }

    @Test
    fun unlinkingATagCallsDelete() {
        respond("DELETE /api/v1/spool/5/tag/11223344", 204 to "")
        client().unlinkTag(5, "11223344")
        assertEquals("DELETE", requests.single().method)
    }

    // ---- recording a freshly written tag ----

    @Test
    fun recordingATagLinksItAndReturnsTheSpoolAsSpoolmanNowHasIt() {
        respond("POST /api/v1/spool/5/tag", 201 to tagJson("11223344"))
        respond("GET /api/v1/spool/5", 200 to spoolJson(5, tags = "[${tagJson("11223344")}]"))
        val spool = client().listSpoolsFrom(spoolJson(5))
        val record = client().recordTag(spool, "11223344", sessionCount = 1)
        assertEquals(TagMove.None, record.moved)
        assertEquals(1, record.spool!!.tagCount)
        assertTrue(record.spool!!.hasTag("11223344"))
    }

    @Test
    fun onAnOlderServerRecordingATagUpdatesTheCustomFieldInstead() {
        respond("GET /api/v1/field/spool", 200 to "[]")
        respond("POST /api/v1/field/spool/cfs_tags", 200 to "[]")
        respond("PATCH /api/v1/spool/5", 200 to spoolJson(5, tags = null))
        val spool = client().listSpoolsFrom(spoolJson(5, extra = """{"print_label":"true"}""", tags = null))

        val record = client(patchCapable).recordTag(spool, "11223344", sessionCount = 2)
        assertNull(record.spool)

        assertTrue("no native tag call on an old server", requests.none { it.path.endsWith("/tag") })
        val field = JSONObject(requests.single { it.method == "POST" }.body)
        assertEquals("integer", field.getString("field_type"))
        val extra = JSONObject(requests.single { it.method == "PATCH" }.body).getJSONObject("extra")
        assertEquals("2", extra.getString("cfs_tags"))
        assertEquals("true", extra.getString("print_label")) // existing extras survive
    }

    @Test
    fun theLegacyFieldIsOnlyRegisteredOnce() {
        respond("GET /api/v1/field/spool", 200 to """[{"key":"cfs_tags","field_type":"integer"}]""")
        respond("PATCH /api/v1/spool/5", 200 to spoolJson(5, tags = null))
        val client = client(patchCapable)
        val spool = client.listSpoolsFrom(spoolJson(5, tags = null))

        client.setLegacyTagCount(spool, 1)
        client.setLegacyTagCount(spool, 2)

        assertTrue(requests.none { it.method == "POST" })
        assertEquals("the field list is only fetched once", 1, requests.count { it.path == "/api/v1/field/spool" })
        assertEquals(2, requests.count { it.method == "PATCH" })
    }

    // ---- errors ----

    @Test
    fun aServerErrorIsReportedWithItsStatus() {
        respond("GET /api/v1/spool", 500 to "boom")
        val e = assertThrows(SpoolmanException::class.java) { client().listSpools() }
        assertTrue(e.message!!.contains("HTTP 500"))
    }

    @Test
    fun spoolmansOwnMessageIsIncluded() {
        respond("GET /api/v1/spool", 500 to """{"message":"database is locked"}""")
        val e = assertThrows(SpoolmanException::class.java) { client().listSpools() }
        assertTrue(e.message!!.contains("database is locked"))
    }

    @Test
    fun anUnreachableServerIsReportedClearly() {
        val port = server.address.port
        server.stop(0)
        val e = assertThrows(SpoolmanException::class.java) { SpoolmanClient("127.0.0.1:$port").listSpools() }
        assertTrue(e.message!!.contains("Can't reach Spoolman"))
    }

    /** Parses a spool through the real client by serving it once; keeps the tests above readable. */
    private fun SpoolmanClient.listSpoolsFrom(json: String): Spool {
        val saved = responses["GET /api/v1/spool"]
        respond("GET /api/v1/spool", 200 to "[$json]")
        val spool = listSpools().single()
        if (saved == null) responses.remove("GET /api/v1/spool") else responses["GET /api/v1/spool"] = saved
        requests.clear()
        return spool
    }
}
