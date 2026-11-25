package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.MonitoredSource
import com.example.data.model.PresetSource
import com.example.data.model.PresetSourcesCatalog
import com.example.data.model.SourceCategory
import com.example.data.model.SourceStatus
import com.example.data.network.UrlSecurityValidator
import com.example.viewmodel.UiState

@Composable
fun SitesScreen(
    sourcesState: UiState<List<MonitoredSource>>,
    scanningNow: Boolean,
    showAddDialog: Boolean,
    onDismissAddDialog: () -> Unit,
    onAddSource: (name: String, url: String, category: SourceCategory, intervalSec: Long, onDone: (Boolean) -> Unit) -> Unit,
    onUpdateSource: (id: String, name: String, url: String, category: SourceCategory, enabled: Boolean, intervalSec: Long, onDone: (Boolean) -> Unit) -> Unit,
    onToggleSource: (MonitoredSource, Boolean) -> Unit,
    onDeleteSource: (MonitoredSource) -> Unit,
    onScanSingleSource: (MonitoredSource) -> Unit,
    onAddPreset: (PresetSource) -> Unit,
    onAddCoreFour: () -> Unit,
    modifier: Modifier = Modifier
) {
    var editingSource by remember { mutableStateOf<MonitoredSource?>(null) }
    val sources = (sourcesState as? UiState.Success)?.data.orEmpty()
    val existingUrlSet = remember(sources) { sources.map { it.url.lowercase() }.toSet() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "core_four_cta") {
            val missingCore = PresetSourcesCatalog.coreFour.any { it.url.lowercase() !in existingUrlSet }
            if (missingCore) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                            RoundedCornerShape(18.dp)
                        )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "One-Tap Core AI Job Boards",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Start watching Mercor, micro1, Turing, and TELUS Digital with 15–30s low-latency checks.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = onAddCoreFour,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("sites_add_core_four_button")
                        ) {
                            Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.sites_quick_add_all))
                        }
                    }
                }
            }
        }

        when (sourcesState) {
            is UiState.Loading -> {
                item(key = "loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            is UiState.Error -> {
                item(key = "error") {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = sourcesState.message,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }

            is UiState.Success -> {
                if (sources.isNotEmpty()) {
                    item(key = "active_header") {
                        Text(
                            text = "YOUR MONITORED SITES (${sources.size})",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    items(sources, key = { it.id }) { source ->
                        MonitoredSourceCard(
                            source = source,
                            scanningNow = scanningNow,
                            onToggle = { enabled -> onToggleSource(source, enabled) },
                            onScan = { onScanSingleSource(source) },
                            onEdit = { editingSource = source },
                            onDelete = { onDeleteSource(source) }
                        )
                    }
                } else {
                    item(key = "empty_sites") {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Language,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(34.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.sites_empty_title),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.sites_empty_body),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        item(key = "presets_header") {
            Text(
                text = stringResource(R.string.sites_quick_presets).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        items(PresetSourcesCatalog.all, key = { "preset_${it.url}" }) { preset ->
            val alreadyAdded = preset.url.lowercase() in existingUrlSet
            PresetSourceRow(
                preset = preset,
                alreadyAdded = alreadyAdded,
                onAdd = { onAddPreset(preset) }
            )
        }

        item(key = "bottom_spacer") {
            Spacer(modifier = Modifier.height(84.dp))
        }
    }

    if (showAddDialog) {
        AddEditSourceDialog(
            existing = null,
            onDismiss = onDismissAddDialog,
            onSave = { name, url, category, interval ->
                onAddSource(name, url, category, interval) { ok ->
                    if (ok) onDismissAddDialog()
                }
            }
        )
    }

    editingSource?.let { source ->
        AddEditSourceDialog(
            existing = source,
            onDismiss = { editingSource = null },
            onSave = { name, url, category, interval ->
                onUpdateSource(source.id, name, url, category, source.enabled, interval) { ok ->
                    if (ok) editingSource = null
                }
            }
        )
    }
}

@Composable
private fun MonitoredSourceCard(
    source: MonitoredSource,
    scanningNow: Boolean,
    onToggle: (Boolean) -> Unit,
    onScan: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val statusColor = when (source.statusEnum()) {
        SourceStatus.LIVE -> MaterialTheme.colorScheme.primary
        SourceStatus.ERROR -> MaterialTheme.colorScheme.error
        SourceStatus.IDLE -> MaterialTheme.colorScheme.secondary
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
            .testTag("source_card_${source.id}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (source.enabled) statusColor else MaterialTheme.colorScheme.outline)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = source.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = source.url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Switch(
                    checked = source.enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.testTag("source_switch_${source.id}")
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmallBadge(text = source.categoryEnum().label)
                SmallBadge(text = "Every ${formatInterval(source.pollIntervalSeconds)}")
                if (source.lastLatencyMs > 0L) {
                    SmallBadge(text = "${source.lastLatencyMs}ms")
                }
                if (source.lastJobCount > 0L) {
                    SmallBadge(text = "${source.lastJobCount} roles")
                }
            }

            if (!source.lastError.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = source.lastError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = onScan,
                    enabled = !scanningNow,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.height(40.dp)
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.cd_scan_site), modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Scan", style = MaterialTheme.typography.labelLarge)
                }
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(onClick = onEdit) {
                    Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.cd_edit))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.cd_delete))
                }
            }
        }
    }
}

@Composable
private fun PresetSourceRow(
    preset: PresetSource,
    alreadyAdded: Boolean,
    onAdd: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = preset.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    SmallBadge(text = preset.category.label)
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = preset.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            FilledTonalButton(
                onClick = onAdd,
                enabled = !alreadyAdded,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier
                    .height(44.dp)
                    .testTag("preset_add_${preset.name.lowercase().replace(" ", "_")}")
            ) {
                Icon(
                    imageVector = if (alreadyAdded) Icons.Rounded.Check else Icons.Rounded.Add,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (alreadyAdded) "Added" else "Add")
            }
        }
    }
}

@Composable
private fun SmallBadge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddEditSourceDialog(
    existing: MonitoredSource?,
    onDismiss: () -> Unit,
    onSave: (name: String, url: String, category: SourceCategory, intervalSec: Long) -> Unit
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var url by remember { mutableStateOf(existing?.url ?: "https://") }
    var category by remember { mutableStateOf(existing?.categoryEnum() ?: SourceCategory.CUSTOM) }
    var intervalSec by remember { mutableLongStateOf(existing?.pollIntervalSeconds ?: 15L) }
    var validationError by remember { mutableStateOf<String?>(null) }

    val intervals = listOf(10L to "10s", 15L to "15s", 30L to "30s", 60L to "60s", 300L to "5m")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (existing == null) {
                    stringResource(R.string.sites_dialog_add_title)
                } else {
                    stringResource(R.string.sites_dialog_edit_title)
                }
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it.take(80)
                        validationError = null
                    },
                    label = { Text(stringResource(R.string.sites_name_label)) },
                    placeholder = { Text("e.g. Mercor, Ashby AI, Company Careers") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("site_name_input")
                )

                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it.take(500)
                        validationError = null
                    },
                    label = { Text(stringResource(R.string.sites_url_label)) },
                    placeholder = { Text("https://boards.greenhouse.io/company") },
                    singleLine = true,
                    isError = validationError != null,
                    supportingText = {
                        if (validationError != null) {
                            Text(validationError.orEmpty())
                        } else {
                            Text("Supports Greenhouse, Lever, Ashby, RSS, JSON-LD & career pages.")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("site_url_input")
                )

                Text(
                    text = stringResource(R.string.sites_category_label),
                    style = MaterialTheme.typography.labelLarge
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SourceCategory.entries.forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat.label) }
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.sites_interval_label),
                    style = MaterialTheme.typography.labelLarge
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    intervals.forEach { (sec, label) ->
                        FilterChip(
                            selected = intervalSec == sec,
                            onClick = { intervalSec = sec },
                            label = { Text(label) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmedName = name.trim()
                    if (trimmedName.isEmpty()) {
                        validationError = "Please enter a website name."
                        return@Button
                    }
                    val urlCheck = UrlSecurityValidator.normalizeAndValidateSourceUrl(url)
                    if (urlCheck.isFailure) {
                        validationError = urlCheck.exceptionOrNull()?.message
                            ?: "Enter a valid public HTTPS URL."
                        return@Button
                    }
                    onSave(trimmedName, urlCheck.getOrThrow(), category, intervalSec)
                },
                modifier = Modifier.testTag("save_site_button")
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

private fun formatInterval(seconds: Long): String =
    if (seconds < 60L) "${seconds}s" else "${seconds / 60L}m"
