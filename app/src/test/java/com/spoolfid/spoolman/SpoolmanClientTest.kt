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

/** Runs the real client against a tiny in-process HTTP server that plays Spoolman. */
class SpoolmanClientTest {
    private class Request(val method: String, val path: String, val query: String?, val body: String)

    private lateinit var server: HttpServer
    private val requests = mutableListOf<Request>()
    private val responses = mutableMapOf<String, Pair<Int, String>>()

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            requests += Request(ex.requestMethod, ex.requestURI.path, ex.requestURI.query, body)
            val (code, text) = responses["${ex.requestMethod} ${ex.requestURI.path}"] ?: (404 to """{"message":"nope"}""")
            val bytes = text.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
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
     * The desktop JDK's HttpURLConnection refuses PATCH (Android's accepts it), so the tests that record
     * the tag count use the JDK's modern HTTP client instead.
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

    private fun spoolJson(
        id: Int,
        filament: String = """{"name":"Black","material":"PLA","color_hex":"1D1E1E","weight":1000,"vendor":{"name":"Acme"}}""",
        extra: String = "{}",
    ) = """{"id":$id,"initial_weight":1000.0,"remaining_weight":640.5,"location":"Shelf","archived":false,"extra":$extra,"filament":$filament}"""

    // ---- URL handling ----

    @Test
    fun normalizeAddsSchemeAndStripsTrailingBits() {
        assertEquals("http://192.168.1.5:7912", SpoolmanClient.normalize("192.168.1.5:7912"))
        assertEquals("http://host:7912", SpoolmanClient.normalize("  http://host:7912/  "))
        assertEquals("https://spools.example", SpoolmanClient.normalize("https://spools.example/"))
        assertEquals("http://host:7912", SpoolmanClient.normalize("host:7912/api/v1"))
        assertEquals("", SpoolmanClient.normalize("   "))
    }

    // ---- reading spools ----

    @Test
    fun listSpoolsParsesTheFieldsWeUse() {
        responses["GET /api/v1/spool"] = 200 to "[${spoolJson(3)}]"
        val spools = client().listSpools()
        assertEquals(1, spools.size)
        val s = spools.single()
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
        responses["GET /api/v1/spool"] = 200 to "[]"
        client().listSpools()
        assertEquals("allow_archived=false", requests.single().query)
    }

    @Test
    fun missingOptionalFieldsBecomeNull() {
        responses["GET /api/v1/spool"] = 200 to
            """[{"id":9,"initial_weight":null,"remaining_weight":null,"location":null,"extra":{},
               "filament":{"name":null,"material":null,"color_hex":null,"weight":null,"vendor":null}}]"""
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
        responses["GET /api/v1/spool"] = 200 to
            "[${spoolJson(4, """{"name":"Rainbow","material":"PLA","color_hex":null,"multi_color_hexes":"FF0000, 00FF00,0000FF"}""")}]"
        val s = client().listSpools().single()
        assertEquals("FF0000", s.colorHex)
        assertTrue(s.multiColor)
    }

    @Test
    fun aSingleColorWinsOverTheMultiColorList() {
        responses["GET /api/v1/spool"] = 200 to
            "[${spoolJson(4, """{"name":"X","material":"PLA","color_hex":"112233","multi_color_hexes":"FF0000,00FF00"}""")}]"
        val s = client().listSpools().single()
        assertEquals("112233", s.colorHex)
        assertFalse(s.multiColor)
    }

    @Test
    fun tagCountComesFromTheExtraFieldOrTheLegacyFlag() {
        responses["GET /api/v1/spool"] = 200 to "[" + listOf(
            spoolJson(1),
            spoolJson(2, extra = """{"cfs_tags":"2"}"""),
            spoolJson(3, extra = """{"cfs_tags":"1"}"""),
            spoolJson(4, extra = """{"cfs_tag":"true"}"""),
            spoolJson(5, extra = """{"cfs_tag":"false"}"""),
            spoolJson(6, extra = """{"cfs_tags":"2","cfs_tag":"true"}"""),
            spoolJson(7, extra = """{"cfs_tags":"oops"}"""),
        ).joinToString(",") + "]"
        val counts = client().listSpools().associate { it.id to it.tagCount }
        assertEquals(mapOf(1 to 0, 2 to 2, 3 to 1, 4 to 1, 5 to 0, 6 to 2, 7 to 0), counts)
    }

    @Test
    fun getSpoolReturnsNullWhenNotFound() {
        responses["GET /api/v1/spool/5"] = 200 to spoolJson(5)
        assertEquals(5, client().getSpool(5)!!.id)
        assertNull(client().getSpool(6)) // unknown path -> 404
    }

    // ---- errors ----

    @Test
    fun aServerErrorIsReportedWithItsStatus() {
        responses["GET /api/v1/spool"] = 500 to "boom"
        val e = assertThrows(SpoolmanException::class.java) { client().listSpools() }
        assertTrue(e.message!!.contains("HTTP 500"))
    }

    @Test
    fun anUnreachableServerIsReportedClearly() {
        val port = server.address.port
        server.stop(0)
        val e = assertThrows(SpoolmanException::class.java) { SpoolmanClient("127.0.0.1:$port").listSpools() }
        assertTrue(e.message!!.contains("Can't reach Spoolman"))
    }

    // ---- recording the tag count ----

    @Test
    fun settingTheCountRegistersTheFieldWhenMissingAndKeepsOtherExtras() {
        responses["GET /api/v1/field/spool"] = 200 to """[{"key":"print_label","field_type":"boolean"}]"""
        responses["POST /api/v1/field/spool/cfs_tags"] = 200 to "[]"
        responses["PATCH /api/v1/spool/5"] = 200 to spoolJson(5)
        responses["GET /api/v1/spool/5"] = 200 to spoolJson(5, extra = """{"print_label":"true"}""")
        val spool = client().getSpool(5)!!

        requests.clear()
        client(patchCapable).setTagCount(spool, 2)

        val create = requests.single { it.method == "POST" }
        val field = JSONObject(create.body)
        assertEquals("integer", field.getString("field_type"))
        assertTrue(field.getString("name").isNotBlank())

        val patch = JSONObject(requests.single { it.method == "PATCH" }.body)
        val extra = patch.getJSONObject("extra")
        assertEquals("2", extra.getString("cfs_tags"))
        assertEquals("true", extra.getString("print_label")) // existing extras survive
    }

    @Test
    fun settingTheCountDoesNotReregisterAnExistingField() {
        responses["GET /api/v1/field/spool"] = 200 to """[{"key":"cfs_tags","field_type":"integer"}]"""
        responses["PATCH /api/v1/spool/5"] = 200 to spoolJson(5)
        responses["GET /api/v1/spool/5"] = 200 to spoolJson(5)
        val client = client(patchCapable)
        val spool = client.getSpool(5)!!

        requests.clear()
        client.setTagCount(spool, 1)
        client.setTagCount(spool, 2)

        assertTrue(requests.none { it.method == "POST" })
        assertEquals("the field list is only fetched once", 1, requests.count { it.path == "/api/v1/field/spool" })
        assertEquals(2, requests.count { it.method == "PATCH" })
    }
}
