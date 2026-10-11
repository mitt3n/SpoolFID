package com.spoolfid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spoolfid.AppViewModel
import com.spoolfid.Editor
import com.spoolfid.Library
import com.spoolfid.ScanTarget
import com.spoolfid.spoolman.CatalogFilament
import com.spoolfid.spoolman.Filament
import com.spoolfid.spoolman.FilamentForm
import com.spoolfid.spoolman.FormError
import com.spoolfid.spoolman.SpoolTag
import com.spoolfid.spoolman.SpoolForm
import com.spoolfid.spoolman.Vendor
import com.spoolfid.spoolman.VendorForm
import com.spoolfid.spoolman.defaultDensity
import com.spoolfid.spoolman.formatNumber
import com.spoolfid.spoolman.normalizeHex
import com.spoolfid.spoolman.parseCopies

private val CommonMaterials = listOf("PLA", "PLA+", "PETG", "ABS", "ASA", "TPU", "PC", "PA", "PLA-CF", "PETG-CF", "PA-CF")

@Composable
internal fun EditorScreen(vm: AppViewModel) {
    when (val e = vm.library.editor) {
        is Editor.OfSpool -> SpoolEditor(vm, e)
        is Editor.OfFilament -> FilamentEditor(vm, e)
        is Editor.OfVendor -> VendorEditor(vm.library, e)
        is Editor.OfCatalog -> CatalogScreen(vm, e)
        null -> Unit
    }
}

// ---------------------------------------------------------------- shared pieces

@Composable
private fun EditorFrame(title: String, lib: Library, onSave: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { lib.closeEditor() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
            Spacer(Modifier.height(8.dp))
        }
        lib.formError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { lib.closeEditor() }, Modifier.weight(1f).height(52.dp)) { Text("Cancel") }
            Button(onClick = onSave, enabled = !lib.saving, modifier = Modifier.weight(1f).height(52.dp)) {
                Text(if (lib.saving) "Saving..." else "Save")
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    number: Boolean = false,
    whole: Boolean = false,
    placeholder: String? = null,
    suffix: String? = null,
    multiLine: Boolean = false,
    leading: @Composable (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        suffix = suffix?.let { { Text(it) } },
        leadingIcon = leading,
        singleLine = !multiLine,
        minLines = if (multiLine) 2 else 1,
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                whole -> KeyboardType.Number
                number -> KeyboardType.Decimal
                else -> KeyboardType.Text
            },
        ),
    )
}

@Composable
private fun Suggestions(options: List<String>, onPick: (String) -> Unit) {
    if (options.isEmpty()) return
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.take(20).forEach { SuggestionChip(onClick = { onPick(it) }, label = { Text(it) }) }
    }
}

@Composable
private fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DeleteButton(label: String, title: String, text: String, onDelete: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    TextButton(onClick = { asking = true }) {
        Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(8.dp))
        Text(label, color = MaterialTheme.colorScheme.error)
    }
    if (asking) ConfirmDialog(title, text, "Delete", onDelete) { asking = false }
}

/** The tags Spoolman has linked to a spool or filament, with removing and linking a new one by scanning it. */
@Composable
private fun TagsSection(
    lib: Library,
    target: ScanTarget,
    tags: List<SpoolTag>,
    supported: Boolean,
    unlinked: Int = 0,
    extra: @Composable () -> Unit = {},
) {
    var removing by remember { mutableStateOf<String?>(null) }
    HorizontalDivider()
    Text("Tags", style = MaterialTheme.typography.titleMedium)
    if (!supported) {
        Text("This Spoolman can't link tags (it needs version 0.27 or newer).", style = MaterialTheme.typography.bodySmall)
        return
    }
    if (tags.isEmpty()) {
        Text("No tags linked in Spoolman yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    tags.forEach { t ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.uid, fontFamily = FontFamily.Monospace)
                t.format?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            IconButton(onClick = { removing = t.uid }) { Icon(Icons.Default.Delete, "Remove tag") }
        }
    }
    if (unlinked > 0) {
        Text(
            "$unlinked tag${if (unlinked == 1) "" else "s"} written with SpoolFID 1.0 ${if (unlinked == 1) "isn't" else "aren't"} " +
                "linked yet. Scan ${if (unlinked == 1) "it" else "them"} on the Read tab to link ${if (unlinked == 1) "it" else "them"}.",
            color = WarnAmber,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (lib.scanTarget == target) {
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Hold a tag to the back of the phone", Modifier.weight(1f))
                TextButton(onClick = { lib.cancelScan() }) { Text("Cancel") }
            }
        }
    } else {
        OutlinedButton(onClick = { lib.startScan(target) }, Modifier.fillMaxWidth()) { Text("Link a tag by scanning it") }
    }
    extra()
    lib.scanMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
    removing?.let { uid ->
        ConfirmDialog(
            title = "Remove this tag?",
            text = "$uid will be unlinked in Spoolman. The tag itself keeps what is written on it.",
            confirm = "Remove",
            onConfirm = { lib.removeTag(target, uid) },
            onDismiss = { removing = null },
        )
    }
}

// ---------------------------------------------------------------- spool

@Composable
private fun SpoolEditor(vm: AppViewModel, e: Editor.OfSpool) {
    val lib = vm.library
    var form by remember(e) { mutableStateOf(e.form) }
    var picking by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var copies by remember(e) { mutableStateOf("1") }
    var scale by remember(e) { mutableStateOf("") }
    val filament = form.filamentId?.let { lib.filament(it) }
    val existing = e.id?.let { lib.spool(it) }

    EditorFrame(
        if (e.id == null) "New spool" else "Spool #${e.id}",
        lib,
        onSave = {
            try {
                lib.saveSpool(e.id, form, if (e.id == null) parseCopies(copies) else 1)
            } catch (err: FormError) {
                lib.formError = err.message
            }
        },
    ) {
        OutlinedButton(onClick = { picking = true }, Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            if (filament != null) Swatch(filament.displayColor, 28.dp)
            if (filament != null) Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(filament?.title ?: "Choose a filament", maxLines = 1, overflow = TextOverflow.Ellipsis)
                filament?.subtitle?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (lib.filaments.isEmpty() && !lib.loading) {
            Text("There are no filaments yet. Add one on the Filaments list first.", color = WarnAmber, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Field("Starting weight", form.initialWeight, { form = form.copy(initialWeight = it) }, Modifier.weight(1f), number = true, suffix = "g")
            Field("Spool weight", form.spoolWeight, { form = form.copy(spoolWeight = it) }, Modifier.weight(1f), number = true, suffix = "g")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Field("Remaining", form.remainingWeight, { form = form.copy(remainingWeight = it) }, Modifier.weight(1f), number = true, suffix = "g")
            Field("Price", form.price, { form = form.copy(price = it) }, Modifier.weight(1f), number = true)
        }
        Text(
            "Starting weight is the filament on a full spool. Leave it empty to use the filament's own weight.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (e.id == null) {
            Field("How many spools", copies, { copies = it }, whole = true)
        }
        Field("Location", form.location, { form = form.copy(location = it) })
        Suggestions(lib.locations.filter { it != form.location && it.contains(form.location, ignoreCase = true) }) {
            form = form.copy(location = it)
        }
        Field("Lot number", form.lotNr, { form = form.copy(lotNr = it) })
        Field("Comment", form.comment, { form = form.copy(comment = it) }, multiLine = true)
        if (e.id != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Archived", style = MaterialTheme.typography.titleMedium)
                    Text("Hides the spool from the Write list and from the printer's choices, but keeps its history.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = form.archived, onCheckedChange = { form = form.copy(archived = it) })
            }
        }

        if (existing != null) {
            HorizontalDivider()
            Text("Weigh in", style = MaterialTheme.typography.titleMedium)
            Text(
                "Put the whole spool on the scale and enter the reading. Spoolman subtracts the empty spool's weight.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Field("Scale reading", scale, { scale = it }, Modifier.weight(1f), number = true, suffix = "g")
                Button(onClick = {
                    lib.weighIn(existing.id, scale) { fresh ->
                        val remaining = fresh.remainingWeight?.let { formatNumber(Math.round(it * 10) / 10.0) }.orEmpty()
                        form = form.copy(remainingWeight = remaining, originalRemaining = remaining)
                        scale = ""
                    }
                }, enabled = !lib.saving) { Text("Update") }
            }
            lib.weighMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            TagsSection(
                lib, ScanTarget(filament = false, id = existing.id), existing.tags, existing.tagsSupported, existing.unlinkedCount,
            ) {
                Button(onClick = { vm.startSession(listOf(existing)) }, Modifier.fillMaxWidth()) { Text("Write tags for this spool") }
            }
            HorizontalDivider()
            OutlinedButton(onClick = { vm.printLabels(context, listOf(existing)) }, Modifier.fillMaxWidth()) { Text("Print a QR label for this spool") }
            OutlinedButton(onClick = { lib.duplicate(existing) }, Modifier.fillMaxWidth()) { Text("Duplicate this spool") }
            DeleteButton(
                "Delete spool",
                "Delete spool #${existing.id}?",
                "This removes it from Spoolman for good. To keep its history, archive it instead.",
            ) { lib.deleteSpool(existing.id) }
        }
    }

    if (picking) {
        PickerDialog(
            title = "Choose a filament",
            items = lib.filaments,
            text = { listOfNotNull(it.title, it.vendor?.name, it.material).joinToString(" ") },
            row = { f -> PickerRow(f.displayColor, f.title, f.subtitle) },
            onPick = { f ->
                // A new spool starts from the filament's weights and price, unless some were already typed.
                val start = SpoolForm.newOf(f)
                form = if (e.id == null) {
                    form.copy(
                        filamentId = f.id,
                        initialWeight = form.initialWeight.ifBlank { start.initialWeight },
                        spoolWeight = form.spoolWeight.ifBlank { start.spoolWeight },
                        price = form.price.ifBlank { start.price },
                    )
                } else {
                    form.copy(filamentId = f.id)
                }
                picking = false
            },
            onDismiss = {
                picking = false
                lib.searchDb("")
            },
            onQuery = { lib.searchDb(it) },
            more = {
                databaseMatches(lib) { added ->
                    // Same as picking one you already had: the new filament is used for this spool.
                    val start = SpoolForm.newOf(added)
                    form = if (e.id == null) {
                        form.copy(
                            filamentId = added.id,
                            initialWeight = form.initialWeight.ifBlank { start.initialWeight },
                            spoolWeight = form.spoolWeight.ifBlank { start.spoolWeight },
                            price = form.price.ifBlank { start.price },
                        )
                    } else {
                        form.copy(filamentId = added.id)
                    }
                    picking = false
                    lib.searchDb("")
                }
            },
        )
    }
}

// ---------------------------------------------------------------- filament

@Composable
private fun FilamentEditor(vm: AppViewModel, e: Editor.OfFilament) {
    val lib = vm.library
    var form by remember(e) { mutableStateOf(e.form) }
    var multi by remember(e) { mutableStateOf(e.form.isMultiColor) }
    var pickingVendor by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf(false) }
    val existing = e.id?.let { lib.filament(it) }
    val vendor = form.vendorId?.let { lib.vendor(it) }

    fun setMaterial(new: String) {
        // Keep the density in step with the material until it's been changed by hand.
        val untouched = form.density == formatNumber(defaultDensity(form.material))
        form = form.copy(material = new, density = if (untouched) formatNumber(defaultDensity(new)) else form.density)
    }

    EditorFrame(if (e.id == null) "New filament" else "Filament #${e.id}", lib, onSave = { lib.saveFilament(e.id, form) }) {
        if (e.id == null) {
            OutlinedButton(onClick = { browsing = true }, Modifier.fillMaxWidth()) { Text("Start from the filament database") }
        }
        Field("Name", form.name, { form = form.copy(name = it) }, placeholder = "Galaxy Black")
        OutlinedButton(onClick = { pickingVendor = true }, Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(vendor?.name ?: "Manufacturer: none", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Field("Material", form.material, ::setMaterial, placeholder = "PLA")
        Suggestions((lib.materials + CommonMaterials).distinct().filter { it != form.material && it.contains(form.material, ignoreCase = true) }, ::setMaterial)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Multi-color", style = MaterialTheme.typography.titleMedium)
                Text("Dual-color, tri-color or rainbow filament", style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = multi,
                onCheckedChange = {
                    multi = it
                    if (!it) form = form.copy(multiColorHexes = "")
                },
            )
        }
        if (multi) {
            Field("Colors", form.multiColorHexes, { form = form.copy(multiColorHexes = it) }, placeholder = "FF0000, 00FF00, 0000FF")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("coaxial" to "Layered", "longitudinal" to "Side by side").forEach { (value, label) ->
                    FilterChip(
                        selected = form.multiColorDirection == value,
                        onClick = { form = form.copy(multiColorDirection = value) },
                        label = { Text(label) },
                    )
                }
            }
        } else {
            Field(
                "Color", form.colorHex, { form = form.copy(colorHex = it) }, placeholder = "FF8800",
                leading = { Swatch(normalizeHex(form.colorHex), 24.dp) },
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Field("Net weight", form.weight, { form = form.copy(weight = it) }, Modifier.weight(1f), number = true, suffix = "g")
            Field("Spool weight", form.spoolWeight, { form = form.copy(spoolWeight = it) }, Modifier.weight(1f), number = true, suffix = "g")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Field("Diameter", form.diameter, { form = form.copy(diameter = it) }, Modifier.weight(1f), number = true, suffix = "mm")
            Field("Density", form.density, { form = form.copy(density = it) }, Modifier.weight(1f), number = true, suffix = "g/cm³")
        }
        Suggestions(listOf("1.75", "2.85").filter { it != form.diameter }) { form = form.copy(diameter = it) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Field("Nozzle", form.extruderTemp, { form = form.copy(extruderTemp = it) }, Modifier.weight(1f), whole = true, suffix = "°C")
            Field("Bed", form.bedTemp, { form = form.copy(bedTemp = it) }, Modifier.weight(1f), whole = true, suffix = "°C")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Field("Price", form.price, { form = form.copy(price = it) }, Modifier.weight(1f), number = true)
            Field("Article number", form.articleNumber, { form = form.copy(articleNumber = it) }, Modifier.weight(1f))
        }
        Field("Comment", form.comment, { form = form.copy(comment = it) }, multiLine = true)

        if (existing != null) {
            TagsSection(lib, ScanTarget(filament = true, id = existing.id), existing.tags, existing.tagsSupported)
            HorizontalDivider()
            Button(onClick = { lib.newSpool(existing) }, Modifier.fillMaxWidth()) { Text("Add a spool of this filament") }
            OutlinedButton(onClick = { lib.duplicate(existing) }, Modifier.fillMaxWidth()) { Text("Duplicate this filament") }
            DeleteButton(
                "Delete filament",
                "Delete ${existing.title}?",
                "This removes it from Spoolman for good. If it still has spools, Spoolman may refuse until those are deleted or moved.",
            ) { lib.deleteFilament(existing.id) }
        }
    }

    if (pickingVendor) {
        VendorDialog(
            lib = lib,
            onPick = { v ->
                form = form.copy(vendorId = v?.id)
                pickingVendor = false
            },
            onDismiss = { pickingVendor = false },
        )
    }
    if (browsing) {
        CatalogDialog(
            lib = lib,
            onPick = { entry ->
                lib.useCatalog(entry) { prefilled ->
                    form = prefilled
                    multi = prefilled.isMultiColor
                    browsing = false
                }
            },
            onDismiss = { browsing = false },
        )
    }
}

@Composable
private fun VendorDialog(lib: Library, onPick: (Vendor?) -> Unit, onDismiss: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    if (creating) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("New manufacturer") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("Name", name, { name = it })
                    lib.formError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = { lib.createVendorInline(name) { onPick(it) } }, enabled = name.isNotBlank() && !lib.saving) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Back") } },
        )
        return
    }
    PickerDialog(
        title = "Manufacturer",
        items = listOf<Vendor?>(null) + lib.vendors,
        text = { it?.name ?: "" },
        row = { v -> PickerRow(null, v?.name ?: "No manufacturer", null) },
        onPick = { onPick(it) },
        onDismiss = onDismiss,
        footer = { TextButton(onClick = { creating = true }) { Text("New manufacturer") } },
    )
}

@Composable
private fun CatalogDialog(lib: Library, onPick: (CatalogFilament) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filament database") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Brand, name or material") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { lib.searchCatalog(text) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (lib.catalogLoading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                lib.catalogError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                lib.formError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(lib.catalogResults, key = { it.id }) { c ->
                        Column(Modifier.clickable { onPick(c) }) {
                            PickerRow(c.colorHex ?: c.colorHexes.firstOrNull(), "${c.manufacturer} · ${c.name}", "${c.material} · ${formatNumber(c.weight)} g")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { lib.searchCatalog(text) }) { Text("Search") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

// ---------------------------------------------------------------- manufacturer

@Composable
private fun VendorEditor(lib: Library, e: Editor.OfVendor) {
    var form by remember(e) { mutableStateOf(e.form) }
    val existing = e.id?.let { lib.vendor(it) }
    EditorFrame(if (e.id == null) "New manufacturer" else "Manufacturer", lib, onSave = { lib.saveVendor(e.id, form) }) {
        Field("Name", form.name, { form = form.copy(name = it) })
        Field("Empty spool weight", form.emptySpoolWeight, { form = form.copy(emptySpoolWeight = it) }, number = true, suffix = "g")
        Text(
            "Used for this manufacturer's spools that don't have a spool weight of their own.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Field("Comment", form.comment, { form = form.copy(comment = it) }, multiLine = true)
        if (existing != null) {
            val count = lib.filaments.count { it.vendor?.id == existing.id }
            HorizontalDivider()
            Text("$count filament${if (count == 1) "" else "s"} from this manufacturer", color = MaterialTheme.colorScheme.onSurfaceVariant)
            DeleteButton(
                "Delete manufacturer",
                "Delete ${existing.name}?",
                "This removes it from Spoolman. If filaments still use it, Spoolman may refuse.",
            ) { lib.deleteVendor(existing.id) }
        }
    }
}

// ---------------------------------------------------------------- pickers

@Composable
private fun PickerRow(colorHex: String?, title: String, subtitle: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (colorHex != null) {
            Swatch(colorHex, 28.dp)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A searchable list in a dialog. [text] is what the search matches against; [row] draws one entry. */
@Composable
private fun <T> PickerDialog(
    title: String,
    items: List<T>,
    text: (T) -> String,
    row: @Composable (T) -> Unit,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
    footer: @Composable () -> Unit = {},
    onQuery: (String) -> Unit = {},
    more: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    val shown = items.filter { query.isBlank() || text(it).contains(query.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        onQuery(it)
                    },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown) { item ->
                        Column(Modifier.clickable { onPick(item) }) { row(item) }
                    }
                    more()
                }
            }
        },
        confirmButton = footer,
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The "from the Spoolman database" section under a filament search: tap one to add it and use it. */
internal fun androidx.compose.foundation.lazy.LazyListScope.databaseMatches(lib: Library, onAdded: (Filament) -> Unit) {
    if (lib.dbHits.isEmpty() && !lib.dbSearching && lib.dbError == null) return
    item(key = "db-header") {
        Column {
            HorizontalDivider()
            Text(
                "From the Spoolman database",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                "Not in your Spoolman yet. Tapping one adds it, and its manufacturer if that's new.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (lib.dbSearching) CircularProgressIndicator(Modifier.padding(vertical = 8.dp).size(20.dp), strokeWidth = 2.dp)
            lib.dbError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    items(lib.dbHits, key = { "db-${it.id}" }) { c ->
        Column(Modifier.clickable(enabled = !lib.saving) { lib.addFromCatalog(c, onAdded) }) {
            PickerRow(
                c.colorHex ?: c.colorHexes.firstOrNull(),
                "${c.manufacturer} \u00b7 ${c.name}",
                "${c.material} \u00b7 ${formatNumber(c.weight)} g",
            )
        }
    }
}
