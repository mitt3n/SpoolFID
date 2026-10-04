package com.spoolfid.spoolman

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException

/** An NFC/RFID tag linked to a spool in Spoolman (Spoolman 0.27+). */
data class SpoolTag(val uid: String, val format: String?)

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
    /** Raw extra fields: values are JSON-encoded strings, as Spoolman stores them. */
    val extra: Map<String, String>,
    /** The filament has several colors, so [colorHex] is just the first of them. */
    val multiColor: Boolean = false,
    /** Tags Spoolman has linked to this spool. */
    val tags: List<SpoolTag> = emptyList(),
    /** The server reports native tags. Older Spoolman versions don't have the field at all. */
    val tagsSupported: Boolean = false,
    /** Count kept in a custom extra field by older SpoolFID versions; only used when the server has no native tags. */
    val legacyTagCount: Int = 0,
) {
    val title: String get() = filamentName?.takeIf { it.isNotBlank() } ?: "Spool $id"

    /** Spoolman is the source of truth: the number of tags it has linked (or the legacy count on old servers). */
    val tagCount: Int get() = if (tagsSupported) tags.size else legacyTagCount

    fun hasTag(uid: String): Boolean = tags.any { it.uid.equals(uid, ignoreCase = true) }
}

class SpoolmanException(message: String) : Exception(message)

class ServerInfo(val version: String?)

/** Where a tag was linked before it was moved to a different spool. */
sealed interface TagMove {
    data object None : TagMove
    data class FromSpool(val spoolId: Int) : TagMove
    data class FromFilament(val filamentId: Int) : TagMove
}

/** The outcome of recording a freshly written tag: the spool as Spoolman now has it, and any link that moved. */
class TagRecord(val spool: Spool?, val moved: TagMove)

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

    /** The server's version, for the connection check. */
    fun info(): ServerInfo = ServerInfo(JSONObject(request("GET", "/api/v1/info")).str("version"))

    fun listSpools(): List<Spool> = parseSpools(request("GET", "/api/v1/spool?allow_archived=false"))

    fun getSpool(id: Int): Spool? = try {
        parseSpool(JSONObject(request("GET", "/api/v1/spool/$id")))
    } catch (e: SpoolmanException) {
        if (e.message?.contains("HTTP 404") == true) null else throw e
    }

    /**
     * The spools that have [uid] linked (archived ones included). Servers without tag support ignore the filter
     * and return everything, so the result is always checked against each spool's own tag list.
     */
    fun spoolsWithTag(uid: String): List<Spool> =
        parseSpools(request("GET", "/api/v1/spool?allow_archived=true&tag=${URLEncoder.encode(uid, "UTF-8")}"))
            .filter { it.hasTag(uid) }

    /**
     * Links [uid] to the spool. A tag identifies exactly one spool or filament, so if something else already holds it
     * the link is moved here (and the old holder is reported).
     */
    fun linkTag(spoolId: Int, uid: String, format: String = TAG_FORMAT): TagMove {
        val body = JSONObject().put("uid", uid).put("format", format).toString()
        val path = "/api/v1/spool/$spoolId/tag"
        val first = send("POST", path, body)
        if (first.code in 200..299) return TagMove.None
        if (first.code != 409) throw failure(first)

        // Already linked somewhere: re-linking to the same spool is a no-op, otherwise move it.
        val conflict = runCatching { JSONObject(first.body) }.getOrNull() ?: throw failure(first)
        val heldBySpool = conflict.int("spool_id")
        val heldByFilament = conflict.int("filament_id")
        if (heldBySpool == spoolId) return TagMove.None
        val move = when {
            heldBySpool != null -> {
                requireOk(send("DELETE", "/api/v1/spool/$heldBySpool/tag/$uid", null))
                TagMove.FromSpool(heldBySpool)
            }
            heldByFilament != null -> {
                requireOk(send("DELETE", "/api/v1/filament/$heldByFilament/tag/$uid", null))
                TagMove.FromFilament(heldByFilament)
            }
            else -> throw failure(first)
        }
        requireOk(send("POST", path, body))
        return move
    }

    fun unlinkTag(spoolId: Int, uid: String) {
        requireOk(send("DELETE", "/api/v1/spool/$spoolId/tag/$uid", null))
    }

    /**
     * Records a tag that was just written. On Spoolman with native tags the tag is linked and the spool is re-read,
     * so what's shown is exactly what Spoolman holds. On older servers the count goes in a custom field instead.
     */
    fun recordTag(spool: Spool, uid: String, sessionCount: Int): TagRecord =
        if (spool.tagsSupported) {
            val moved = linkTag(spool.id, uid)
            TagRecord(getSpool(spool.id), moved)
        } else {
            setLegacyTagCount(spool, sessionCount)
            TagRecord(null, TagMove.None)
        }

    /** Fallback for servers without native tags: the count lives in an integer extra field. */
    fun setLegacyTagCount(spool: Spool, count: Int) {
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

    /** Sends a request and returns whatever the server answered, whatever the status. */
    private fun send(method: String, path: String, body: String?): HttpResponse = try {
        transport.send(method, base + path, body)
    } catch (e: MalformedURLException) {
        throw SpoolmanException("Invalid Spoolman URL")
    } catch (e: UnknownHostException) {
        throw SpoolmanException("Unknown host - check the Spoolman address")
    } catch (e: IOException) {
        throw SpoolmanException("Can't reach Spoolman at $base\n(${e.javaClass.simpleName}: ${e.message})")
    }

    private fun request(method: String, path: String, body: String? = null): String =
        requireOk(send(method, path, body)).body

    private fun requireOk(response: HttpResponse): HttpResponse {
        if (response.code !in 200..299) throw failure(response)
        return response
    }

    /** An error carrying the status and, when Spoolman explains itself, its message. */
    private fun failure(response: HttpResponse): SpoolmanException {
        val detail = runCatching {
            val o = JSONObject(response.body)
            o.str("message") ?: o.str("detail")
        }.getOrNull()
        return SpoolmanException("Spoolman returned HTTP ${response.code}" + (detail?.let { ": $it" } ?: ""))
    }

    companion object {
        /** The tag format recorded in Spoolman; "creality" is one of the formats it lists. */
        const val TAG_FORMAT = "creality"

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
        private fun JSONObject.int(key: String): Int? = if (isNull(key)) null else getInt(key)

        private fun parseSpools(json: String): List<Spool> {
            val arr = JSONArray(json)
            return (0 until arr.length()).map { parseSpool(arr.getJSONObject(it)) }
        }

        private fun parseSpool(o: JSONObject): Spool {
            val f = o.optJSONObject("filament")
            val extraObj = o.optJSONObject("extra")
            val extra = buildMap {
                extraObj?.keys()?.forEach { k -> put(k, extraObj.getString(k)) }
            }
            // Multi-color filaments have no color_hex, only a comma-separated multi_color_hexes.
            val single = f?.str("color_hex")?.takeIf { it.isNotBlank() }
            val firstOfMany = f?.str("multi_color_hexes")?.split(",")?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
            val tagArray = o.optJSONArray("tags")
            val tags = (0 until (tagArray?.length() ?: 0)).mapNotNull { i ->
                tagArray!!.optJSONObject(i)?.let { t -> t.str("uid")?.let { uid -> SpoolTag(uid, t.str("format")) } }
            }
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
                extra = extra,
                tags = tags,
                tagsSupported = o.has("tags"),
                legacyTagCount = extra[TAGS_FIELD]?.trim('"')?.toIntOrNull()
                    ?: if (extra[LEGACY_FIELD] == "true") 1 else 0,
            )
        }
    }
}
