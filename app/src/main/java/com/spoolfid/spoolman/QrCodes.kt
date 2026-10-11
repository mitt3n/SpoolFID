package com.spoolfid.spoolman

/** What a scanned QR code points at in Spoolman. */
sealed interface ScannedCode {
    data class OfSpool(val id: Int) : ScannedCode
    data class OfFilament(val id: Int) : ScannedCode
}

/**
 * The QR codes Spoolman puts on its labels: `WEB+SPOOLMAN:S-<id>` for a spool (the form its web page prints by default,
 * upper case so the code stays small), or a link to the spool's page in Spoolman. SpoolFID prints and reads the same
 * ones, so labels work with both.
 */
object QrCodes {
    private val SCHEME = Regex("""^web\+spoolman:([sf])-(\d+)$""", RegexOption.IGNORE_CASE)
    private val LINK = Regex("""/(spool|filament)/show/(\d+)(?:[/?#].*)?$""", RegexOption.IGNORE_CASE)

    fun parse(text: String): ScannedCode? {
        val t = text.trim()
        SCHEME.find(t)?.let { m ->
            val id = m.groupValues[2].toIntOrNull() ?: return null
            return if (m.groupValues[1].equals("s", ignoreCase = true)) ScannedCode.OfSpool(id) else ScannedCode.OfFilament(id)
        }
        LINK.find(t)?.let { m ->
            val id = m.groupValues[2].toIntOrNull() ?: return null
            return if (m.groupValues[1].equals("spool", ignoreCase = true)) ScannedCode.OfSpool(id) else ScannedCode.OfFilament(id)
        }
        return null
    }

    fun spoolCode(id: Int) = "WEB+SPOOLMAN:S-$id"

    /** A link to the spool's page, for labels that should open in a phone's browser. [baseUrl] has no trailing slash. */
    fun spoolLink(baseUrl: String, id: Int) = "${baseUrl.trimEnd('/')}/spool/show/$id"
}
