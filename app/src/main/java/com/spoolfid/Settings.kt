package com.spoolfid

import android.content.Context
import androidx.core.content.edit

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("spoolfid", Context.MODE_PRIVATE)

    var spoolmanUrl: String
        get() = prefs.getString("url", "") ?: ""
        set(v) = prefs.edit { putString("url", v.trim()) }

    /** Link written tags to their spool in Spoolman. (Stored under the key an earlier version used.) */
    var linkTags: Boolean
        get() = prefs.getBoolean("markWritten", true)
        set(v) = prefs.edit { putBoolean("markWritten", v) }

    /**
     * Tags written per spool, one for each flange the CFS might read. Starts at 2, and carries over the old
     * two-or-one switch if that was set.
     */
    var tagsPerSpool: Int
        get() = when {
            prefs.contains("tagsPerSpool") -> prefs.getInt("tagsPerSpool", DEFAULT_TAGS)
            prefs.contains("twoTagsPerSpool") -> if (prefs.getBoolean("twoTagsPerSpool", true)) 2 else 1
            else -> DEFAULT_TAGS
        }.coerceIn(MIN_TAGS, MAX_TAGS)
        set(v) = prefs.edit { putInt("tagsPerSpool", v.coerceIn(MIN_TAGS, MAX_TAGS)) }

    /** Keep the display on during write sessions and on the Read tab, so batch writing doesn't stall. */
    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keepScreenOn", true)
        set(v) = prefs.edit { putBoolean("keepScreenOn", v) }

    /** Seconds before the "Done" screen closes itself after a write session; 0 = stay until dismissed. */
    var doneCloseSeconds: Int
        get() = prefs.getInt("doneCloseSeconds", DEFAULT_DONE_SECONDS).coerceIn(0, MAX_DONE_SECONDS)
        set(v) = prefs.edit { putInt("doneCloseSeconds", v.coerceIn(0, MAX_DONE_SECONDS)) }

    /** On the Read tab, link a tag that names a spool as soon as it's scanned, without asking. */
    var autoLink: Boolean
        get() = prefs.getBoolean("autoLink", false)
        set(v) = prefs.edit { putBoolean("autoLink", v) }

    /** Tell Spoolman about tags scanned on the Read tab, so a paired browser window follows along. */
    var reportScans: Boolean
        get() = prefs.getBoolean("reportScans", false)
        set(v) = prefs.edit { putBoolean("reportScans", v) }

    /** Names this phone to Spoolman as a tag reader. Random, made once, and tells Spoolman nothing about you. */
    val readerId: String
        get() = prefs.getString("readerId", null) ?: "spoolfid-${java.util.UUID.randomUUID().toString().take(8)}"
            .also { id -> prefs.edit { putString("readerId", id) } }

    /** The user ticked "don't show this again" on the notice shown when switching on scan reporting. */
    var hideReportNotice: Boolean
        get() = prefs.getBoolean("hideReportNotice", false)
        set(v) = prefs.edit { putBoolean("hideReportNotice", v) }

    var confirmOverwrite: Boolean
        get() = prefs.getBoolean("confirmOverwrite", true)
        set(v) = prefs.edit { putBoolean("confirmOverwrite", v) }

    /** Which spools the Write list shows. Falls back to the old "hide fully tagged" switch if it was set. */
    var tagFilter: TagFilter
        get() = prefs.getString("tagFilter", null)?.let { name -> TagFilter.entries.firstOrNull { it.name == name } }
            ?: if (prefs.getBoolean("hideWritten", true)) TagFilter.TO_DO else TagFilter.ALL
        set(v) = prefs.edit { putString("tagFilter", v.name) }
}

const val MIN_TAGS = 1
const val MAX_TAGS = 6
const val DEFAULT_TAGS = 2
const val MAX_DONE_SECONDS = 60
const val DEFAULT_DONE_SECONDS = 3
