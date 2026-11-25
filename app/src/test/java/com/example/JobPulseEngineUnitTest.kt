package com.example

import com.example.data.filter.JobFilterEngine
import com.example.data.model.CustomFilter
import com.example.data.model.MatchMode
import com.example.data.model.ParsedJobListing
import com.example.data.model.WorkMode
import com.example.data.network.UrlSecurityValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JobPulseEngineUnitTest {

    @Test
    fun urlValidator_allowsPublicHttpsAndBlocksUnsafeTargets() {
        assertTrue(UrlSecurityValidator.normalizeAndValidateSourceUrl("https://work.mercor.com/explore").isSuccess)
        assertTrue(UrlSecurityValidator.normalizeAndValidateSourceUrl("www.micro1.ai/jobs").isSuccess)
        assertTrue(UrlSecurityValidator.normalizeAndValidateSourceUrl("http://insecure.example.com/jobs").isFailure)
        assertTrue(UrlSecurityValidator.normalizeAndValidateSourceUrl("https://localhost/jobs").isFailure)
        assertTrue(UrlSecurityValidator.normalizeAndValidateSourceUrl("https://127.0.0.1/jobs").isFailure)
        assertTrue(UrlSecurityValidator.normalizeAndValidateSourceUrl("https://169.254.169.254/latest").isFailure)
        assertTrue(UrlSecurityValidator.normalizeAndValidateSourceUrl("https://192.168.1.5/admin").isFailure)
        assertFalse(UrlSecurityValidator.isSafeExternalJobUrl("javascript:alert(1)"))
        assertTrue(UrlSecurityValidator.isSafeExternalJobUrl("https://www.turing.com/jobs/ai-engineer"))
    }

    @Test
    fun filterEngine_matchesKeywordsRateAndExcludesNegativeTerms() {
        val filter = CustomFilter(
            id = "f1",
            name = "High-Pay Remote AI",
            enabled = true,
            matchMode = MatchMode.ANY.name,
            includeKeywords = listOf("Kotlin", "LLM", "AI Trainer"),
            excludeKeywords = listOf("Intern", "Unpaid"),
            workMode = WorkMode.REMOTE.name,
            minHourlyRate = 60L,
            targetSourceIds = listOf("src_mercor"),
            instantPush = true
        )

        val matchingJob = ParsedJobListing(
            fingerprint = "fp1",
            title = "Senior LLM Evaluation Engineer",
            company = "Mercor",
            location = "Remote (Global)",
            compensation = "$85/hr",
            jobUrl = "https://work.mercor.com/jobs/llm-eval"
        )
        val evalMatch = JobFilterEngine.evaluate("src_mercor", matchingJob, listOf(filter))
        assertEquals(1, evalMatch.matchedFilters.size)
        assertTrue(evalMatch.shouldSendInstantPush)

        val excludedJob = matchingJob.copy(title = "LLM Evaluation Intern")
        val evalExcluded = JobFilterEngine.evaluate("src_mercor", excludedJob, listOf(filter))
        assertTrue(evalExcluded.matchedFilters.isEmpty())
        assertFalse(evalExcluded.shouldSendInstantPush)

        val lowRateJob = matchingJob.copy(compensation = "$35/hr")
        val evalLowRate = JobFilterEngine.evaluate("src_mercor", lowRateJob, listOf(filter))
        assertTrue(evalLowRate.matchedFilters.isEmpty())
    }
}
