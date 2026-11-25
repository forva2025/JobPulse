package com.example.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.filter.JobFilterEngine
import com.example.data.model.CustomFilter
import com.example.data.model.JobAlert
import com.example.data.model.MatchMode
import com.example.data.model.MonitoredSource
import com.example.data.model.WorkMode
import com.example.viewmodel.UiState

@Composable
fun FiltersScreen(
    filtersState: UiState<List<CustomFilter>>,
    sourcesState: UiState<List<MonitoredSource>>,
    alertsState: UiState<List<JobAlert>>,
    showAddDialog: Boolean,
    onDismissAddDialog: () -> Unit,
    onAddFilter: (
        name: String,
        matchMode: MatchMode,
        includeKeywords: List<String>,
        excludeKeywords: List<String>,
        workMode: WorkMode,
        minHourlyRate: Long,
        targetSourceIds: List<String>,
        instantPush: Boolean,
        onDone: (Boolean) -> Unit
    ) -> Unit,
    onUpdateFilter: (
        id: String,
        name: String,
        enabled: Boolean,
        matchMode: MatchMode,
        includeKeywords: List<String>,
        excludeKeywords: List<String>,
        workMode: WorkMode,
        minHourlyRate: Long,
        targetSourceIds: List<String>,
        instantPush: Boolean,
        onDone: (Boolean) -> Unit
    ) -> Unit,
    onToggleFilter: (CustomFilter, Boolean) -> Unit,
    onDeleteFilter: (CustomFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    var editingFilter by remember { mutableStateOf<CustomFilter?>(null) }
    val sources = (sourcesState as? UiState.Success)?.data.orEmpty()
    val alerts = (alertsState as? UiState.Success)?.data.orEmpty()
    val sourceNameById = remember(sources) { sources.associate { it.id to it.name } }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "filter_explainer") {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Active rules filter incoming job drops in real time before pushing heads-up notifications.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        when (filtersState) {
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
                            text = filtersState.message,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }

            is UiState.Success -> {
                val filters = filtersState.data
                if (filters.isEmpty()) {
                    item(key = "empty") {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Tune,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = stringResource(R.string.filters_empty_title),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = stringResource(R.string.filters_empty_body),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(filters, key = { it.id }) { filter ->
                        val liveMatches = remember(alerts, filter) {
                            alerts.count { JobFilterEngine.matchesAlert(it, filter) }
                        }
                        CustomFilterCard(
                            filter = filter,
                            liveMatchCount = liveMatches,
                            sourceNameById = sourceNameById,
                            onToggle = { enabled -> onToggleFilter(filter, enabled) },
                            onEdit = { editingFilter = filter },
                            onDelete = { onDeleteFilter(filter) }
                        )
                    }
                }
            }
        }

        item(key = "bottom_spacer") {
            Spacer(modifier = Modifier.height(84.dp))
        }
    }

    if (showAddDialog) {
        AddEditFilterDialog(
            existing = null,
            sources = sources,
            onDismiss = onDismissAddDialog,
            onSave = { name, matchMode, includes, excludes, workMode, minRate, targetIds, instantPush ->
                onAddFilter(name, matchMode, includes, excludes, workMode, minRate, targetIds, instantPush) { ok ->
                    if (ok) onDismissAddDialog()
                }
            }
        )
    }

    editingFilter?.let { filter ->
        AddEditFilterDialog(
            existing = filter,
            sources = sources,
            onDismiss = { editingFilter = null },
            onSave = { name, matchMode, includes, excludes, workMode, minRate, targetIds, instantPush ->
                onUpdateFilter(
                    filter.id,
                    name,
                    filter.enabled,
                    matchMode,
                    includes,
                    excludes,
                    workMode,
                    minRate,
                    targetIds,
                    instantPush
                ) { ok ->
                    if (ok) editingFilter = null
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomFilterCard(
    filter: CustomFilter,
    liveMatchCount: Int,
    sourceNameById: Map<String, String>,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (filter.enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(18.dp)
            )
            .testTag("filter_card_${filter.id}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = filter.name,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (filter.instantPush) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsOff,
                            contentDescription = null,
                            tint = if (filter.instantPush) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = if (filter.instantPush) "Instant Push" else "Silent Feed",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (filter.instantPush) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "· $liveMatchCount current match(es)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = filter.enabled,
                    onCheckedChange = onToggle
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (filter.workModeEnum() != WorkMode.ANY) {
                    RuleBadge(
                        text = filter.workModeEnum().label,
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
                if (filter.minHourlyRate > 0L) {
                    RuleBadge(
                        text = "$${filter.minHourlyRate}+/hr",
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                if (filter.includeKeywords.size > 1) {
                    RuleBadge(
                        text = filter.matchModeEnum().name,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                filter.includeKeywords.forEach { kw ->
                    RuleBadge(
                        text = "+ $kw",
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                filter.excludeKeywords.forEach { kw ->
                    RuleBadge(
                        text = "- $kw",
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            if (filter.targetSourceIds.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                val names = filter.targetSourceIds.mapNotNull { sourceNameById[it] }.joinToString(", ")
                if (names.isNotBlank()) {
                    Text(
                        text = "Sites: $names",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.End
            ) {
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
private fun RuleBadge(
    text: String,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color
) {
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(7.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddEditFilterDialog(
    existing: CustomFilter?,
    sources: List<MonitoredSource>,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        matchMode: MatchMode,
        includeKeywords: List<String>,
        excludeKeywords: List<String>,
        workMode: WorkMode,
        minHourlyRate: Long,
        targetSourceIds: List<String>,
        instantPush: Boolean
    ) -> Unit
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var includeRaw by remember { mutableStateOf(existing?.includeKeywords?.joinToString(", ") ?: "") }
    var excludeRaw by remember { mutableStateOf(existing?.excludeKeywords?.joinToString(", ") ?: "") }
    var matchMode by remember { mutableStateOf(existing?.matchModeEnum() ?: MatchMode.ANY) }
    var workMode by remember { mutableStateOf(existing?.workModeEnum() ?: WorkMode.ANY) }
    var minRateRaw by remember { mutableStateOf(if ((existing?.minHourlyRate ?: 0L) > 0L) existing!!.minHourlyRate.toString() else "") }
    var instantPush by remember { mutableStateOf(existing?.instantPush ?: true) }
    val selectedSourceIds = remember {
        mutableStateListOf<String>().apply {
            existing?.targetSourceIds?.let { addAll(it) }
        }
    }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (existing == null) {
                    stringResource(R.string.filters_dialog_add_title)
                } else {
                    stringResource(R.string.filters_dialog_edit_title)
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
                        errorMsg = null
                    },
                    label = { Text(stringResource(R.string.filters_name_label)) },
                    placeholder = { Text("e.g. High-Pay AI & Kotlin") },
                    singleLine = true,
                    isError = errorMsg != null,
                    supportingText = errorMsg?.let { msg -> { Text(msg) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("filter_name_input")
                )

                OutlinedTextField(
                    value = includeRaw,
                    onValueChange = { includeRaw = it },
                    label = { Text(stringResource(R.string.filters_include_label)) },
                    placeholder = { Text("Kotlin, Android, LLM, Python, AI Trainer") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("filter_include_input")
                )

                OutlinedTextField(
                    value = excludeRaw,
                    onValueChange = { excludeRaw = it },
                    label = { Text(stringResource(R.string.filters_exclude_label)) },
                    placeholder = { Text("Intern, Unpaid, Onsite") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("filter_exclude_input")
                )

                Text(
                    text = stringResource(R.string.filters_match_mode_label),
                    style = MaterialTheme.typography.labelLarge
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MatchMode.entries.forEach { mode ->
                        FilterChip(
                            selected = matchMode == mode,
                            onClick = { matchMode = mode },
                            label = { Text(mode.label) }
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.filters_work_mode_label),
                    style = MaterialTheme.typography.labelLarge
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    WorkMode.entries.forEach { mode ->
                        FilterChip(
                            selected = workMode == mode,
                            onClick = { workMode = mode },
                            label = { Text(mode.label) }
                        )
                    }
                }

                OutlinedTextField(
                    value = minRateRaw,
                    onValueChange = { input ->
                        minRateRaw = input.filter { it.isDigit() }.take(5)
                    },
                    label = { Text(stringResource(R.string.filters_min_rate_label)) },
                    placeholder = { Text("e.g. 50") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                if (sources.isNotEmpty()) {
                    Text(
                        text = "Target Websites (leave empty for All Sites)",
                        style = MaterialTheme.typography.labelLarge
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        sources.forEach { src ->
                            val isSelected = src.id in selectedSourceIds
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    if (isSelected) selectedSourceIds.remove(src.id)
                                    else selectedSourceIds.add(src.id)
                                },
                                label = { Text(src.name) }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.filters_instant_push_label),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = stringResource(R.string.filters_instant_push_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = instantPush,
                        onCheckedChange = { instantPush = it }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanName = name.trim()
                    if (cleanName.isEmpty()) {
                        errorMsg = "Please enter a filter name."
                        return@Button
                    }
                    val includes = includeRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(25)
                    val excludes = excludeRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(25)
                    val minRate = minRateRaw.toLongOrNull()?.coerceIn(0L, 10_000L) ?: 0L
                    onSave(
                        cleanName,
                        matchMode,
                        includes,
                        excludes,
                        workMode,
                        minRate,
                        selectedSourceIds.toList(),
                        instantPush
                    )
                },
                modifier = Modifier.testTag("save_filter_button")
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
