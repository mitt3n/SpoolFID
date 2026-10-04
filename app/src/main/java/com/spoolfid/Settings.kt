package com.spoolfid

import android.content.Context
import androidx.core.content.edit

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("spoolfid", Context.MODE_PRIVATE)

    var spoolmanUrl: String
        get() = prefs.getString("url", "") ?: ""
        set(v) = prefs.edit { putString("url", v.trim()) }

    var markWritten: Boolean
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

    var hideWritten: Boolean
        get() = prefs.getBoolean("hideWritten", true)
        set(v) = prefs.edit { putBoolean("hideWritten", v) }
}
