package com.example.data.filter

import com.example.data.model.CustomFilter
import com.example.data.model.JobAlert
import com.example.data.model.MatchMode
import com.example.data.model.ParsedJobListing
import com.example.data.model.WorkMode
import java.util.Locale

data class FilterEvaluationResult(
    val matchedFilters: List<CustomFilter>,
    val shouldSendInstantPush: Boolean
)

object JobFilterEngine {

    private val hourlyRateRegex = Regex(
        """\$\s*(\d{2,4})(?:\s*[-–to]+\s*\$?\s*(\d{2,4}))?\s*(?:/|\s*per\s*)(?:hr|hour)""",
        RegexOption.IGNORE_CASE
    )
    private val annualSalaryKRegex = Regex(
        """\$\s*(\d{2,3})\s*k(?:\s*[-–to]+\s*\$?\s*(\d{2,3})\s*k)?""",
        RegexOption.IGNORE_CASE
    )
    private val annualSalaryFullRegex = Regex(
        """\$\s*(\d{2,3}),(\d{3})(?:\s*[-–to]+\s*\$?\s*(\d{2,3}),(\d{3}))?""",
        RegexOption.IGNORE_CASE
    )

    fun evaluate(
        sourceId: String,
        listing: ParsedJobListing,
        allFilters: List<CustomFilter>
    ): FilterEvaluationResult {
        val enabledFilters = allFilters.filter { it.enabled }
        if (enabledFilters.isEmpty()) {
            return FilterEvaluationResult(
                matchedFilters = emptyList(),
                shouldSendInstantPush = true
            )
        }

        val combinedText = buildSearchBlob(
            title = listing.title,
            company = listing.company,
            location = listing.location,
            compensation = listing.compensation,
            snippet = listing.rawSnippet
        )
        val extractedHourlyRate = extractEstimatedHourlyRate("$combinedText ${listing.compensation}")

        val matched = enabledFilters.filter { filter ->
            matchesSingleFilter(
                sourceId = sourceId,
                combinedLower = combinedText,
                extractedHourlyRate = extractedHourlyRate,
                filter = filter
            )
        }

        return FilterEvaluationResult(
            matchedFilters = matched,
            shouldSendInstantPush = matched.any { it.instantPush }
        )
    }

    fun matchesAlert(alert: JobAlert, filter: CustomFilter): Boolean {
        val combinedText = buildSearchBlob(
            title = alert.title,
            company = alert.company,
            location = alert.location,
            compensation = alert.compensation,
            snippet = alert.sourceName
        )
        val rate = extractEstimatedHourlyRate("${alert.compensation} ${alert.title}")
        return matchesSingleFilter(
            sourceId = alert.sourceId,
            combinedLower = combinedText,
            extractedHourlyRate = rate,
            filter = filter
        )
    }

    private fun matchesSingleFilter(
        sourceId: String,
        combinedLower: String,
        extractedHourlyRate: Int?,
        filter: CustomFilter
    ): Boolean {
        if (filter.targetSourceIds.isNotEmpty() && sourceId !in filter.targetSourceIds) {
            return false
        }

        val cleanExcludes = filter.excludeKeywords
            .map { it.trim().lowercase(Locale.US) }
            .filter { it.isNotEmpty() }
        if (cleanExcludes.any { keyword -> combinedLower.contains(keyword) }) {
            return false
        }

        val cleanIncludes = filter.includeKeywords
            .map { it.trim().lowercase(Locale.US) }
            .filter { it.isNotEmpty() }
        if (cleanIncludes.isNotEmpty()) {
            val matchedKeywords = when (filter.matchModeEnum()) {
                MatchMode.ANY -> cleanIncludes.any { keyword -> combinedLower.contains(keyword) }
                MatchMode.ALL -> cleanIncludes.all { keyword -> combinedLower.contains(keyword) }
            }
            if (!matchedKeywords) return false
        }

        when (filter.workModeEnum()) {
            WorkMode.ANY -> Unit
            WorkMode.REMOTE -> {
                val remoteSignals = listOf("remote", "anywhere", "global", "worldwide", "wfh", "work from home")
                if (remoteSignals.none { combinedLower.contains(it) }) return false
            }
            WorkMode.CONTRACT -> {
                val contractSignals = listOf("contract", "freelance", "hourly", "/hr", "per hour", "part-time", "project")
                if (contractSignals.none { combinedLower.contains(it) }) return false
            }
            WorkMode.FULL_TIME -> {
                val fullTimeSignals = listOf("full-time", "full time", "permanent", "salaried", "/yr", "annual")
                if (fullTimeSignals.none { combinedLower.contains(it) }) return false
            }
        }

        if (filter.minHourlyRate > 0L) {
            val rate = extractedHourlyRate ?: return false
            if (rate < filter.minHourlyRate) return false
        }

        return true
    }

    fun extractEstimatedHourlyRate(text: String): Int? {
        hourlyRateRegex.find(text)?.let { match ->
            val low = match.groupValues[1].toIntOrNull()
            val high = match.groupValues.getOrNull(2)?.toIntOrNull()
            return high ?: low
        }
        annualSalaryKRegex.find(text)?.let { match ->
            val lowK = match.groupValues[1].toIntOrNull()
            val highK = match.groupValues.getOrNull(2)?.toIntOrNull()
            val annual = (highK ?: lowK)?.times(1000)
            if (annual != null && annual >= 20_000) {
                return annual / 2080
            }
        }
        annualSalaryFullRegex.find(text)?.let { match ->
            val low = "${match.groupValues[1]}${match.groupValues[2]}".toIntOrNull()
            val high = if (match.groupValues[3].isNotEmpty() && match.groupValues[4].isNotEmpty()) {
                "${match.groupValues[3]}${match.groupValues[4]}".toIntOrNull()
            } else null
            val annual = high ?: low
            if (annual != null && annual >= 20_000) {
                return annual / 2080
            }
        }
        return null
    }

    private fun buildSearchBlob(
        title: String,
        company: String,
        location: String,
        compensation: String,
        snippet: String
    ): String {
        return "$title $company $location $compensation $snippet".lowercase(Locale.US)
    }
}
