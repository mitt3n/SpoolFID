package com.spoolfid

import android.app.Application
import android.media.AudioManager
import android.media.ToneGenerator
import android.nfc.Tag
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spoolfid.nfc.DecodedPayload
import com.spoolfid.nfc.ExistingDataException
import com.spoolfid.nfc.TagContents
import com.spoolfid.nfc.TagException
import com.spoolfid.nfc.TagIo
import com.spoolfid.nfc.toHex
import com.spoolfid.spoolman.Spool
import com.spoolfid.spoolman.SpoolmanClient
import com.spoolfid.spoolman.SpoolmanException
import com.spoolfid.spoolman.TagLink
import com.spoolfid.spoolman.TagLinkStatus
import com.spoolfid.spoolman.TagMove
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Tab(val label: String) { WRITE("Write"), READ("Read"), SETTINGS("Settings") }

enum class NfcStatus { OK, DISABLED, UNSUPPORTED, NO_MIFARE }

enum class Phase { WAITING, WRITING, SUCCESS, ERROR, CONFIRM, DONE }

data class Session(
    val queue: List<Spool>,
    val index: Int = 0,
    val phase: Phase = Phase.WAITING,
    val message: String? = null,
    val lastUid: String? = null,
    val written: Int = 0,
    /** Tag held back by the overwrite prompt, so "Overwrite" can write it without another tap. */
    val pendingTag: Tag? = null,
    /** UID the user has approved overwriting. */
    val approvedUid: String? = null,
    /** Tags to write per spool (one per flange), and which one we're on (0-based). */
    val copies: Int = 1,
    val copy: Int = 0,
) {
    val current: Spool get() = queue[index]
    val hasNext: Boolean get() = index + 1 < queue.size
    val onLastCopy: Boolean get() = copy + 1 >= copies
    val tagsTotal: Int get() = queue.size * copies
    val tagsDone: Int get() = index * copies + copy

    /** State after the current tag is written: the next tag of this spool, else the next spool, else done. */
    fun advanced(): Session = when {
        !onLastCopy -> copy(copy = copy + 1, phase = Phase.WAITING, message = null, pendingTag = null, approvedUid = null)
        hasNext -> copy(index = index + 1, copy = 0, phase = Phase.WAITING, message = null, pendingTag = null, approvedUid = null)
        else -> copy(phase = Phase.DONE, message = null, pendingTag = null, approvedUid = null)
    }

    /** State after abandoning the current spool (all its remaining tags). */
    fun skipped(): Session =
        if (hasNext) {
            copy(index = index + 1, copy = 0, phase = Phase.WAITING, message = null, pendingTag = null, approvedUid = null)
        } else {
            copy(phase = Phase.DONE, message = null, pendingTag = null, approvedUid = null)
        }
}

sealed interface ReadState {
    data object Idle : ReadState
    data object Reading : ReadState
    data class Error(val message: String) : ReadState
    data class Result(
        val uid: String,
        val contents: TagContents,
        /** How the tag relates to Spoolman's tag links; null if Spoolman couldn't be asked. */
        val link: TagLinkStatus?,
        /** The server can link tags (Spoolman 0.27+). */
        val canLink: Boolean,
        val note: String?,
        /** A link was just made from this screen. */
        val justLinked: String? = null,
    ) : ReadState
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = Settings(app)
    private val catalog = MaterialCatalog()
    private var tone: ToneGenerator? = null
    private var busy = false
    private var cachedClient: SpoolmanClient? = null
    private var cachedClientUrl: String? = null

    var nfcStatus by mutableStateOf(NfcStatus.OK)
    var tab by mutableStateOf(Tab.WRITE)

    var spools by mutableStateOf<List<Spool>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var loadError by mutableStateOf<String?>(null); private set
    var query by mutableStateOf("")
    var tagFilter by mutableStateOf(settings.tagFilter); private set
    var selection by mutableStateOf<List<Int>>(emptyList()); private set

    var session by mutableStateOf<Session?>(null); private set
    var read by mutableStateOf<ReadState>(ReadState.Idle); private set

    var url by mutableStateOf(settings.spoolmanUrl)
    var linkTags by mutableStateOf(settings.linkTags); private set
    var confirmOverwrite by mutableStateOf(settings.confirmOverwrite); private set
    var twoTagsPerSpool by mutableStateOf(settings.twoTagsPerSpool); private set
    var keepScreenOn by mutableStateOf(settings.keepScreenOn); private set
    var doneCloseSeconds by mutableStateOf(settings.doneCloseSeconds); private set
    var settingsStatus by mutableStateOf<String?>(null); private set

    val configured: Boolean get() = settings.spoolmanUrl.isNotBlank()

    /** Tags a spool needs to count as fully tagged. */
    val requiredTags: Int get() = if (twoTagsPerSpool) 2 else 1

    init {
        if (configured) refresh()
    }

    // ---- Spoolman ----

    private fun client(): SpoolmanClient {
        val u = settings.spoolmanUrl
        if (cachedClient == null || cachedClientUrl != u) {
            cachedClient = SpoolmanClient(u)
            cachedClientUrl = u
        }
        return cachedClient!!
    }

    private fun describe(e: Throwable): String =
        if (e is SpoolmanException) e.message ?: "Spoolman error" else "Unexpected response - is this a Spoolman server?"

    fun refresh() {
        if (!configured) return
        viewModelScope.launch {
            loading = true
            loadError = null
            runCatching { withContext(Dispatchers.IO) { client().listSpools() } }
                .onSuccess { list ->
                    spools = list.sortedBy { it.id }
                    selection = selection.filter { id -> list.any { it.id == id } }
                }
                .onFailure { loadError = describe(it) }
            loading = false
        }
    }

    /** Re-sync with Spoolman when the app comes back to the foreground (not mid-session). */
    fun onResumed() {
        if (configured && session == null && !loading) refresh()
    }

    /** Whether the connected Spoolman can link tags (Spoolman 0.27+). Null until we've seen a spool. */
    private fun tagLinkingSupported(): Boolean? = spools.firstOrNull()?.tagsSupported

    fun saveSettings() {
        settings.spoolmanUrl = url
        url = settings.spoolmanUrl
        settingsStatus = "Connecting..."
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val c = client()
                    c.info() to c.listSpools()
                }
            }
                .onSuccess { (info, list) ->
                    spools = list.sortedBy { s -> s.id }
                    loadError = null
                    val linking = when (list.firstOrNull()?.tagsSupported) {
                        true -> "tag linking available"
                        false -> "older Spoolman: tags can't be linked"
                        null -> null
                    }
                    settingsStatus = listOfNotNull(
                        "Connected" + (info.version?.let { " to Spoolman $it" } ?: ""),
                        "${list.size} spools",
                        linking,
                    ).joinToString(" - ")
                }
                .onFailure { settingsStatus = describe(it) }
        }
    }

    fun updateLinkTags(v: Boolean) {
        linkTags = v
        settings.linkTags = v
    }

    fun updateKeepScreenOn(v: Boolean) {
        keepScreenOn = v
        settings.keepScreenOn = v
    }

    fun updateDoneCloseSeconds(v: Int) {
        doneCloseSeconds = v
        settings.doneCloseSeconds = v
    }

    fun updateTwoTagsPerSpool(v: Boolean) {
        twoTagsPerSpool = v
        settings.twoTagsPerSpool = v
    }

    fun updateConfirmOverwrite(v: Boolean) {
        confirmOverwrite = v
        settings.confirmOverwrite = v
    }

    fun updateTagFilter(f: TagFilter) {
        tagFilter = f
        settings.tagFilter = f
    }

    /** How many spools each tag filter would show, for the chip labels. */
    fun countFor(f: TagFilter): Int = spools.count { f.matches(it.tagCount, requiredTags, it.unlinkedCount) }

    // ---- Spool list / selection ----

    fun visibleSpools(): List<Spool> {
        val q = query.trim().lowercase()
        return spools.filter { s ->
            (tagFilter.matches(s.tagCount, requiredTags, s.unlinkedCount) || s.id in selection) &&
                (q.isEmpty() || listOfNotNull("#${s.id}", s.title, s.vendor, s.material, s.location)
                    .any { it.lowercase().contains(q) })
        }
    }

    fun toggleSelect(id: Int) {
        selection = if (id in selection) selection - id else selection + id
    }

    fun selectAllVisible() {
        selection = visibleSpools().map { it.id }
    }

    fun clearSelection() {
        selection = emptyList()
    }

    // ---- Write session ----

    fun startSession(list: List<Spool>) {
        if (list.isEmpty()) return
        session = Session(queue = list, copies = if (settings.twoTagsPerSpool) 2 else 1)
        selection = emptyList()
    }

    fun startSelected() = startSession(spools.filter { it.id in selection })

    fun stopSession() {
        session = null
    }

    fun skip() {
        val s = session ?: return
        if (s.phase != Phase.WAITING && s.phase != Phase.ERROR && s.phase != Phase.CONFIRM) return
        session = s.skipped()
    }

    /** The user approved overwriting the tag that triggered the prompt; write it now if it's still in range. */
    fun confirmOverwriteNow() {
        val s = session ?: return
        val tag = s.pendingTag
        if (s.phase != Phase.CONFIRM || tag == null) return
        val approved = s.copy(approvedUid = tag.id.toHex(), pendingTag = null, phase = Phase.WAITING, message = null)
        session = approved
        writeTag(tag, approved)
    }

    /** Back to waiting for a different tag. */
    fun declineOverwrite() {
        val s = session ?: return
        if (s.phase == Phase.CONFIRM) session = s.copy(phase = Phase.WAITING, message = null, pendingTag = null)
    }

    private fun describeExisting(p: DecodedPayload?): String {
        val id = p?.spoolId
        return when {
            id != null && id >= 1 -> {
                val name = spools.firstOrNull { it.id == id }?.title
                "This tag already holds spool #$id" + (name?.let { " ($it)" } ?: "")
            }
            p != null -> "This tag already has CFS data (material ${p.materialId})"
            else -> "This tag already has data"
        }
    }

    fun mapped(spool: Spool): Mapped = catalog.map(spool)

    // ---- Tag events ----

    fun onTag(tag: Tag) {
        if (busy) return
        val s = session
        when {
            s != null -> if (s.phase == Phase.WAITING || s.phase == Phase.ERROR || s.phase == Phase.CONFIRM) writeTag(tag, s)
            tab == Tab.READ -> readTag(tag)
        }
    }

    private fun writeTag(tag: Tag, s: Session) {
        busy = true
        viewModelScope.launch {
            try {
                val spool = s.current
                val uid = tag.id.toHex()
                if (uid == s.lastUid) {
                    session = s.copy(phase = Phase.ERROR, message = "That tag was just written - use a fresh tag")
                    feedback(false)
                    return@launch
                }
                session = s.copy(phase = Phase.WRITING, message = null, pendingTag = null)

                val payload = catalog.map(spool).payload
                val allowOverwrite = !settings.confirmOverwrite || s.approvedUid == uid
                val result = runCatching {
                    withContext(Dispatchers.IO) { TagIo.write(tag, payload, allowOverwrite) }
                }
                val error = result.exceptionOrNull()
                if (error is ExistingDataException) {
                    session = s.copy(phase = Phase.CONFIRM, message = describeExisting(error.existing), pendingTag = tag)
                    feedback(false)
                    return@launch
                }
                if (error != null) {
                    session = s.copy(
                        phase = Phase.ERROR,
                        message = (error as? TagException)?.message ?: "Unexpected error: ${error.message}",
                    )
                    feedback(false)
                    return@launch
                }

                feedback(true)
                val notes = mutableListOf<String>()
                val prev = result.getOrNull()?.previousSpoolId
                if (prev != null && prev > 1 && prev != spool.id) notes += "Replaced spool #$prev"

                // Spoolman is the source of truth: link the tag there and show the spool as Spoolman now has it.
                if (settings.linkTags) {
                    val count = s.copy + 1
                    runCatching {
                        withContext(Dispatchers.IO) { client().recordTag(spool, uid, count) }
                    }
                        .onSuccess { record ->
                            val fresh = record.spool
                            if (fresh != null) {
                                spools = spools.map { if (it.id == fresh.id) fresh else it }
                            } else {
                                // Older server: the count went into a custom field, so mirror it locally.
                                spools = spools.map {
                                    if (it.id == spool.id) {
                                        it.copy(
                                            legacyTagCount = count,
                                            extra = it.extra + (SpoolmanClient.TAGS_FIELD to count.toString()),
                                        )
                                    } else {
                                        it
                                    }
                                }
                            }
                            when (val moved = record.moved) {
                                is TagMove.FromSpool -> notes += "Tag was linked to spool #${moved.spoolId} in Spoolman - moved here"
                                is TagMove.FromFilament -> notes += "Tag was linked to filament #${moved.filamentId} in Spoolman - moved here"
                                TagMove.None -> Unit
                            }
                        }
                        .onFailure { notes += "Couldn't link the tag in Spoolman: ${describe(it)}" }
                }

                session = s.copy(
                    phase = Phase.SUCCESS,
                    message = notes.joinToString("\n").ifEmpty { null },
                    lastUid = uid,
                    written = s.written + 1,
                    approvedUid = null,
                )
                delay(if (notes.isEmpty()) 900 else 2500)
                val cur = session ?: return@launch
                session = cur.advanced()
            } finally {
                busy = false
            }
        }
    }

    private fun readTag(tag: Tag) {
        busy = true
        viewModelScope.launch {
            try {
                read = ReadState.Reading
                val result = runCatching { withContext(Dispatchers.IO) { TagIo.read(tag) } }
                val r = result.getOrNull()
                if (r == null) {
                    val e = result.exceptionOrNull()
                    read = ReadState.Error((e as? TagException)?.message ?: "Unexpected error: ${e?.message}")
                    feedback(false)
                    return@launch
                }
                feedback(true)
                val tagSpoolId = (r.contents as? TagContents.Written)?.payload?.spoolId?.takeIf { it >= 1 }
                read = lookUp(r.uid, r.contents, tagSpoolId)
            } finally {
                busy = false
            }
        }
    }

    /** Asks Spoolman what it has linked for this tag, and for the spool the tag names. */
    private suspend fun lookUp(
        uid: String,
        contents: TagContents,
        tagSpoolId: Int?,
        justLinked: String? = null,
    ): ReadState.Result {
        if (!configured || contents == TagContents.Blank) {
            return ReadState.Result(uid, contents, null, false, null, justLinked)
        }
        return runCatching {
            withContext(Dispatchers.IO) {
                val c = client()
                val linked = c.spoolsWithTag(uid).firstOrNull()
                val named = when {
                    tagSpoolId == null -> null
                    linked?.id == tagSpoolId -> linked
                    else -> c.getSpool(tagSpoolId)
                }
                Triple(linked, named, TagLink.classify(tagSpoolId, linked, named))
            }
        }.fold(
            onSuccess = { (linked, named, status) ->
                val supported = (linked ?: named)?.tagsSupported ?: tagLinkingSupported() ?: false
                ReadState.Result(uid, contents, status, supported, null, justLinked)
            },
            onFailure = { ReadState.Result(uid, contents, null, false, describe(it), justLinked) },
        )
    }

    /** Links the tag on screen to the spool it names, moving it if Spoolman has it on another spool. */
    fun linkReadTag() {
        val r = read as? ReadState.Result ?: return
        val spoolId = when (val l = r.link) {
            is TagLinkStatus.NotLinked -> l.tagSpoolId
            is TagLinkStatus.Mismatch -> l.tagSpoolId
            else -> null
        } ?: return
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                val outcome = runCatching { withContext(Dispatchers.IO) { client().linkTag(spoolId, r.uid) } }
                val error = outcome.exceptionOrNull()
                if (error != null) {
                    read = r.copy(note = "Couldn't link the tag: ${describe(error)}")
                    feedback(false)
                    return@launch
                }
                feedback(true)
                val moved = when (val m = outcome.getOrThrow()) {
                    is TagMove.FromSpool -> " (moved from spool #${m.spoolId})"
                    is TagMove.FromFilament -> " (moved from filament #${m.filamentId})"
                    TagMove.None -> ""
                }
                read = lookUp(r.uid, r.contents, spoolId, justLinked = "Linked to spool #$spoolId$moved")
                // Keep the spool list in step with Spoolman.
                runCatching { withContext(Dispatchers.IO) { client().getSpool(spoolId) } }.getOrNull()?.let { fresh ->
                    spools = spools.map { if (it.id == fresh.id) fresh else it }
                }
            } finally {
                busy = false
            }
        }
    }

    private fun feedback(ok: Boolean) {
        val v = getApplication<Application>().getSystemService(Vibrator::class.java)
        val t = tone ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).also { tone = it }
        if (ok) {
            v?.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
            t.startTone(ToneGenerator.TONE_PROP_ACK, 120)
        } else {
            v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 80, 60, 80), -1))
            t.startTone(ToneGenerator.TONE_PROP_NACK, 200)
        }
    }

    override fun onCleared() {
        tone?.release()
    }
}
