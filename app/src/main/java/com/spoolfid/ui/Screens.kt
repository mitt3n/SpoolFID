package com.spoolfid.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme
import com.spoolfid.AppViewModel
import com.spoolfid.NfcStatus
import com.spoolfid.Phase
import com.spoolfid.ReadState
import com.spoolfid.Session
import com.spoolfid.TagFilter
import com.spoolfid.Tab
import com.spoolfid.nfc.TagContents
import com.spoolfid.spoolman.Spool
import com.spoolfid.spoolman.TagLinkStatus
import kotlin.math.roundToInt

private val SuccessGreen = Color(0xFF2E7D32)
private val WarnAmber = Color(0xFFB26A00)

@Composable
fun SpoolFIDTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private fun Tab.icon(): ImageVector = when (this) {
    Tab.WRITE -> Icons.Default.Edit
    Tab.READ -> Icons.Default.Search
    Tab.SETTINGS -> Icons.Default.Settings
}

@Composable
fun App(vm: AppViewModel) {
    val session = vm.session

    // Keep the display awake while tags are being written or read, if enabled.
    val view = LocalView.current
    val keepOn = vm.keepScreenOn && (session != null || vm.tab == Tab.READ)
    DisposableEffect(keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }

    if (session != null) {
        BackHandler { vm.stopSession() }
        Surface(Modifier.fillMaxSize()) { SessionScreen(vm, session) }
        return
    }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = vm.tab == t,
                        onClick = { vm.tab = t },
                        icon = { Icon(t.icon(), contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (vm.tab) {
                Tab.WRITE -> SpoolListScreen(vm)
                Tab.READ -> ReadScreen(vm)
                Tab.SETTINGS -> SettingsScreen(vm)
            }
        }
    }
}

// ---------------------------------------------------------------- shared bits

private fun hexColor(hex: String?): Color = try {
    val h = hex?.removePrefix("#")?.take(6) ?: ""
    if (h.length == 6) Color(android.graphics.Color.parseColor("#$h")) else Color.Gray
} catch (_: IllegalArgumentException) {
    Color.Gray
}

@Composable
private fun Swatch(hex: String?, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(hexColor(hex))
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
    )
}

private fun Spool.subtitle(): String =
    listOfNotNull(vendor, material, remainingWeight?.let { "${it.roundToInt()} g left" }, location?.takeIf { it.isNotBlank() })
        .joinToString(" · ")

@Composable
private fun NfcBanner(status: NfcStatus) {
    val text = when (status) {
        NfcStatus.OK -> return
        NfcStatus.DISABLED -> "NFC is turned off."
        NfcStatus.UNSUPPORTED -> "This device has no NFC."
        NfcStatus.NO_MIFARE ->
            "This phone's NFC chip doesn't report MIFARE Classic support. Reading and writing CFS tags will likely fail."
    }
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (status == NfcStatus.DISABLED) {
            val context = LocalContext.current
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }) {
                Text("Turn on")
            }
        }
    }
}

// ---------------------------------------------------------------- write tab

@Composable
private fun SpoolListScreen(vm: AppViewModel) {
    val visible = vm.visibleSpools()
    val selecting = vm.selection.isNotEmpty()

    Column(Modifier.fillMaxSize()) {
        NfcBanner(vm.nfcStatus)

        if (!vm.configured) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Connect to Spoolman to get started", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { vm.tab = Tab.SETTINGS }) { Text("Set Spoolman address") }
            }
            return@Column
        }

        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = vm.query,
                onValueChange = { vm.query = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search spools") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (vm.query.isNotEmpty()) {
                        IconButton(onClick = { vm.query = "" }) { Icon(Icons.Default.Close, "Clear search") }
                    }
                },
                singleLine = true,
            )
            IconButton(onClick = { vm.refresh() }) { Icon(Icons.Default.Refresh, "Refresh") }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // "Partly tagged" only means something when a spool needs more than one tag.
            TagFilter.entries.filter { it != TagFilter.PARTIAL || vm.requiredTags > 1 }.forEach { f ->
                FilterChip(
                    selected = vm.tagFilter == f,
                    onClick = { vm.updateTagFilter(f) },
                    label = { Text("${f.label} · ${vm.countFor(f)}") },
                )
            }
        }
        Text(
            "Showing ${visible.size} of ${vm.spools.size} spools",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())

        vm.loadError?.let { err ->
            Card(
                Modifier.fillMaxWidth().padding(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(err, color = MaterialTheme.colorScheme.onErrorContainer)
                    TextButton(onClick = { vm.refresh() }) { Text("Retry") }
                }
            }
        }

        LazyColumn(Modifier.weight(1f)) {
            items(visible, key = { it.id }) { spool ->
                SpoolRow(
                    spool = spool,
                    selected = spool.id in vm.selection,
                    selecting = selecting,
                    requiredTags = vm.requiredTags,
                    onClick = { if (selecting) vm.toggleSelect(spool.id) else vm.startSession(listOf(spool)) },
                    onLongClick = { vm.toggleSelect(spool.id) },
                )
                HorizontalDivider()
            }
            if (visible.isEmpty() && !vm.loading && vm.loadError == null) {
                item {
                    Text(
                        if (vm.spools.isEmpty()) "No spools in Spoolman" else "No spools match",
                        Modifier.fillMaxWidth().padding(32.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        if (selecting) {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { vm.clearSelection() }) { Text("Clear") }
                    TextButton(onClick = { vm.selectAllVisible() }) { Text("All") }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { vm.startSelected() }) { Text("Write ${vm.selection.size}") }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpoolRow(
    spool: Spool,
    selected: Boolean,
    selecting: Boolean,
    requiredTags: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
                spool.subtitle(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (spool.tagCount > 0) {
            TagBadge(spool.tagCount, requiredTags)
            Spacer(Modifier.width(8.dp))
        }
        if (selecting) Checkbox(checked = selected, onCheckedChange = null)
    }
}

/** Green when the spool has all the tags it should, amber when only some are written. */
@Composable
private fun TagBadge(count: Int, required: Int) {
    val done = count >= required
    val color = if (done) SuccessGreen else WarnAmber
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (done) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = color,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            if (done) "$count tag${if (count == 1) "" else "s"}" else "$count/$required tags",
            color = color,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

// ---------------------------------------------------------------- write session

@Composable
private fun SessionScreen(vm: AppViewModel, s: Session) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    s.phase == Phase.DONE -> "Finished"
                    s.copies > 1 -> "Spool ${s.index + 1} of ${s.queue.size} · tag ${s.copy + 1} of ${s.copies}"
                    else -> "Spool ${s.index + 1} of ${s.queue.size}"
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { vm.stopSession() }) { Icon(Icons.Default.Close, "Stop") }
        }
        if (s.tagsTotal > 1) {
            LinearProgressIndicator(
                progress = { (s.tagsDone + (if (s.phase == Phase.SUCCESS || s.phase == Phase.DONE) 1 else 0)) / s.tagsTotal.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (s.phase == Phase.DONE) {
            // Close by itself after the configured delay, so batch writing needs no extra tap.
            val closeAfter = vm.doneCloseSeconds
            var remaining by remember { mutableIntStateOf(closeAfter) }
            LaunchedEffect(Unit) {
                if (closeAfter > 0) {
                    while (remaining > 0) {
                        delay(1000)
                        remaining--
                    }
                    vm.stopSession()
                }
            }
            Spacer(Modifier.weight(1f))
            Icon(Icons.Default.CheckCircle, null, Modifier.size(96.dp), tint = SuccessGreen)
            Spacer(Modifier.height(16.dp))
            Text("${s.written} tag${if (s.written == 1) "" else "s"} written", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.weight(1f))
            Button(onClick = { vm.stopSession() }, Modifier.fillMaxWidth().height(56.dp)) {
                Text(if (closeAfter > 0 && remaining > 0) "Done ($remaining)" else "Done")
            }
            return@Column
        }

        val spool = s.current
        val mapped = vm.mapped(spool)
        Spacer(Modifier.height(24.dp))
        Swatch(spool.colorHex, 88.dp)
        Spacer(Modifier.height(12.dp))
        Text("#${spool.id}  ${spool.title}", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(spool.subtitle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text(
            "${mapped.material.name} (${mapped.material.id}) · #${mapped.payload.colorHex} · ${mapped.payload.weight.grams} g",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        listOfNotNull(
            mapped.materialNote,
            mapped.weightNote,
            mapped.colorNote,
            if (spool.id <= 1) "Spool ID ${spool.id} is treated as 'no ID' by Jacobean's firmware." else null,
        ).forEach {
            Text(it, color = WarnAmber, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }

        Spacer(Modifier.weight(1f))
        StatusPanel(s, onOverwrite = { vm.confirmOverwriteNow() }, onDecline = { vm.declineOverwrite() })
        Spacer(Modifier.weight(1f))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { vm.skip() },
                enabled = s.phase == Phase.WAITING || s.phase == Phase.ERROR || s.phase == Phase.CONFIRM,
                modifier = Modifier.weight(1f).height(52.dp),
            ) { Text(if (s.hasNext) "Skip" else "Finish") }
            OutlinedButton(onClick = { vm.stopSession() }, Modifier.weight(1f).height(52.dp)) { Text("Stop") }
        }
    }
}

@Composable
private fun StatusPanel(s: Session, onOverwrite: () -> Unit, onDecline: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        when (s.phase) {
            Phase.WAITING -> {
                Text(
                    if (s.copy == 0) "Hold a tag to the back of the phone" else "Now a tag for the other side of the spool",
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
            }
            Phase.WRITING -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("Writing - keep the tag still", style = MaterialTheme.typography.titleMedium)
            }
            Phase.SUCCESS -> {
                Icon(Icons.Default.Check, null, Modifier.size(72.dp), tint = SuccessGreen)
                Text("Written", style = MaterialTheme.typography.titleLarge, color = SuccessGreen)
                s.message?.let { Text(it, color = WarnAmber, textAlign = TextAlign.Center) }
            }
            Phase.ERROR -> {
                Icon(Icons.Default.Warning, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.error)
                Text(s.message ?: "Error", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                Text("Tap the tag again", style = MaterialTheme.typography.bodySmall)
            }
            Phase.CONFIRM -> {
                Icon(Icons.Default.Warning, null, Modifier.size(56.dp), tint = WarnAmber)
                Text(
                    s.message ?: "This tag already has data",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Text("Overwrite it with this spool?", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onDecline, Modifier.weight(1f).height(52.dp)) { Text("Use another tag") }
                    Button(onClick = onOverwrite, Modifier.weight(1f).height(52.dp)) { Text("Overwrite") }
                }
            }
            Phase.DONE -> Unit
        }
    }
}

// ---------------------------------------------------------------- read tab

@Composable
private fun ReadScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize()) {
        NfcBanner(vm.nfcStatus)
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (val r = vm.read) {
                ReadState.Idle -> Text("Hold a tag to the back of the phone to read it", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                ReadState.Reading -> CircularProgressIndicator()
                is ReadState.Error -> {
                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                    Text(r.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                }
                is ReadState.Result -> ReadResultCard(r, onLink = { vm.linkReadTag() })
            }
        }
    }
}

@Composable
private fun ReadResultCard(r: ReadState.Result, onLink: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("UID ${r.uid}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            when (val c = r.contents) {
                TagContents.Blank -> Text("Blank tag - ready to write", style = MaterialTheme.typography.titleMedium)
                is TagContents.Written -> {
                    val p = c.payload
                    if (p == null) {
                        Text("Written, but the payload couldn't be decoded")
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Swatch(p.colorHex, 28.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                when {
                                    p.spoolId == null -> "Spool ID unreadable"
                                    p.spoolId > 1 -> "Spoolman spool #${p.spoolId}"
                                    p.spoolId == 1 -> "Spool #1 (printer firmware ignores ID 1)"
                                    else -> "No spool ID on tag"
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        Text("Material ${p.materialId} · #${p.colorHex} · ${p.weight?.grams?.let { "$it g" } ?: p.weightCode}")
                        Text("Batch ${p.batch} · date ${p.date} · supplier ${p.supplier} · serial ${p.serial}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            r.justLinked?.let { Text(it, color = SuccessGreen, style = MaterialTheme.typography.titleSmall) }
            if (r.link != null || r.note != null) HorizontalDivider()
            when (val l = r.link) {
                is TagLinkStatus.Linked -> SpoolmanSpool("Linked in Spoolman", l.spool, ok = true)
                is TagLinkStatus.Mismatch -> {
                    Text(
                        (l.tagSpoolId?.let { "The tag says spool #$it, but " } ?: "The tag names no spool, but ") +
                            "Spoolman has it linked to:",
                        color = WarnAmber,
                    )
                    SpoolmanSpool(null, l.linked, ok = false)
                    if (l.tagSpoolId != null && r.canLink) {
                        Button(onClick = onLink) { Text("Link to #${l.tagSpoolId} instead") }
                    }
                }
                is TagLinkStatus.NotLinked -> {
                    Text("Not linked in Spoolman yet", color = WarnAmber, style = MaterialTheme.typography.titleSmall)
                    if (l.spool != null) {
                        SpoolmanSpool("The tag names", l.spool, ok = false)
                        if (r.canLink) Button(onClick = onLink) { Text("Link to #${l.tagSpoolId}") }
                    } else {
                        Text("Spool #${l.tagSpoolId} doesn't exist in Spoolman", color = WarnAmber)
                    }
                    if (!r.canLink) {
                        Text("This Spoolman can't link tags (it needs version 0.27 or newer).", style = MaterialTheme.typography.bodySmall)
                    }
                }
                TagLinkStatus.Unknown -> Text("Not known to Spoolman")
                null -> Unit
            }
            r.note?.let { Text(it, color = WarnAmber) }
        }
    }
}

/** A Spoolman spool shown inside the read card, with how many tags Spoolman has linked to it. */
@Composable
private fun SpoolmanSpool(label: String?, spool: Spool, ok: Boolean) {
    if (label != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (ok) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (ok) SuccessGreen else WarnAmber,
            )
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
    Text("#${spool.id}  ${spool.title}", style = MaterialTheme.typography.titleMedium)
    Text(spool.subtitle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (spool.tagsSupported) {
        val n = spool.tags.size
        Text("$n tag${if (n == 1) "" else "s"} linked in Spoolman", style = MaterialTheme.typography.bodySmall)
    }
}

// ---------------------------------------------------------------- settings tab

@Composable
private fun SettingsScreen(vm: AppViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = vm.url,
            onValueChange = { vm.url = it },
            label = { Text("Spoolman address") },
            placeholder = { Text("192.168.1.50:7912") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { vm.saveSettings() }) { Text("Save & test") }
            Spacer(Modifier.width(12.dp))
            vm.settingsStatus?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Link tags in Spoolman", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Links each tag you write to its spool in Spoolman, so Spoolman knows which tags belong to which spool. " +
                        "The tag count in the list comes from Spoolman.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = vm.linkTags, onCheckedChange = { vm.updateLinkTags(it) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Two tags per spool", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Write a tag for each flange. The CFS only reads the tag on the side facing its reader.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = vm.twoTagsPerSpool, onCheckedChange = { vm.updateTwoTagsPerSpool(it) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Keep screen on", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Stops the display sleeping during write sessions and on the Read tab.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = vm.keepScreenOn, onCheckedChange = { vm.updateKeepScreenOn(it) })
        }
        Column {
            Text("Close \"Done\" screen automatically", style = MaterialTheme.typography.titleMedium)
            Text(
                "Returns to the spool list this long after the last tag is written.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Off", 3 to "3 s", 5 to "5 s", 10 to "10 s").forEach { (secs, label) ->
                    FilterChip(
                        selected = vm.doneCloseSeconds == secs,
                        onClick = { vm.updateDoneCloseSeconds(secs) },
                        label = { Text(label) },
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Confirm before overwriting", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Ask first when a tag already holds a different spool or other CFS data. " +
                        "Turn off for fastest batch writing.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = vm.confirmOverwrite, onCheckedChange = { vm.updateConfirmOverwrite(it) })
        }
    }
}
