package com.spoolfid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spoolfid.AppViewModel
import com.spoolfid.CatalogBrowse
import com.spoolfid.Editor
import com.spoolfid.spoolman.CatalogFilament
import com.spoolfid.spoolman.formatNumber

/** Browse Spoolman's public filament database by manufacturer and add what you don't have yet. */
@Composable
internal fun CatalogScreen(vm: AppViewModel, e: Editor.OfCatalog) {
    val lib = vm.library
    val catalog = lib.catalog
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { lib.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(
                e.manufacturer ?: "Filament database",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        when {
            catalog == null -> {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val error = lib.catalogError
                    if (error != null) {
                        Text(error, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { lib.loadCatalog() }) { Text("Retry") }
                    } else {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("Loading the filament database")
                    }
                }
            }
            e.manufacturer == null -> ManufacturerList(vm, catalog)
            else -> FilamentList(vm, catalog, e.manufacturer)
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, hint: String) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        placeholder = { Text(hint) },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Close, "Clear search") }
        },
        singleLine = true,
    )
}

@Composable
private fun ManufacturerList(vm: AppViewModel, catalog: List<CatalogFilament>) {
    val lib = vm.library
    var query by remember { mutableStateOf("") }
    val shown = CatalogBrowse.manufacturers(catalog, query)
    Column(Modifier.fillMaxSize()) {
        SearchField(query, { query = it }, "Search brands, names or materials")
        Text(
            "${shown.size} manufacturer${if (shown.size == 1) "" else "s"}. Pick one to add it and its filaments to Spoolman.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.name }) { m ->
                val have = CatalogBrowse.vendorNamed(m.name, lib.vendors) != null
                Row(
                    Modifier.fillMaxWidth().clickable { lib.showManufacturer(m.name) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(m.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${m.count} filament${if (m.count == 1) "" else "s"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        if (have) "In Spoolman" else "New",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (have) MaterialTheme.colorScheme.onSurfaceVariant else SuccessGreen,
                    )
                }
                HorizontalDivider()
            }
            if (shown.isEmpty()) {
                item { Text("Nothing matches", Modifier.fillMaxWidth().padding(32.dp)) }
            }
        }
    }
}

@Composable
private fun FilamentList(vm: AppViewModel, catalog: List<CatalogFilament>, manufacturer: String) {
    val lib = vm.library
    var query by remember(manufacturer) { mutableStateOf("") }
    // The picks reset once an import has refreshed the filament list, so what was added shows as added.
    var picked by remember(manufacturer, lib.filaments) { mutableStateOf(setOf<String>()) }
    val shown = CatalogBrowse.filamentsOf(catalog, manufacturer, query)
    val addable = shown.filter { !CatalogBrowse.registered(it, lib.filaments) }
    val vendorExists = CatalogBrowse.vendorNamed(manufacturer, lib.vendors) != null
    val chosen = catalog.filter { it.id in picked && it.manufacturer == manufacturer }

    Column(Modifier.fillMaxSize()) {
        if (!vendorExists) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Not in Spoolman yet. It's created when you add a filament, or on its own.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { lib.importFromCatalog(manufacturer, emptyList()) }, enabled = !lib.saving) {
                    Text("Add manufacturer")
                }
            }
        }
        SearchField(query, { query = it }, "Search ${manufacturer}'s filaments")
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${shown.size} filament${if (shown.size == 1) "" else "s"}, ${addable.size} not added yet",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            TextButton(onClick = { picked = addable.map { it.id }.toSet() }, enabled = addable.isNotEmpty()) { Text("Select all new") }
            TextButton(onClick = { picked = emptySet() }, enabled = picked.isNotEmpty()) { Text("Clear") }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.id }) { c ->
                val have = CatalogBrowse.registered(c, lib.filaments)
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = !have) { picked = if (c.id in picked) picked - c.id else picked + c.id }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Swatch(c.colorHex ?: c.colorHexes.firstOrNull(), 28.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(c.material, "${formatNumber(c.weight)} g", "${formatNumber(c.diameter)} mm").joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (have) {
                        Text("Added", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Checkbox(checked = c.id in picked, onCheckedChange = null)
                    }
                }
                HorizontalDivider()
            }
        }
        Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                lib.importMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp)) }
                Button(
                    onClick = { lib.importFromCatalog(manufacturer, chosen) },
                    enabled = chosen.isNotEmpty() && !lib.saving,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text(
                        when {
                            lib.saving -> "Adding..."
                            chosen.isEmpty() -> "Pick filaments to add"
                            else -> "Add ${chosen.size} filament${if (chosen.size == 1) "" else "s"} to Spoolman"
                        },
                    )
                }
            }
        }
    }
}
