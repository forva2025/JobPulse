package com.example.service

import android.content.Context
import com.example.data.filter.JobFilterEngine
import com.example.data.model.CustomFilter
import com.example.data.model.MonitoredSource
import com.example.data.model.ScanLogEntry
import com.example.data.model.SourceStatus
import com.example.data.network.JobBoardFetcher
import com.example.data.repository.JobPulseRepository
import com.example.data.repository.RadarPreferencesStore
import com.example.notifications.JobNotificationManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

data class RadarRuntimeTelemetry(
    val serviceRunning: Boolean = false,
    val scanningNow: Boolean = false,
    val lastScanMillis: Long = 0L,
    val avgLatencyMs: Long = 0L,
    val totalChecksThisSession: Int = 0,
    val totalNewDropsThisSession: Int = 0,
    val recentLogs: List<ScanLogEntry> = emptyList()
)

object JobMonitorCoordinator {

    private val fetcher = JobBoardFetcher()
    private val scanMutex = Mutex()

    private val _telemetry = MutableStateFlow(RadarRuntimeTelemetry())
    val telemetry: StateFlow<RadarRuntimeTelemetry> = _telemetry.asStateFlow()

    fun setServiceRunning(running: Boolean) {
        _telemetry.value = _telemetry.value.copy(serviceRunning = running)
    }

    suspend fun scanSourcesNow(
        context: Context,
        repository: JobPulseRepository,
        prefsStore: RadarPreferencesStore,
        sources: List<MonitoredSource>,
        filters: List<CustomFilter>,
        bypassCache: Boolean = false
    ): Int {
        if (sources.isEmpty()) return 0

        return scanMutex.withLock {
            _telemetry.value = _telemetry.value.copy(scanningNow = true)
            try {
                val seenFingerprints = prefsStore.getSeenFingerprints().toMutableSet()
                var newAlertsDispatched = 0
                val latencies = mutableListOf<Long>()
                val newLogs = mutableListOf<ScanLogEntry>()

                val outcomes = coroutineScope {
                    sources.map { source ->
                        async {
                            source to fetcher.fetchSource(source, bypassCache = bypassCache)
                        }
                    }.awaitAll()
                }

                for ((source, outcome) in outcomes) {
                    latencies.add(outcome.latencyMs)

                    if (outcome.errorMessage != null) {
                        repository.updateSourceTelemetry(
                            sourceId = source.id,
                            status = SourceStatus.ERROR,
                            latencyMs = outcome.latencyMs,
                            jobCount = source.lastJobCount,
                            errorMessage = outcome.errorMessage,
                            etag = outcome.etag,
                            lastModified = outcome.lastModified
                        )
                        newLogs.add(
                            ScanLogEntry(
                                timestampMillis = System.currentTimeMillis(),
                                sourceName = source.name,
                                statusText = outcome.errorMessage,
                                latencyMs = outcome.latencyMs,
                                newJobsCount = 0,
                                isError = true
                            )
                        )
                        continue
                    }

                    if (outcome.notModified) {
                        repository.updateSourceTelemetry(
                            sourceId = source.id,
                            status = SourceStatus.LIVE,
                            latencyMs = outcome.latencyMs,
                            jobCount = source.lastJobCount,
                            errorMessage = "",
                            etag = outcome.etag,
                            lastModified = outcome.lastModified
                        )
                        newLogs.add(
                            ScanLogEntry(
                                timestampMillis = System.currentTimeMillis(),
                                sourceName = source.name,
                                statusText = "HTTP 304 · No changes",
                                latencyMs = outcome.latencyMs,
                                newJobsCount = 0,
                                isError = false
                            )
                        )
                        continue
                    }

                    val alreadyBaselined = prefsStore.isSourceBaselined(source.id)
                    val parsedJobs = outcome.jobs
                    val newlyDiscovered = parsedJobs.filter { it.fingerprint !in seenFingerprints }

                    repository.updateSourceTelemetry(
                        sourceId = source.id,
                        status = SourceStatus.LIVE,
                        latencyMs = outcome.latencyMs,
                        jobCount = parsedJobs.size.toLong(),
                        errorMessage = "",
                        etag = outcome.etag,
                        lastModified = outcome.lastModified
                    )

                    if (parsedJobs.isNotEmpty()) {
                        val toPersist = if (alreadyBaselined) {
                            newlyDiscovered.take(20)
                        } else {
                            // First baseline scan: store top 12 listings cleanly without spamming push banners
                            newlyDiscovered.take(12)
                        }

                        var freshCountForSource = 0
                        for (listing in toPersist) {
                            val eval = JobFilterEngine.evaluate(
                                sourceId = source.id,
                                listing = listing,
                                allFilters = filters
                            )
                            val matchedNames = eval.matchedFilters.map { it.name }
                            val isFreshDrop = alreadyBaselined

                            val saveRes = repository.saveDetectedAlert(
                                sourceId = source.id,
                                sourceName = source.name,
                                fingerprint = listing.fingerprint,
                                title = listing.title,
                                company = listing.company,
                                location = listing.location,
                                compensation = listing.compensation,
                                jobUrl = listing.jobUrl,
                                matchedFilterNames = matchedNames,
                                detectionLatencyMs = outcome.latencyMs,
                                freshAlert = isFreshDrop
                            )

                            if (saveRes.isSuccess && isFreshDrop) {
                                freshCountForSource++
                                if (eval.shouldSendInstantPush) {
                                    newAlertsDispatched++
                                    JobNotificationManager.showJobDropNotification(
                                        context = context,
                                        sourceName = source.name,
                                        title = listing.title,
                                        company = listing.company,
                                        compensation = listing.compensation,
                                        location = listing.location,
                                        jobUrl = listing.jobUrl,
                                        matchedFilterNames = matchedNames,
                                        detectionLatencyMs = outcome.latencyMs,
                                        notificationId = abs(listing.fingerprint.hashCode()) + 3000
                                    )
                                }
                            }
                        }

                        val allDiscoveredHashes = parsedJobs.map { it.fingerprint }
                        seenFingerprints.addAll(allDiscoveredHashes)
                        prefsStore.recordSeenFingerprints(source.id, allDiscoveredHashes)

                        val summary = when {
                            !alreadyBaselined -> "HTTP ${outcome.httpCode} · Indexed ${parsedJobs.size} baseline roles"
                            freshCountForSource > 0 -> "HTTP ${outcome.httpCode} · $freshCountForSource NEW role(s)!"
                            else -> "HTTP ${outcome.httpCode} · ${parsedJobs.size} roles checked"
                        }
                        newLogs.add(
                            ScanLogEntry(
                                timestampMillis = System.currentTimeMillis(),
                                sourceName = source.name,
                                statusText = summary,
                                latencyMs = outcome.latencyMs,
                                newJobsCount = freshCountForSource,
                                isError = false
                            )
                        )
                    } else {
                        newLogs.add(
                            ScanLogEntry(
                                timestampMillis = System.currentTimeMillis(),
                                sourceName = source.name,
                                statusText = "HTTP ${outcome.httpCode} · Page reachable (dynamic JS board)",
                                latencyMs = outcome.latencyMs,
                                newJobsCount = 0,
                                isError = false
                            )
                        )
                    }
                }

                val current = _telemetry.value
                val avgLatency = if (latencies.isNotEmpty()) latencies.average().toLong() else current.avgLatencyMs
                _telemetry.value = current.copy(
                    scanningNow = false,
                    lastScanMillis = System.currentTimeMillis(),
                    avgLatencyMs = avgLatency,
                    totalChecksThisSession = current.totalChecksThisSession + outcomes.size,
                    totalNewDropsThisSession = current.totalNewDropsThisSession + newAlertsDispatched,
                    recentLogs = (newLogs + current.recentLogs).take(30)
                )
                newAlertsDispatched
            } finally {
                _telemetry.value = _telemetry.value.copy(scanningNow = false)
            }
        }
    }
}
