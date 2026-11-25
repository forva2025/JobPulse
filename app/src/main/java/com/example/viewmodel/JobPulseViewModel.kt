package com.example.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.CustomFilter
import com.example.data.model.JobAlert
import com.example.data.model.MatchMode
import com.example.data.model.MonitoredSource
import com.example.data.model.PresetSource
import com.example.data.model.PresetSourcesCatalog
import com.example.data.model.SourceCategory
import com.example.data.model.WorkMode
import com.example.data.repository.JobPulseRepository
import com.example.data.repository.RadarPreferences
import com.example.data.repository.RadarPreferencesStore
import com.example.notifications.JobNotificationManager
import com.example.service.JobMonitorCoordinator
import com.example.service.JobMonitorService
import com.example.service.RadarRuntimeTelemetry
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

enum class FeedChipFilter(val label: String) {
    ALL("All"),
    FRESH("New Drops"),
    MATCHED("Filter Match"),
    SAVED("Saved"),
    APPLIED("Applied")
}

class JobPulseViewModel(
    private val repository: JobPulseRepository,
    private val prefsStore: RadarPreferencesStore,
    private val currentUserId: String
) : ViewModel() {

    val sourcesState: StateFlow<UiState<List<MonitoredSource>>> =
        repository.observeSources(currentUserId)
            .map<List<MonitoredSource>, UiState<List<MonitoredSource>>> { UiState.Success(it) }
            .catch { error ->
                emit(unwrapStreamError("sources", error))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = UiState.Loading
            )

    val filtersState: StateFlow<UiState<List<CustomFilter>>> =
        repository.observeFilters(currentUserId)
            .map<List<CustomFilter>, UiState<List<CustomFilter>>> { UiState.Success(it) }
            .catch { error ->
                emit(unwrapStreamError("filters", error))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = UiState.Loading
            )

    val alertsState: StateFlow<UiState<List<JobAlert>>> =
        repository.observeAlerts(currentUserId)
            .map<List<JobAlert>, UiState<List<JobAlert>>> { UiState.Success(it) }
            .catch { error ->
                emit(unwrapStreamError("alerts", error))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = UiState.Loading
            )

    val radarPrefs: StateFlow<RadarPreferences> = prefsStore.preferencesFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = RadarPreferences()
        )

    val radarTelemetry: StateFlow<RadarRuntimeTelemetry> = JobMonitorCoordinator.telemetry

    private val _selectedFeedFilter = MutableStateFlow(FeedChipFilter.ALL)
    val selectedFeedFilter: StateFlow<FeedChipFilter> = _selectedFeedFilter.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    fun selectFeedFilter(filter: FeedChipFilter) {
        _selectedFeedFilter.value = filter
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    // --- SCANNING & RADAR SERVICE ---

    fun scanAllEnabledNow(context: Context) {
        val currentSources = (sourcesState.value as? UiState.Success)?.data
            ?.filter { it.enabled }
            .orEmpty()
        if (currentSources.isEmpty()) {
            _snackbarMessage.value = "Add and enable at least one website first."
            return
        }
        val currentFilters = (filtersState.value as? UiState.Success)?.data.orEmpty()
        viewModelScope.launch {
            val newAlerts = JobMonitorCoordinator.scanSourcesNow(
                context = context.applicationContext,
                repository = repository,
                prefsStore = prefsStore,
                sources = currentSources,
                filters = currentFilters,
                bypassCache = true
            )
            _snackbarMessage.value = if (newAlerts > 0) {
                "Scan complete — $newAlerts new matching job alert(s) pushed!"
            } else {
                "Scanned ${currentSources.size} website(s) in ${radarTelemetry.value.avgLatencyMs}ms avg."
            }
        }
    }

    fun scanSingleSource(context: Context, source: MonitoredSource) {
        val currentFilters = (filtersState.value as? UiState.Success)?.data.orEmpty()
        viewModelScope.launch {
            val newAlerts = JobMonitorCoordinator.scanSourcesNow(
                context = context.applicationContext,
                repository = repository,
                prefsStore = prefsStore,
                sources = listOf(source),
                filters = currentFilters,
                bypassCache = true
            )
            _snackbarMessage.value = if (newAlerts > 0) {
                "${source.name}: $newAlerts new job(s) detected!"
            } else {
                "${source.name} scanned."
            }
        }
    }

    fun setLiveRadarEnabled(context: Context, enabled: Boolean) {
        viewModelScope.launch {
            prefsStore.setLiveRadarEnabled(enabled)
            if (enabled) {
                JobMonitorService.start(context.applicationContext)
                _snackbarMessage.value = "Live Radar active — monitoring in background."
            } else {
                JobMonitorService.stop(context.applicationContext)
                _snackbarMessage.value = "Live Radar paused."
            }
        }
    }

    fun setDefaultPollCadence(seconds: Int) {
        viewModelScope.launch {
            prefsStore.setDefaultPollIntervalSeconds(seconds)
            _snackbarMessage.value = "Default radar cadence set to ${formatCadence(seconds)}."
        }
    }

    fun sendTestPushAlert(context: Context) {
        val dispatched = JobNotificationManager.showTestAlertNotification(context)
        _snackbarMessage.value = if (dispatched) {
            "High-priority test push notification sent!"
        } else {
            "Grant notification permission to receive push alerts."
        }
    }

    // --- MONITORED SOURCES ---

    fun addSource(
        name: String,
        url: String,
        category: SourceCategory,
        pollIntervalSeconds: Long,
        onComplete: (Boolean) -> Unit = {}
    ) {
        viewModelScope.launch {
            val res = repository.addSource(name, url, category, pollIntervalSeconds)
            if (res.isSuccess) {
                _snackbarMessage.value = "Added $name to monitored websites."
                onComplete(true)
            } else {
                _snackbarMessage.value = res.exceptionOrNull()?.message ?: "Could not add website."
                onComplete(false)
            }
        }
    }

    fun addPreset(context: Context, preset: PresetSource) {
        val existingUrls = (sourcesState.value as? UiState.Success)?.data
            ?.map { it.url.lowercase() }
            ?.toSet()
            .orEmpty()
        if (preset.url.lowercase() in existingUrls) {
            _snackbarMessage.value = "${preset.name} is already monitored."
            return
        }
        viewModelScope.launch {
            val res = repository.addSource(
                name = preset.name,
                rawUrl = preset.url,
                category = preset.category,
                pollIntervalSeconds = preset.defaultIntervalSeconds
            )
            if (res.isSuccess) {
                _snackbarMessage.value = "Added ${preset.name}! Scanning now…"
                val id = res.getOrThrow()
                val newSource = MonitoredSource(
                    id = id,
                    userId = currentUserId,
                    name = preset.name,
                    url = preset.url,
                    category = preset.category.name,
                    enabled = true,
                    pollIntervalSeconds = preset.defaultIntervalSeconds
                )
                val currentFilters = (filtersState.value as? UiState.Success)?.data.orEmpty()
                JobMonitorCoordinator.scanSourcesNow(
                    context = context.applicationContext,
                    repository = repository,
                    prefsStore = prefsStore,
                    sources = listOf(newSource),
                    filters = currentFilters,
                    bypassCache = true
                )
            } else {
                _snackbarMessage.value = res.exceptionOrNull()?.message ?: "Failed to add ${preset.name}."
            }
        }
    }

    fun addCoreFourPresets(context: Context) {
        val existingUrls = (sourcesState.value as? UiState.Success)?.data
            ?.map { it.url.lowercase() }
            ?.toSet()
            .orEmpty()
        viewModelScope.launch {
            val res = repository.addPresetSources(PresetSourcesCatalog.coreFour, existingUrls)
            val count = res.getOrDefault(0)
            if (count > 0) {
                _snackbarMessage.value = "Added $count core AI job board(s) — scanning live…"
                val enabledSources = repository.getEnabledSourcesOnce().getOrDefault(emptyList())
                val filters = repository.getFiltersOnce().getOrDefault(emptyList())
                JobMonitorCoordinator.scanSourcesNow(
                    context = context.applicationContext,
                    repository = repository,
                    prefsStore = prefsStore,
                    sources = enabledSources,
                    filters = filters,
                    bypassCache = true
                )
            } else {
                _snackbarMessage.value = "Mercor, micro1, Turing & TELUS Digital are already added."
            }
        }
    }

    fun updateSource(
        sourceId: String,
        name: String,
        url: String,
        category: SourceCategory,
        enabled: Boolean,
        pollIntervalSeconds: Long,
        onComplete: (Boolean) -> Unit = {}
    ) {
        viewModelScope.launch {
            val res = repository.updateSourceConfig(
                sourceId = sourceId,
                name = name,
                rawUrl = url,
                category = category,
                enabled = enabled,
                pollIntervalSeconds = pollIntervalSeconds
            )
            if (res.isSuccess) {
                _snackbarMessage.value = "Updated $name."
                onComplete(true)
            } else {
                _snackbarMessage.value = res.exceptionOrNull()?.message ?: "Update failed."
                onComplete(false)
            }
        }
    }

    fun toggleSourceEnabled(source: MonitoredSource, enabled: Boolean) {
        viewModelScope.launch {
            val res = repository.toggleSourceEnabled(source.id, enabled)
            if (res.isFailure) {
                _snackbarMessage.value = res.exceptionOrNull()?.message ?: "Failed to toggle site."
            }
        }
    }

    fun deleteSource(source: MonitoredSource) {
        viewModelScope.launch {
            val res = repository.deleteSource(source.id)
            if (res.isSuccess) {
                prefsStore.clearSourceBaseline(source.id)
                _snackbarMessage.value = "Removed ${source.name}."
            } else {
                _snackbarMessage.value = res.exceptionOrNull()?.message ?: "Failed to remove site."
            }
        }
    }

    // --- CUSTOM FILTERS ---

    fun addFilter(
        name: String,
        matchMode: MatchMode,
        includeKeywords: List<String>,
        excludeKeywords: List<String>,
        workMode: WorkMode,
        minHourlyRate: Long,
        targetSourceIds: List<String>,
        instantPush: Boolean,
        onComplete: (Boolean) -> Unit = {}
    ) {
        viewModelScope.launch {
            val res = repository.addFilter(
                name = name,
                matchMode = matchMode,
                includeKeywords = includeKeywords,
                excludeKeywords = excludeKeywords,
                workMode = workMode,
                minHourlyRate = minHourlyRate,
                targetSourceIds = targetSourceIds,
                instantPush = instantPush
            )
            if (res.isSuccess) {
                _snackbarMessage.value = "Saved filter \"$name\"."
                onComplete(true)
            } else {
                _snackbarMessage.value = res.exceptionOrNull()?.message ?: "Could not save filter."
                onComplete(false)
            }
        }
    }

    fun updateFilter(
        filterId: String,
        name: String,
        enabled: Boolean,
        matchMode: MatchMode,
        includeKeywords: List<String>,
        excludeKeywords: List<String>,
        workMode: WorkMode,
        minHourlyRate: Long,
        targetSourceIds: List<String>,
        instantPush: Boolean,
        onComplete: (Boolean) -> Unit = {}
    ) {
        viewModelScope.launch {
            val res = repository.updateFilter(
                filterId = filterId,
                name = name,
                enabled = enabled,
                matchMode = matchMode,
                includeKeywords = includeKeywords,
                excludeKeywords = excludeKeywords,
                workMode = workMode,
                minHourlyRate = minHourlyRate,
                targetSourceIds = targetSourceIds,
                instantPush = instantPush
            )
            if (res.isSuccess) {
                _snackbarMessage.value = "Updated filter \"$name\"."
                onComplete(true)
            } else {
                _snackbarMessage.value = res.exceptionOrNull()?.message ?: "Update failed."
                onComplete(false)
            }
        }
    }

    fun toggleFilterEnabled(filter: CustomFilter, enabled: Boolean) {
        viewModelScope.launch {
            val res = repository.toggleFilterEnabled(filter.id, enabled)
            if (res.isFailure) {
                _snackbarMessage.value = res.exceptionOrNull()?.message ?: "Failed to update filter."
            }
        }
    }

    fun deleteFilter(filter: CustomFilter) {
        viewModelScope.launch {
            val res = repository.deleteFilter(filter.id)
            if (res.isSuccess) {
                _snackbarMessage.value = "Deleted filter \"${filter.name}\"."
            } else {
                _snackbarMessage.value = res.exceptionOrNull()?.message ?: "Failed to delete filter."
            }
        }
    }

    // --- JOB ALERTS ---

    fun toggleBookmark(alert: JobAlert) {
        viewModelScope.launch {
            repository.updateAlertState(alertId = alert.id, bookmarked = !alert.bookmarked)
        }
    }

    fun toggleApplied(alert: JobAlert) {
        viewModelScope.launch {
            val nextApplied = !alert.applied
            val res = repository.updateAlertState(
                alertId = alert.id,
                applied = nextApplied,
                read = true
            )
            if (res.isSuccess && nextApplied) {
                _snackbarMessage.value = "Marked \"${alert.title}\" as applied."
            }
        }
    }

    fun markAlertRead(alert: JobAlert) {
        if (alert.read) return
        viewModelScope.launch {
            repository.updateAlertState(alertId = alert.id, read = true)
        }
    }

    fun deleteAlert(alert: JobAlert) {
        viewModelScope.launch {
            repository.deleteAlert(alert.id)
        }
    }

    private fun unwrapStreamError(streamName: String, error: Throwable): UiState.Error {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val rootError = firestoreError ?: error
        Log.w(TAG, "Error observing $streamName", rootError)
        return UiState.Error(rootError.localizedMessage ?: "Failed to load $streamName.")
    }

    private fun formatCadence(seconds: Int): String =
        if (seconds < 60) "${seconds}s" else "${seconds / 60}m"

    private companion object {
        const val TAG = "JobPulseVM"
        const val STOP_TIMEOUT_MILLIS = 5000L
    }
}
