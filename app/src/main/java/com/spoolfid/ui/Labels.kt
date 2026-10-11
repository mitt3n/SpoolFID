package com.spoolfid.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spoolfid.AppViewModel
import com.spoolfid.LabelsPage
import com.spoolfid.Tab
import com.spoolfid.labels.LabelLayout
import com.spoolfid.labels.LabelPlan
import com.spoolfid.labels.LabelSettings

// ---------------------------------------------------------------- scanning a QR label

/** Point the camera at a QR label (from Spoolman or SpoolFID) to open that spool. */
@Composable
internal fun QrScreen(vm: AppViewModel) {
    val lib = vm.library
    LaunchedEffect(vm.configured) { if (vm.configured) lib.ensureLoaded() }
    Column(Modifier.fillMaxSize()) {
        if (!vm.configured) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Connect to Spoolman first", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { vm.goTo(Tab.SETTINGS) }) { Text("Set Spoolman address") }
            }
            return@Column
        }
        Text(
            "Point the camera at a QR label from Spoolman or SpoolFID to open its spool.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        Box(Modifier.weight(1f).padding(horizontal = 12.dp).clip(RoundedCornerShape(16.dp))) {
            QrScanner(onCode = { lib.openFromCode(it) })
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(lib.notice ?: " ", color = WarnAmber, modifier = Modifier.weight(1f))
            if (lib.notice != null) IconButton(onClick = { lib.notice = null }) { Icon(Icons.Default.Close, "Dismiss") }
        }
    }
}

// ---------------------------------------------------------------- printing labels

@Composable
internal fun LabelsScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize()) {
        GroupTabs(LabelsPage.entries.map { it.label }, vm.labelsPage.ordinal) { vm.goLabels(LabelsPage.entries[it]) }
        when (vm.labelsPage) {
            LabelsPage.SPOOLS -> LabelSpools(vm)
            LabelsPage.LAYOUT -> LabelLayoutPage(vm)
        }
    }
}

@Composable
private fun LabelSpools(vm: AppViewModel) {
    if (!vm.configured) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Connect to Spoolman to print labels", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { vm.goTo(Tab.SETTINGS) }) { Text("Set Spoolman address") }
        }
        return
    }
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase()
    val shown = vm.spools.filter { s ->
        q.isEmpty() || listOfNotNull("#${s.id}", s.title, s.vendor, s.material, s.location).any { it.lowercase().contains(q) }
    }
    val chosen = vm.spools.filter { it.id in vm.labelSelection }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            placeholder = { Text("Search spools") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear search") }
            },
            singleLine = true,
        )
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${chosen.size} selected",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            TextButton(onClick = { vm.selectLabels(shown.map { it.id }) }, enabled = shown.isNotEmpty()) { Text("Select all shown") }
            TextButton(onClick = { vm.selectLabels(emptyList()) }, enabled = chosen.isNotEmpty()) { Text("Clear") }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.id }) { s ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.toggleLabel(s.id) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Swatch(s.colorHex, 28.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("#${s.id}  ${s.title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            s.subtitle(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Checkbox(checked = s.id in vm.labelSelection, onCheckedChange = null)
                }
                HorizontalDivider()
            }
            if (shown.isEmpty()) item { Text("No spools match", Modifier.fillMaxWidth().padding(32.dp), textAlign = TextAlign.Center) }
        }
        Surface(tonalElevation = 3.dp) {
            Button(
                onClick = { vm.printLabels(context, chosen) },
                enabled = chosen.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(12.dp).height(52.dp),
            ) {
                Text(if (chosen.isEmpty()) "Pick spools to print" else "Print ${chosen.size} label${if (chosen.size == 1) "" else "s"}")
            }
        }
    }
}

@Composable
private fun LabelLayoutPage(vm: AppViewModel) {
    val s = vm.labelSettings
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column {
            Text("Layout", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LabelLayout.entries.forEach { l ->
                    FilterChip(
                        selected = s.layout == l,
                        onClick = { vm.updateLabels { it.copy(layout = l) } },
                        label = { Text(l.label) },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (s.layout == LabelLayout.SHEET) "Several labels per A4 page, with a line to cut along."
                else "One label per page, with the page sized to the label, for a label printer.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Column {
            Text("Label size", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LabelSettings.PRESETS.forEach { (w, h) ->
                    FilterChip(
                        selected = s.widthMm == w && s.heightMm == h,
                        onClick = { vm.updateLabels { it.copy(widthMm = w, heightMm = h) } },
                        label = { Text("$w × $h mm") },
                    )
                }
            }
        }
        StepperRow(
            "Width",
            "",
            s.widthMm,
            LabelSettings.MIN_MM..LabelSettings.MAX_MM,
            format = { "$it mm" },
            onChange = { v -> vm.updateLabels { it.copy(widthMm = v) } },
        )
        StepperRow(
            "Height",
            "",
            s.heightMm,
            LabelSettings.MIN_MM..LabelSettings.MAX_MM,
            format = { "$it mm" },
            onChange = { v -> vm.updateLabels { it.copy(heightMm = v) } },
        )
        if (s.layout == LabelLayout.SHEET) {
            val plan = LabelPlan.compute(1, s, 210f, 297f)
            Text(
                if (plan == null) "These labels are too big for an A4 page." else "On A4: ${plan.columns} across and ${plan.rows} down, ${plan.perPage} labels a page.",
                style = MaterialTheme.typography.bodyMedium,
                color = if (plan == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()
        SwitchRow(
            "QR code holds a web link",
            "Off: Spoolman's own spool code, which Spoolman's scanner and SpoolFID read. On: a link to the spool's page, which " +
                "any phone camera can open. Both work in Spoolman's scanner and SpoolFID.",
            s.qrAsLink,
            { v -> vm.updateLabels { it.copy(qrAsLink = v) } },
        )
        SwitchRow("Show the manufacturer", "Under the spool's name.", s.showVendor, { v -> vm.updateLabels { it.copy(showVendor = v) } })
        SwitchRow("Show the material", "Under the spool's name.", s.showMaterial, { v -> vm.updateLabels { it.copy(showMaterial = v) } })
    }
}
