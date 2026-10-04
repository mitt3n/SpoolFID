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

    /** One tag per flange; the CFS only reads the tag on the side facing its reader. */
    var twoTagsPerSpool: Boolean
        get() = prefs.getBoolean("twoTagsPerSpool", true)
        set(v) = prefs.edit { putBoolean("twoTagsPerSpool", v) }

    /** Keep the display on during write sessions and on the Read tab, so batch writing doesn't stall. */
    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keepScreenOn", true)
        set(v) = prefs.edit { putBoolean("keepScreenOn", v) }

    /** Seconds before the "Done" screen closes itself after a write session; 0 = stay until dismissed. */
    var doneCloseSeconds: Int
        get() = prefs.getInt("doneCloseSeconds", 5)
        set(v) = prefs.edit { putInt("doneCloseSeconds", v) }

    var confirmOverwrite: Boolean
        get() = prefs.getBoolean("confirmOverwrite", true)
        set(v) = prefs.edit { putBoolean("confirmOverwrite", v) }

    /** Which spools the Write list shows. Falls back to the old "hide fully tagged" switch if it was set. */
    var tagFilter: TagFilter
        get() = prefs.getString("tagFilter", null)?.let { name -> TagFilter.entries.firstOrNull { it.name == name } }
            ?: if (prefs.getBoolean("hideWritten", true)) TagFilter.TO_DO else TagFilter.ALL
        set(v) = prefs.edit { putString("tagFilter", v.name) }
}
