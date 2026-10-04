package com.spoolfid.spoolman

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.net.UnknownHostException

data class Spool(
    val id: Int,
    val filamentName: String?,
    val vendor: String?,
    val material: String?,
    val colorHex: String?,
    val filamentWeight: Double?,
    val initialWeight: Double?,
    val remainingWeight: Double?,
    val location: String?,
    /** Tags written for this spool in its latest write session (0 = none). */
    val tagCount: Int,
    /** Raw extra fields: values are JSON-encoded strings, as Spoolman stores them. */
    val extra: Map<String, String>,
    /** The filament has several colors, so [colorHex] is just the first of them. */
    val multiColor: Boolean = false,
) {
    val title: String get() = filamentName?.takeIf { it.isNotBlank() } ?: "Spool $id"
}

class SpoolmanException(message: String) : Exception(message)

class HttpResponse(val code: Int, val body: String)

/** Sends one HTTP request. Swappable so tests can use a transport that supports PATCH (Android's does, the desktop JDK's doesn't). */
fun interface HttpTransport {
    @Throws(IOException::class)
    fun send(method: String, url: String, body: String?): HttpResponse
}

/** The real transport: Android's HttpURLConnection. */
object UrlConnectionTransport : HttpTransport {
    override fun send(method: String, url: String, body: String?): HttpResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 5_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return HttpResponse(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            conn.disconnect()
        }
    }
}

class SpoolmanClient(rawUrl: String, private val transport: HttpTransport = UrlConnectionTransport) {
    private val base: String = normalize(rawUrl)
    private var fieldEnsured = false

    fun listSpools(): List<Spool> {
        val arr = JSONArray(request("GET", "/api/v1/spool?allow_archived=false"))
        return (0 until arr.length()).map { parseSpool(arr.getJSONObject(it)) }
    }

    fun getSpool(id: Int): Spool? = try {
        parseSpool(JSONObject(request("GET", "/api/v1/spool/$id")))
    } catch (e: SpoolmanException) {
        if (e.message?.contains("404") == true) null else throw e
    }

    /** Sets the integer extra field [TAGS_FIELD] on the spool, registering the field first if needed. */
    fun setTagCount(spool: Spool, count: Int) {
        ensureField()
        val extra = JSONObject()
        spool.extra.forEach { (k, v) -> extra.put(k, v) }
        extra.put(TAGS_FIELD, count.toString())
        request("PATCH", "/api/v1/spool/${spool.id}", JSONObject().put("extra", extra).toString())
    }

    private fun ensureField() {
        if (fieldEnsured) return
        val fields = JSONArray(request("GET", "/api/v1/field/spool"))
        val exists = (0 until fields.length()).any { fields.getJSONObject(it).optString("key") == TAGS_FIELD }
        if (!exists) {
            val body = JSONObject().put("name", "CFS tags written").put("field_type", "integer").toString()
            request("POST", "/api/v1/field/spool/$TAGS_FIELD", body)
        }
        fieldEnsured = true
    }

    private fun request(method: String, path: String, body: String? = null): String {
        val response = try {
            transport.send(method, base + path, body)
        } catch (e: MalformedURLException) {
            throw SpoolmanException("Invalid Spoolman URL")
        } catch (e: UnknownHostException) {
            throw SpoolmanException("Unknown host - check the Spoolman address")
        } catch (e: IOException) {
            throw SpoolmanException("Can't reach Spoolman at $base\n(${e.javaClass.simpleName}: ${e.message})")
        }
        if (response.code !in 200..299) throw SpoolmanException("Spoolman returned HTTP ${response.code}")
        return response.body
    }

    companion object {
        const val TAGS_FIELD = "cfs_tags"

        /** Earlier versions stored a boolean "cfs_tag" (one tag); still read as a count of 1. */
        private const val LEGACY_FIELD = "cfs_tag"

        fun normalize(raw: String): String {
            var u = raw.trim().trimEnd('/')
            if (u.isEmpty()) return u
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
            return u.removeSuffix("/api/v1")
        }

        private fun JSONObject.str(key: String): String? = if (isNull(key)) null else getString(key)
        private fun JSONObject.num(key: String): Double? = if (isNull(key)) null else getDouble(key)

        private fun parseSpool(o: JSONObject): Spool {
            val f = o.optJSONObject("filament")
            val extraObj = o.optJSONObject("extra")
            val extra = buildMap {
                extraObj?.keys()?.forEach { k -> put(k, extraObj.getString(k)) }
            }
            // Multi-color filaments have no color_hex, only a comma-separated multi_color_hexes.
            val single = f?.str("color_hex")?.takeIf { it.isNotBlank() }
            val firstOfMany = f?.str("multi_color_hexes")?.split(",")?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
            return Spool(
                id = o.getInt("id"),
                filamentName = f?.str("name"),
                vendor = f?.optJSONObject("vendor")?.str("name"),
                material = f?.str("material"),
                colorHex = single ?: firstOfMany,
                multiColor = single == null && firstOfMany != null,
                filamentWeight = f?.num("weight"),
                initialWeight = o.num("initial_weight"),
                remainingWeight = o.num("remaining_weight"),
                location = o.str("location"),
                tagCount = extra[TAGS_FIELD]?.trim('"')?.toIntOrNull()
                    ?: if (extra[LEGACY_FIELD] == "true") 1 else 0,
                extra = extra,
            )
        }
    }
}
