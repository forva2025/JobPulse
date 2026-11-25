package com.example.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class SourceCategory(val label: String) {
    AI_TALENT("AI Talent"),
    TECH_BOARD("Tech Board"),
    ATS_API("ATS API"),
    RSS_FEED("RSS / Atom"),
    CUSTOM("Custom URL");

    companion object {
        fun fromRaw(raw: String): SourceCategory =
            entries.firstOrNull { it.name == raw } ?: CUSTOM
    }
}

enum class SourceStatus(val label: String) {
    IDLE("Ready"),
    LIVE("Live"),
    ERROR("Error");

    companion object {
        fun fromRaw(raw: String): SourceStatus =
            entries.firstOrNull { it.name == raw } ?: IDLE
    }
}

enum class MatchMode(val label: String) {
    ANY("Match Any Keyword"),
    ALL("Match All Keywords");

    companion object {
        fun fromRaw(raw: String): MatchMode =
            entries.firstOrNull { it.name == raw } ?: ANY
    }
}

enum class WorkMode(val label: String) {
    ANY("Any Mode"),
    REMOTE("Remote Only"),
    CONTRACT("Contract / Hourly"),
    FULL_TIME("Full-Time");

    companion object {
        fun fromRaw(raw: String): WorkMode =
            entries.firstOrNull { it.name == raw } ?: ANY
    }
}

@Keep
@IgnoreExtraProperties
data class MonitoredSource(
    @DocumentId val id: String = "",
    val userId: String = "",
    val name: String = "",
    val url: String = "",
    val category: String = SourceCategory.CUSTOM.name,
    val enabled: Boolean = true,
    val pollIntervalSeconds: Long = 30L,
    val lastStatus: String = SourceStatus.IDLE.name,
    val lastLatencyMs: Long = 0L,
    val lastJobCount: Long = 0L,
    val lastError: String? = null,
    val etag: String? = null,
    val lastModifiedHeader: String? = null,
    val lastCheckedAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun categoryEnum(): SourceCategory = SourceCategory.fromRaw(category)
    fun statusEnum(): SourceStatus = SourceStatus.fromRaw(lastStatus)
}

@Keep
@IgnoreExtraProperties
data class CustomFilter(
    @DocumentId val id: String = "",
    val userId: String = "",
    val name: String = "",
    val enabled: Boolean = true,
    val matchMode: String = MatchMode.ANY.name,
    val includeKeywords: List<String> = emptyList(),
    val excludeKeywords: List<String> = emptyList(),
    val workMode: String = WorkMode.ANY.name,
    val minHourlyRate: Long = 0L,
    val targetSourceIds: List<String> = emptyList(),
    val instantPush: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun matchModeEnum(): MatchMode = MatchMode.fromRaw(matchMode)
    fun workModeEnum(): WorkMode = WorkMode.fromRaw(workMode)
}

@Keep
@IgnoreExtraProperties
data class JobAlert(
    @DocumentId val id: String = "",
    val userId: String = "",
    val sourceId: String = "",
    val sourceName: String = "",
    val fingerprint: String = "",
    val title: String = "",
    val company: String = "",
    val location: String = "",
    val compensation: String = "",
    val jobUrl: String = "",
    val matchedFilterNames: List<String> = emptyList(),
    val detectionLatencyMs: Long = 0L,
    val freshAlert: Boolean = false,
    val bookmarked: Boolean = false,
    val read: Boolean = false,
    val applied: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

data class ParsedJobListing(
    val fingerprint: String,
    val title: String,
    val company: String,
    val location: String,
    val compensation: String,
    val jobUrl: String,
    val rawSnippet: String = ""
)

data class SourceScanOutcome(
    val sourceId: String,
    val sourceName: String,
    val httpCode: Int,
    val notModified: Boolean,
    val latencyMs: Long,
    val jobs: List<ParsedJobListing>,
    val etag: String?,
    val lastModified: String?,
    val errorMessage: String? = null
)

data class ScanLogEntry(
    val timestampMillis: Long,
    val sourceName: String,
    val statusText: String,
    val latencyMs: Long,
    val newJobsCount: Int,
    val isError: Boolean
)

data class PresetSource(
    val name: String,
    val url: String,
    val category: SourceCategory,
    val subtitle: String,
    val defaultIntervalSeconds: Long = 15L,
    val coreRecommended: Boolean = false
)

object PresetSourcesCatalog {
    val all: List<PresetSource> = listOf(
        PresetSource(
            name = "Mercor",
            url = "https://work.mercor.com/explore",
            category = SourceCategory.AI_TALENT,
            subtitle = "AI training, software & domain expert contracts",
            defaultIntervalSeconds = 15L,
            coreRecommended = true
        ),
        PresetSource(
            name = "micro1",
            url = "https://www.micro1.ai/jobs",
            category = SourceCategory.AI_TALENT,
            subtitle = "Vetted AI & remote software engineering roles",
            defaultIntervalSeconds = 15L,
            coreRecommended = true
        ),
        PresetSource(
            name = "Turing",
            url = "https://www.turing.com/jobs",
            category = SourceCategory.AI_TALENT,
            subtitle = "Remote developers, LLM evaluation & AI engineering",
            defaultIntervalSeconds = 15L,
            coreRecommended = true
        ),
        PresetSource(
            name = "TELUS Digital",
            url = "https://www.telusdigital.com/careers",
            category = SourceCategory.AI_TALENT,
            subtitle = "AI data solutions, digital engineering & CX roles",
            defaultIntervalSeconds = 30L,
            coreRecommended = true
        ),
        PresetSource(
            name = "Remotive API",
            url = "https://remotive.com/api/remote-jobs",
            category = SourceCategory.ATS_API,
            subtitle = "Live structured JSON feed of global remote tech jobs",
            defaultIntervalSeconds = 30L
        ),
        PresetSource(
            name = "RemoteOK API",
            url = "https://remoteok.com/api",
            category = SourceCategory.ATS_API,
            subtitle = "Real-time JSON board for remote engineering & AI",
            defaultIntervalSeconds = 30L
        ),
        PresetSource(
            name = "We Work Remotely RSS",
            url = "https://weworkremotely.com/remote-jobs.rss",
            category = SourceCategory.RSS_FEED,
            subtitle = "Fast XML feed with instant new listing entries",
            defaultIntervalSeconds = 30L
        ),
        PresetSource(
            name = "Scale AI / Outlier",
            url = "https://scale.com/careers",
            category = SourceCategory.TECH_BOARD,
            subtitle = "AI infrastructure, research & engineering careers",
            defaultIntervalSeconds = 30L
        )
    )

    val coreFour: List<PresetSource> = all.filter { it.coreRecommended }
}
