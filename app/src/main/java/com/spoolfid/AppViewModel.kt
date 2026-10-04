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
        val spool: Spool?,
        val lookupNote: String?,
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
    var hideWritten by mutableStateOf(settings.hideWritten); private set
    var selection by mutableStateOf<List<Int>>(emptyList()); private set

    var session by mutableStateOf<Session?>(null); private set
    var read by mutableStateOf<ReadState>(ReadState.Idle); private set

    var url by mutableStateOf(settings.spoolmanUrl)
    var markWritten by mutableStateOf(settings.markWritten); private set
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

    fun saveSettings() {
        settings.spoolmanUrl = url
        url = settings.spoolmanUrl
        settingsStatus = "Connecting..."
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { client().listSpools() } }
                .onSuccess {
                    spools = it.sortedBy { s -> s.id }
                    loadError = null
                    settingsStatus = "Connected - ${it.size} spools"
                }
                .onFailure { settingsStatus = describe(it) }
        }
    }

    fun updateMarkWritten(v: Boolean) {
        markWritten = v
        settings.markWritten = v
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

    fun updateHideWritten(v: Boolean) {
        hideWritten = v
        settings.hideWritten = v
    }

    // ---- Spool list / selection ----

    fun visibleSpools(): List<Spool> {
        val q = query.trim().lowercase()
        return spools.filter { s ->
            (!hideWritten || s.tagCount < requiredTags || s.id in selection) &&
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

                // Tags written for this spool so far in this session; shown in the list and kept in Spoolman.
                val count = s.copy + 1
                spools = spools.map {
                    if (it.id == spool.id) it.copy(tagCount = count, extra = it.extra + (SpoolmanClient.TAGS_FIELD to count.toString())) else it
                }
                if (settings.markWritten) {
                    runCatching { withContext(Dispatchers.IO) { client().setTagCount(spool, count) } }
                        .onFailure { notes += "Couldn't update Spoolman: ${describe(it)}" }
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
                val id = (r.contents as? TagContents.Written)?.payload?.spoolId
                var spool: Spool? = null
                var note: String? = null
                if (id != null && id >= 1 && configured) {
                    runCatching { withContext(Dispatchers.IO) { client().getSpool(id) } }
                        .onSuccess { spool = it; if (it == null) note = "Spool #$id not found in Spoolman" }
                        .onFailure { note = describe(it) }
                }
                read = ReadState.Result(r.uid, r.contents, spool, note)
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
