package com.spoolfid

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.spoolfid.spoolman.CatalogFilament
import com.spoolfid.spoolman.Filament
import com.spoolfid.spoolman.FilamentForm
import com.spoolfid.spoolman.Spool
import com.spoolfid.spoolman.SpoolForm
import com.spoolfid.spoolman.SpoolmanClient
import com.spoolfid.spoolman.SpoolmanException
import com.spoolfid.spoolman.TagMove
import com.spoolfid.spoolman.Vendor
import com.spoolfid.spoolman.VendorForm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Section(val label: String) {
    SPOOLS("Spools"),
    FILAMENTS("Filaments"),
    MANUFACTURERS("Manufacturers"),
}

/** The form that is open, with what it starts out holding. `id` is null while creating. */
sealed interface Editor {
    data class OfSpool(val id: Int?, val form: SpoolForm) : Editor
    data class OfFilament(val id: Int?, val form: FilamentForm) : Editor
    data class OfVendor(val id: Int?, val form: VendorForm) : Editor

    /** Browsing the public filament database; [manufacturer] is null on the list of manufacturers. */
    data class OfCatalog(val manufacturer: String?) : Editor
}

private class Loaded(
    val archived: List<Spool>,
    val vendors: List<Vendor>,
    val filaments: List<Filament>,
    val materials: List<String>,
    val locations: List<String>,
)

/** The spool or filament waiting for a tag to be held to the phone, to link it. */
data class ScanTarget(val filament: Boolean, val id: Int)

/**
 * The Manage tab: browsing and editing manufacturers, filaments and spools in Spoolman, and the tags linked to them.
 * Everything is read from and written to Spoolman; nothing is kept on the phone.
 */
class Library(
    private val scope: CoroutineScope,
    private val client: () -> SpoolmanClient,
    private val describe: (Throwable) -> String,
    /** The spools the Write list already holds (not archived). */
    private val activeSpools: () -> List<Spool>,
    /** Reloads those, so the Write list follows what changed here. */
    private val refreshActive: () -> Unit,
    private val feedback: (Boolean) -> Unit,
    /** Tells Spoolman a tag was scanned (when that's switched on). */
    private val reportScan: (uid: String, format: String?) -> Unit = { _, _ -> },
) {
    var section by mutableStateOf(Section.SPOOLS)
    var query by mutableStateOf("")
    var showArchived by mutableStateOf(false)

    var vendors by mutableStateOf<List<Vendor>>(emptyList()); private set
    var filaments by mutableStateOf<List<Filament>>(emptyList()); private set
    private var archived by mutableStateOf<List<Spool>>(emptyList())
    var materials by mutableStateOf<List<String>>(emptyList()); private set
    var locations by mutableStateOf<List<String>>(emptyList()); private set

    var loading by mutableStateOf(false); private set
    var loadError by mutableStateOf<String?>(null); private set
    /** A one-line result of the last action, shown above the list. */
    var notice by mutableStateOf<String?>(null)

    var editor by mutableStateOf<Editor?>(null); private set
    var saving by mutableStateOf(false); private set
    var formError by mutableStateOf<String?>(null)

    var scanTarget by mutableStateOf<ScanTarget?>(null); private set
    var scanMessage by mutableStateOf<String?>(null); private set

    var catalogResults by mutableStateOf<List<CatalogFilament>>(emptyList()); private set
    var catalogLoading by mutableStateOf(false); private set
    var catalogError by mutableStateOf<String?>(null); private set

    /** The whole community filament database, loaded once the first time it's opened. */
    var catalog by mutableStateOf<List<CatalogFilament>?>(null); private set

    /** Database filaments matching what's being typed in a filament search, that aren't in Spoolman yet. */
    var dbHits by mutableStateOf<List<CatalogFilament>>(emptyList()); private set
    var dbSearching by mutableStateOf(false); private set
    var dbError by mutableStateOf<String?>(null)
    private var dbSearch: Job? = null

    /** What the last import did, shown on the database screen. */
    var importMessage by mutableStateOf<String?>(null); private set

    private var loaded = false

    /** Every spool, archived ones included. */
    fun spools(): List<Spool> = (activeSpools() + archived).sortedBy { it.id }

    fun spool(id: Int): Spool? = spools().firstOrNull { it.id == id }

    fun filament(id: Int): Filament? = filaments.firstOrNull { it.id == id }

    fun vendor(id: Int): Vendor? = vendors.firstOrNull { it.id == id }

    /** Loads everything the first time the tab is opened. */
    fun ensureLoaded() {
        if (!loaded && !loading) refresh()
    }

    fun refresh() {
        loading = true
        loadError = null
        refreshActive()
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val c = client()
                    val all = c.listSpools(includeArchived = true).filter { it.archived }
                    val v = c.listVendors()
                    val f = c.listFilaments()
                    // Suggestions are a convenience; their failure shouldn't fail the load.
                    val m = runCatching { c.materials() }.getOrDefault(emptyList())
                    val l = runCatching { c.locations() }.getOrDefault(emptyList())
                    Loaded(all, v, f, m, l)
                }
            }.onSuccess { r ->
                archived = r.archived
                vendors = r.vendors.sortedBy { it.name.lowercase() }
                filaments = r.filaments
                materials = r.materials
                locations = r.locations
                loaded = true
            }.onFailure { loadError = describe(it) }
            loading = false
        }
    }

    // ---- Opening and closing forms ----

    fun newSpool(forFilament: Filament? = null) =
        open(Editor.OfSpool(null, forFilament?.let { SpoolForm.newOf(it) } ?: SpoolForm()))

    fun newFilament(prefill: FilamentForm = FilamentForm()) = open(Editor.OfFilament(null, prefill))

    fun newVendor() = open(Editor.OfVendor(null, VendorForm()))

    fun edit(spool: Spool) = open(Editor.OfSpool(spool.id, SpoolForm.of(spool)))

    fun edit(filament: Filament) = open(Editor.OfFilament(filament.id, FilamentForm.of(filament)))

    fun edit(vendor: Vendor) = open(Editor.OfVendor(vendor.id, VendorForm.of(vendor)))

    /** A new spool that starts as a copy of [spool]: same filament and details, full, with no tags. */
    fun duplicate(spool: Spool) = open(Editor.OfSpool(null, SpoolForm.duplicateOf(spool)))

    /** A new filament that starts as a copy of [filament]. */
    fun duplicate(filament: Filament) = open(Editor.OfFilament(null, FilamentForm.of(filament)))

    private fun open(e: Editor) {
        formError = null
        weighMessage = null
        scanTarget = null
        scanMessage = null
        catalogResults = emptyList()
        catalogError = null
        editor = e
    }

    /**
     * Looks up [text] in the public filament database as it's typed, so a filament that isn't in Spoolman yet can be
     * picked from the same list as the ones that are. Short or empty text clears the matches.
     */
    fun searchDb(text: String) {
        dbSearch?.cancel()
        dbError = null
        if (text.trim().length < 2) {
            dbHits = emptyList()
            dbSearching = false
            return
        }
        dbSearch = scope.launch {
            delay(300)
            dbSearching = true
            runCatching { withContext(Dispatchers.IO) { client().searchCatalog(text, limit = 20) } }
                .onSuccess { found -> dbHits = found.filter { !CatalogBrowse.registered(it, filaments) } }
                // A server without the database just has no matches to offer; that isn't worth an error.
                .onFailure { dbHits = emptyList() }
            dbSearching = false
        }
    }

    /**
     * Adds a database filament to Spoolman, creating its manufacturer if you don't have it, and hands back the new
     * filament so it can be used straight away (picked for a spool, or opened to review).
     */
    fun addFromCatalog(entry: CatalogFilament, onDone: (Filament) -> Unit) {
        if (saving) return
        saving = true
        dbError = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val c = client()
                    val vendor = CatalogBrowse.vendorNamed(entry.manufacturer, vendors)
                        ?: c.createVendor(VendorForm(name = entry.manufacturer))
                    vendor to c.createFilament(FilamentForm.of(entry, vendor.id))
                }
            }.onSuccess { (vendor, filament) ->
                feedback(true)
                if (vendors.none { it.id == vendor.id }) vendors = (vendors + vendor).sortedBy { it.name.lowercase() }
                filaments = filaments + filament
                dbHits = dbHits.filter { it.id != entry.id }
                onDone(filament)
                refresh()
            }.onFailure {
                feedback(false)
                dbError = describe(it)
            }
            saving = false
        }
    }

    /** Opens the database browser, on the list of manufacturers or straight into one. */
    fun openCatalog(manufacturer: String? = null) {
        open(Editor.OfCatalog(manufacturer))
        importMessage = null
        if (catalog == null) loadCatalog()
    }

    fun loadCatalog() {
        catalogLoading = true
        catalogError = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { client().fullCatalog() } }
                .onSuccess { catalog = it }
                .onFailure { catalogError = describe(it) }
            catalogLoading = false
        }
    }

    fun showManufacturer(name: String?) {
        importMessage = null
        editor = Editor.OfCatalog(name)
    }

    /** One step back: from a manufacturer's filaments to the manufacturers, otherwise out of the form. */
    fun back() {
        val e = editor
        if (e is Editor.OfCatalog && e.manufacturer != null) showManufacturer(null) else closeEditor()
    }

    /**
     * Adds [entries] from the database to Spoolman under [manufacturer]. The manufacturer is created first if you don't
     * have one by that name; with no entries, only the manufacturer is added. Stops at the first filament Spoolman
     * refuses and says how far it got.
     */
    fun importFromCatalog(manufacturer: String, entries: List<CatalogFilament>) {
        if (saving) return
        saving = true
        importMessage = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val c = client()
                    var created = false
                    val vendor = CatalogBrowse.vendorNamed(manufacturer, vendors)
                        ?: c.createVendor(VendorForm(name = manufacturer)).also { created = true }
                    var added = 0
                    var problem: String? = null
                    for (entry in entries) {
                        try {
                            c.createFilament(FilamentForm.of(entry, vendor.id))
                            added++
                        } catch (e: SpoolmanException) {
                            problem = "${entry.name}: ${e.message}"
                            break
                        }
                    }
                    Triple(created, added, problem)
                }
            }.onSuccess { (created, added, problem) ->
                feedback(problem == null)
                importMessage = listOfNotNull(
                    if (created) "Added $manufacturer" else null,
                    if (added > 0) "added $added filament${if (added == 1) "" else "s"}" else null,
                    problem?.let { "stopped at $it" },
                ).joinToString(", ").replaceFirstChar { it.uppercase() }.ifEmpty { "Nothing to add" }
                refresh()
            }.onFailure {
                feedback(false)
                importMessage = describe(it)
            }
            saving = false
        }
    }

    fun closeEditor() {
        editor = null
        formError = null
        scanTarget = null
        scanMessage = null
    }

    // ---- Saving and deleting ----

    /** Runs [work] off the main thread for a form action: closes the form and refreshes on success, shows the error otherwise. */
    private fun act(work: (SpoolmanClient) -> String, closeAfter: Boolean = true) {
        if (saving) return
        saving = true
        formError = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { work(client()) } }
                .onSuccess { message ->
                    feedback(true)
                    notice = message
                    if (closeAfter) closeEditor()
                    refresh()
                }
                .onFailure {
                    feedback(false)
                    formError = describe(it)
                }
            saving = false
        }
    }

    fun saveSpool(id: Int?, form: SpoolForm, copies: Int = 1) = act({ c ->
        when {
            id != null -> c.updateSpool(id, form).let { "Saved spool #${it.id}" }
            copies == 1 -> "Created spool #${c.createSpool(form).id}"
            else -> {
                val made = mutableListOf<Int>()
                try {
                    repeat(copies) { made += c.createSpool(form).id }
                } catch (e: SpoolmanException) {
                    // Say how far it got, so nothing is created twice by trying again.
                    val done = if (made.isEmpty()) "None were created" else "Created ${made.joinToString { "#$it" }} before this"
                    throw SpoolmanException("${e.message}. $done.")
                }
                "Created spools " + made.joinToString { "#$it" }
            }
        }
    })

    fun saveFilament(id: Int?, form: FilamentForm) = act({ c ->
        if (id == null) "Created filament #${c.createFilament(form).id}" else c.updateFilament(id, form).let { "Saved filament #${it.id}" }
    })

    fun saveVendor(id: Int?, form: VendorForm) = act({ c ->
        if (id == null) c.createVendor(form).let { "Created ${it.name}" } else c.updateVendor(id, form).let { "Saved ${it.name}" }
    })

    fun deleteSpool(id: Int) = act({ c -> c.deleteSpool(id); "Deleted spool #$id" })

    fun deleteFilament(id: Int) = act({ c -> c.deleteFilament(id); "Deleted filament #$id" })

    fun deleteVendor(id: Int) = act({ c -> c.deleteVendor(id); "Deleted the manufacturer" })

    /** Creates a manufacturer from inside the filament form and hands it back to be selected. */
    fun createVendorInline(name: String, onCreated: (Vendor) -> Unit) {
        if (saving) return
        saving = true
        formError = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { client().createVendor(VendorForm(name = name)) } }
                .onSuccess { v ->
                    feedback(true)
                    vendors = (vendors + v).sortedBy { it.name.lowercase() }
                    onCreated(v)
                }
                .onFailure {
                    feedback(false)
                    formError = describe(it)
                }
            saving = false
        }
    }

    // ---- Community filament database ----

    fun searchCatalog(text: String) {
        if (text.isBlank()) {
            catalogResults = emptyList()
            return
        }
        catalogLoading = true
        catalogError = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { client().searchCatalog(text) } }
                .onSuccess { catalogResults = it }
                .onFailure { catalogError = describe(it) }
            catalogLoading = false
        }
    }

    /**
     * Turns a database entry into a filament form. Its manufacturer is matched to one you already have by name, or
     * created if it's new, so the filament lands under the right brand.
     */
    fun useCatalog(entry: CatalogFilament, onReady: (FilamentForm) -> Unit) {
        if (saving) return
        saving = true
        formError = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    vendors.firstOrNull { it.name.equals(entry.manufacturer, ignoreCase = true) }
                        ?: client().createVendor(VendorForm(name = entry.manufacturer)).also { created ->
                            vendors = (vendors + created).sortedBy { it.name.lowercase() }
                        }
                }
            }.onSuccess { vendor -> onReady(FilamentForm.of(entry, vendor.id)) }
                .onFailure { formError = describe(it) }
            saving = false
        }
    }

    // ---- Weighing in ----

    var weighMessage by mutableStateOf<String?>(null)

    /** Sets a spool's remaining weight from a scale reading of the whole spool; Spoolman subtracts the empty spool. */
    fun weighIn(id: Int, text: String, onDone: (Spool) -> Unit) {
        val grams = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 }
        if (grams == null) {
            weighMessage = "Enter the scale reading in grams"
            return
        }
        if (saving) return
        saving = true
        weighMessage = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { client().measureSpool(id, grams) } }
                .onSuccess { fresh ->
                    feedback(true)
                    refreshActive()
                    if (fresh.archived) archived = archived.map { if (it.id == fresh.id) fresh else it }
                    weighMessage = "Remaining weight is now ${fresh.remainingWeight?.let { com.spoolfid.spoolman.formatNumber(Math.round(it * 10) / 10.0) } ?: "unknown"} g"
                    onDone(fresh)
                }
                .onFailure {
                    feedback(false)
                    weighMessage = describe(it)
                }
            saving = false
        }
    }

    // ---- Finding by tag ----

    /** A tag was held to the phone on the Find tab: open whatever it is linked to. */
    fun findByTag(uid: String) {
        reportScan(uid, null)
        scope.launch {
            spools().firstOrNull { it.hasTag(uid) }?.let { found ->
                feedback(true)
                edit(found)
                return@launch
            }
            filaments.firstOrNull { f -> f.tags.any { it.uid.equals(uid, ignoreCase = true) } }?.let { found ->
                feedback(true)
                edit(found)
                return@launch
            }
            // Not in what's loaded: ask Spoolman, in case it changed since.
            runCatching { withContext(Dispatchers.IO) { client().spoolsWithTag(uid).firstOrNull() } }
                .onSuccess { found ->
                    feedback(found != null)
                    if (found != null) edit(found) else notice = "Tag $uid isn't linked to anything in Spoolman"
                }
                .onFailure {
                    feedback(false)
                    notice = describe(it)
                }
        }
    }

    // ---- Tags ----

    fun startScan(target: ScanTarget) {
        scanMessage = null
        scanTarget = target
    }

    fun cancelScan() {
        scanTarget = null
    }

    /** A tag was held to the phone while a spool or filament is waiting for one: link it. */
    fun onTag(uid: String) {
        val target = scanTarget ?: return
        scanTarget = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val c = client()
                    if (target.filament) c.linkFilamentTag(target.id, uid) else c.linkTag(target.id, uid, format = null)
                }
            }.onSuccess { moved ->
                feedback(true)
                scanMessage = "Linked tag $uid" + when (moved) {
                    is TagMove.FromSpool -> " (moved from spool #${moved.spoolId})"
                    is TagMove.FromFilament -> " (moved from filament #${moved.filamentId})"
                    TagMove.None -> ""
                }
                reload(target)
                reportScan(uid, null)
            }.onFailure {
                feedback(false)
                scanMessage = "Couldn't link the tag: ${describe(it)}"
            }
        }
    }

    fun removeTag(target: ScanTarget, uid: String) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (target.filament) client().unlinkFilamentTag(target.id, uid) else client().unlinkTag(target.id, uid)
                }
            }.onSuccess {
                feedback(true)
                scanMessage = "Removed tag $uid"
                reload(target)
            }.onFailure {
                feedback(false)
                scanMessage = "Couldn't remove the tag: ${describe(it)}"
            }
        }
    }

    /** Re-reads one spool or filament so its tag list is what Spoolman now holds. */
    private suspend fun reload(target: ScanTarget) {
        if (target.filament) {
            runCatching { withContext(Dispatchers.IO) { client().getFilament(target.id) } }.getOrNull()?.let { fresh ->
                filaments = filaments.map { if (it.id == fresh.id) fresh else it }
            }
        } else {
            refreshActive()
            runCatching { withContext(Dispatchers.IO) { client().getSpool(target.id) } }.getOrNull()?.let { fresh ->
                if (fresh.archived) archived = archived.map { if (it.id == fresh.id) fresh else it }
            }
        }
    }
}
