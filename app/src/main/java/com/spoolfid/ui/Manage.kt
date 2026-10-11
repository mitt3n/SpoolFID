package com.spoolfid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spoolfid.AppViewModel
import com.spoolfid.AuditItem
import com.spoolfid.Library
import com.spoolfid.Section
import com.spoolfid.Tab
import com.spoolfid.spoolman.Filament
import com.spoolfid.spoolman.Spool
import com.spoolfid.spoolman.Vendor

/** The colour to show for a filament: its single colour, or the first of a multi-colour one. */
internal val Filament.displayColor: String?
    get() = colorHex ?: multiColorHexes?.split(",")?.firstOrNull()?.trim()

@Composable
internal fun ManageScreen(vm: AppViewModel) {
    val lib = vm.library
    LaunchedEffect(vm.configured) { if (vm.configured) lib.ensureLoaded() }

    if (!vm.configured) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Connect to Spoolman to manage your spools", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { vm.tab = Tab.SETTINGS }) { Text("Set Spoolman address") }
        }
        return
    }

    val q = lib.query.trim().lowercase()
    fun matches(vararg fields: String?) = q.isEmpty() || fields.any { it?.lowercase()?.contains(q) == true }

    val allSpools = lib.spools()
    val spools = allSpools.filter { (lib.showArchived || !it.archived) && matches("#${it.id}", it.title, it.vendor, it.material, it.location, it.lotNr) }
    val filaments = lib.filaments.filter { matches("#${it.id}", it.title, it.vendor?.name, it.material, it.articleNumber) }
    val vendors = lib.vendors.filter { matches(it.name, it.comment) }
    val audit = vm.auditItems()
    val auditShown = audit.filter { matches("#${it.spool.id}", it.spool.title, it.issue) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = lib.query,
                    onValueChange = { lib.query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search ${lib.section.label.lowercase()}") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (lib.query.isNotEmpty()) {
                            IconButton(onClick = { lib.query = "" }) { Icon(Icons.Default.Close, "Clear search") }
                        }
                    },
                    singleLine = true,
                )
                IconButton(onClick = { lib.refresh() }) { Icon(Icons.Default.Refresh, "Refresh") }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Section.entries.forEach { s ->
                    val count = when (s) {
                        Section.SPOOLS -> allSpools.count { lib.showArchived || !it.archived }
                        Section.FILAMENTS -> lib.filaments.size
                        Section.MANUFACTURERS -> lib.vendors.size
                        Section.AUDIT -> audit.size
                    }
                    FilterChip(
                        selected = lib.section == s,
                        onClick = { lib.section = s },
                        label = { Text("${s.label} · $count") },
                    )
                }
                if (lib.section == Section.SPOOLS) {
                    FilterChip(
                        selected = lib.showArchived,
                        onClick = { lib.showArchived = !lib.showArchived },
                        label = { Text("Archived") },
                    )
                }
            }

            if (lib.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            if (lib.section == Section.AUDIT) {
                Text(
                    "Spools whose tags need attention. Tap one to fix it.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            } else if (lib.notice == null) {
                Text(
                    "Hold a tag to the phone to open what it's linked to.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            lib.loadError?.let { err ->
                Card(
                    Modifier.fillMaxWidth().padding(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(err, color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = { lib.refresh() }) { Text("Retry") }
                    }
                }
            }
            lib.notice?.let { note ->
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(start = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(note, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSecondaryContainer)
                    IconButton(onClick = { lib.notice = null }) { Icon(Icons.Default.Close, "Dismiss") }
                }
            }

            LazyColumn(Modifier.weight(1f)) {
                when (lib.section) {
                    Section.SPOOLS -> items(spools, key = { "s${it.id}" }) { spool ->
                        SpoolItem(spool, vm.requiredTags) { lib.edit(spool) }
                        HorizontalDivider()
                    }
                    Section.FILAMENTS -> items(filaments, key = { "f${it.id}" }) { f ->
                        FilamentItem(f, spoolCount = allSpools.count { it.filamentId == f.id }) { lib.edit(f) }
                        HorizontalDivider()
                    }
                    Section.MANUFACTURERS -> items(vendors, key = { "v${it.id}" }) { v ->
                        VendorItem(v, filamentCount = lib.filaments.count { it.vendor?.id == v.id }) { lib.edit(v) }
                        HorizontalDivider()
                    }
                    Section.AUDIT -> items(auditShown, key = { "a${it.spool.id}${it.issue}" }) { item ->
                        AuditRow(item) { lib.edit(item.spool) }
                        HorizontalDivider()
                    }
                }
                if (isEmpty(lib, spools, filaments, vendors, auditShown) && !lib.loading && lib.loadError == null) {
                    item {
                        Text(
                            when {
                                q.isNotEmpty() -> "Nothing matches"
                                lib.section == Section.AUDIT -> "Every spool has the tags it needs"
                                else -> "Nothing here yet. Use Add to create the first one."
                            },
                            Modifier.fillMaxWidth().padding(32.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                // Room so the Add button never covers the last row.
                item { Spacer(Modifier.height(88.dp)) }
            }
        }

        if (lib.section != Section.AUDIT) ExtendedFloatingActionButton(
            onClick = {
                when (lib.section) {
                    Section.SPOOLS -> lib.newSpool()
                    Section.FILAMENTS -> lib.newFilament()
                    Section.MANUFACTURERS -> lib.newVendor()
                    Section.AUDIT -> Unit
                }
            },
            icon = { Icon(Icons.Default.Add, null) },
            text = {
                Text(
                    when (lib.section) {
                        Section.SPOOLS -> "Add spool"
                        Section.FILAMENTS -> "Add filament"
                        Section.MANUFACTURERS -> "Add manufacturer"
                        Section.AUDIT -> ""
                    },
                )
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

private fun isEmpty(lib: Library, spools: List<Spool>, filaments: List<Filament>, vendors: List<Vendor>, audit: List<AuditItem>): Boolean =
    when (lib.section) {
        Section.SPOOLS -> spools.isEmpty()
        Section.FILAMENTS -> filaments.isEmpty()
        Section.MANUFACTURERS -> vendors.isEmpty()
        Section.AUDIT -> audit.isEmpty()
    }

@Composable
private fun AuditRow(item: AuditItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Swatch(item.spool.colorHex, 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("#${item.spool.id}  ${item.spool.title}", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.issue, style = MaterialTheme.typography.bodyMedium, color = WarnAmber)
        }
    }
}

@Composable
private fun SpoolItem(spool: Spool, requiredTags: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Swatch(spool.colorHex, 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "#${spool.id}  ${spool.title}",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                (if (spool.archived) "Archived · " else "") + spool.subtitle(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (spool.tagCount > 0) TagBadge(spool.tagCount, requiredTags, spool.unlinkedCount)
    }
}

@Composable
private fun FilamentItem(f: Filament, spoolCount: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Swatch(f.displayColor, 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                f.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(f.subtitle, "$spoolCount spool${if (spoolCount == 1) "" else "s"}").filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (f.tags.isNotEmpty()) {
            Text("${f.tags.size} tag${if (f.tags.size == 1) "" else "s"}", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun VendorItem(v: Vendor, filamentCount: Int, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(v.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(
                "$filamentCount filament${if (filamentCount == 1) "" else "s"}",
                v.emptySpoolWeight?.let { "empty spool ${com.spoolfid.spoolman.formatNumber(it)} g" },
                v.comment?.takeIf { it.isNotBlank() },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
