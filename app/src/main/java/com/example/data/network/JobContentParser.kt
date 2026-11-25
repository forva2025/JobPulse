package com.example.data.network

import android.util.Xml
import com.example.data.model.ParsedJobListing
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

object JobContentParser {

    private val scriptLdJsonRegex = Regex(
        """<script[^>]*type=["']application/ld\+json["'][^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val nextDataRegex = Regex(
        """<script[^>]*id=["']__NEXT_DATA__["'][^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val anchorRegex = Regex(
        """<a\s+[^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val htmlTagRegex = Regex("<[^>]+>")
    private val whitespaceRegex = Regex("\\s+")
    private val compensationRegex = Regex(
        """(\$\s?\d{2,4}(?:\s?[-–]\s?\$?\d{2,4})?\s?(?:/hr|/hour|per hour|k(?:\s?[-–]\s?\$?\d{2,3}k)?|,\d{3}(?:\s?[-–]\s?\$?\d{2,3},\d{3})?))""",
        RegexOption.IGNORE_CASE
    )
    private val jobPathHints = listOf(
        "/job/", "/jobs/", "/role/", "/roles/", "/career/", "/careers/",
        "/position/", "/positions/", "/listing/", "/listings/",
        "/opportunity/", "/opportunities/", "/opening/", "/openings/",
        "/explore/", "/apply/", "/posting/", "/postings/", "/work/"
    )
    private val ignoreLinkText = setOf(
        "jobs", "careers", "home", "about", "login", "sign in", "sign up", "apply",
        "privacy", "terms", "blog", "contact", "faq", "help", "support", "view all",
        "see all", "learn more", "read more", "explore", "back", "next", "previous"
    )

    fun parse(
        sourceId: String,
        sourceName: String,
        sourceUrl: String,
        contentType: String,
        body: String
    ): List<ParsedJobListing> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()

        val results = LinkedHashMap<String, ParsedJobListing>()

        // 1. Direct JSON API responses (Remotive, RemoteOK, Greenhouse, Lever, Ashby, custom JSON)
        if (contentType.contains("json", ignoreCase = true) || trimmed.startsWith("{") || trimmed.startsWith("[")) {
            parseJsonApi(sourceId, sourceName, sourceUrl, trimmed).forEach { job ->
                results.putIfAbsent(job.fingerprint, job)
            }
            if (results.isNotEmpty()) return results.values.take(60)
        }

        // 2. RSS / Atom XML feeds
        if (contentType.contains("xml", ignoreCase = true) ||
            contentType.contains("rss", ignoreCase = true) ||
            trimmed.startsWith("<?xml") ||
            trimmed.contains("<rss", ignoreCase = true) ||
            trimmed.contains("<feed", ignoreCase = true)
        ) {
            parseRssOrAtom(sourceId, sourceName, sourceUrl, trimmed).forEach { job ->
                results.putIfAbsent(job.fingerprint, job)
            }
            if (results.isNotEmpty()) return results.values.take(60)
        }

        // 3. JSON-LD JobPosting embedded in HTML
        for (match in scriptLdJsonRegex.findAll(trimmed)) {
            val jsonText = match.groupValues[1].trim()
            parseJsonLd(sourceId, sourceName, sourceUrl, jsonText).forEach { job ->
                results.putIfAbsent(job.fingerprint, job)
            }
        }

        // 4. Next.js __NEXT_DATA__ JSON (common on Mercor, micro1, Turing, modern career sites)
        nextDataRegex.find(trimmed)?.let { match ->
            val nextJson = match.groupValues[1].trim()
            parseNextDataJobs(sourceId, sourceName, sourceUrl, nextJson).forEach { job ->
                results.putIfAbsent(job.fingerprint, job)
            }
        }

        // 5. Smart HTML anchor extraction fallback for SSR career portals
        if (results.size < 5) {
            parseHtmlAnchors(sourceId, sourceName, sourceUrl, trimmed).forEach { job ->
                results.putIfAbsent(job.fingerprint, job)
            }
        }

        return results.values.take(60)
    }

    private fun parseJsonApi(
        sourceId: String,
        sourceName: String,
        sourceUrl: String,
        raw: String
    ): List<ParsedJobListing> {
        val output = mutableListOf<ParsedJobListing>()
        try {
            if (raw.startsWith("[")) {
                val arr = JSONArray(raw)
                extractJobObjectsFromArray(arr, sourceId, sourceName, sourceUrl, output)
            } else {
                val root = JSONObject(raw)
                val arrayKeys = listOf("jobs", "postings", "data", "results", "items", "openings", "listings", "roles")
                for (key in arrayKeys) {
                    val arr = root.optJSONArray(key)
                    if (arr != null && arr.length() > 0) {
                        extractJobObjectsFromArray(arr, sourceId, sourceName, sourceUrl, output)
                    }
                }
            }
        } catch (_: Exception) {
            // Fall through gracefully
        }
        return output
    }

    private fun extractJobObjectsFromArray(
        arr: JSONArray,
        sourceId: String,
        sourceName: String,
        sourceUrl: String,
        output: MutableList<ParsedJobListing>
    ) {
        val limit = minOf(arr.length(), 80)
        for (i in 0 until limit) {
            val obj = arr.optJSONObject(i) ?: continue
            val title = firstNonBlank(
                obj.optString("title"),
                obj.optString("position"),
                obj.optString("text"),
                obj.optString("name"),
                obj.optString("roleTitle")
            ) ?: continue
            if (title.length < 3 || title.equals("legal", ignoreCase = true)) continue

            val company = firstNonBlank(
                obj.optString("company_name"),
                obj.optString("company"),
                obj.optJSONObject("hiringOrganization")?.optString("name"),
                sourceName
            ) ?: sourceName

            val locationObj = obj.optJSONObject("location")
            val categoriesObj = obj.optJSONObject("categories")
            val location = firstNonBlank(
                obj.optString("candidate_required_location"),
                obj.optString("locationName"),
                locationObj?.optString("name"),
                categoriesObj?.optString("location"),
                if (obj.opt("location") is String) obj.optString("location") else "",
                if (obj.optBoolean("isRemote", false)) "Remote" else ""
            ).orEmpty()

            val compensation = firstNonBlank(
                obj.optString("salary"),
                obj.optString("compensation"),
                obj.optString("rate"),
                formatMinMaxSalary(obj)
            ) ?: extractCompensation(title + " " + obj.optString("description"))

            val rawUrl = firstNonBlank(
                obj.optString("url"),
                obj.optString("absolute_url"),
                obj.optString("hostedUrl"),
                obj.optString("jobUrl"),
                obj.optString("applyUrl"),
                obj.optString("apply_url")
            ) ?: sourceUrl

            val resolvedUrl = resolveUrl(sourceUrl, rawUrl)
            if (!UrlSecurityValidator.isSafeExternalJobUrl(resolvedUrl)) continue

            val snippet = cleanText(
                listOf(
                    obj.optString("job_type"),
                    obj.optString("employmentType"),
                    obj.optString("category"),
                    obj.optString("department"),
                    categoriesObj?.optString("commitment").orEmpty(),
                    obj.optString("tags")
                ).joinToString(" ")
            )

            output.add(
                ParsedJobListing(
                    fingerprint = computeFingerprint(sourceId, resolvedUrl, title),
                    title = cleanText(title).take(190),
                    company = cleanText(company).take(95),
                    location = cleanText(location).take(115),
                    compensation = cleanText(compensation).take(75),
                    jobUrl = resolvedUrl.take(990),
                    rawSnippet = snippet.take(300)
                )
            )
        }
    }

    private fun parseJsonLd(
        sourceId: String,
        sourceName: String,
        sourceUrl: String,
        jsonText: String
    ): List<ParsedJobListing> {
        val output = mutableListOf<ParsedJobListing>()
        try {
            val nodes = mutableListOf<JSONObject>()
            if (jsonText.startsWith("[")) {
                val arr = JSONArray(jsonText)
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { nodes.add(it) }
            } else if (jsonText.startsWith("{")) {
                val obj = JSONObject(jsonText)
                val graph = obj.optJSONArray("@graph")
                if (graph != null) {
                    for (i in 0 until graph.length()) graph.optJSONObject(i)?.let { nodes.add(it) }
                } else {
                    nodes.add(obj)
                }
            }

            for (node in nodes) {
                val type = node.optString("@type")
                if (!type.equals("JobPosting", ignoreCase = true)) continue
                val title = cleanText(node.optString("title"))
                if (title.length < 3) continue
                val company = cleanText(
                    node.optJSONObject("hiringOrganization")?.optString("name").orEmpty()
                ).ifBlank { sourceName }
                val location = extractJsonLdLocation(node)
                val compensation = extractJsonLdSalary(node)
                val url = resolveUrl(sourceUrl, node.optString("url").ifBlank { sourceUrl })
                if (!UrlSecurityValidator.isSafeExternalJobUrl(url)) continue
                val employment = cleanText(node.optString("employmentType"))

                output.add(
                    ParsedJobListing(
                        fingerprint = computeFingerprint(sourceId, url, title),
                        title = title.take(190),
                        company = company.take(95),
                        location = location.take(115),
                        compensation = compensation.take(75),
                        jobUrl = url.take(990),
                        rawSnippet = employment.take(200)
                    )
                )
            }
        } catch (_: Exception) {
            // Ignore malformed JSON-LD block
        }
        return output
    }

    private fun parseNextDataJobs(
        sourceId: String,
        sourceName: String,
        sourceUrl: String,
        nextJson: String
    ): List<ParsedJobListing> {
        val output = mutableListOf<ParsedJobListing>()
        try {
            val root = JSONObject(nextJson)
            walkJsonForJobs(root, sourceId, sourceName, sourceUrl, output, depth = 0)
        } catch (_: Exception) {
            // Ignore
        }
        return output
    }

    private fun walkJsonForJobs(
        node: Any?,
        sourceId: String,
        sourceName: String,
        sourceUrl: String,
        output: MutableList<ParsedJobListing>,
        depth: Int
    ) {
        if (depth > 7 || output.size >= 50 || node == null) return
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext() && output.size < 50) {
                    val key = keys.next()
                    val value = node.opt(key)
                    val lowerKey = key.lowercase(Locale.US)
                    if (value is JSONArray && (
                            lowerKey.contains("job") ||
                                lowerKey.contains("role") ||
                                lowerKey.contains("listing") ||
                                lowerKey.contains("position") ||
                                lowerKey.contains("opening") ||
                                lowerKey.contains("opportunit")
                            )
                    ) {
                        extractJobObjectsFromArray(value, sourceId, sourceName, sourceUrl, output)
                    } else {
                        walkJsonForJobs(value, sourceId, sourceName, sourceUrl, output, depth + 1)
                    }
                }
            }
            is JSONArray -> {
                val limit = minOf(node.length(), 20)
                for (i in 0 until limit) {
                    walkJsonForJobs(node.opt(i), sourceId, sourceName, sourceUrl, output, depth + 1)
                }
            }
        }
    }

    private fun parseRssOrAtom(
        sourceId: String,
        sourceName: String,
        sourceUrl: String,
        xmlText: String
    ): List<ParsedJobListing> {
        val output = mutableListOf<ParsedJobListing>()
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(xmlText))

            var inItem = false
            var title = ""
            var link = ""
            var description = ""
            var eventType = parser.eventType

            while (eventType != XmlPullParser.END_DOCUMENT && output.size < 50) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name.lowercase(Locale.US)
                        if (tag == "item" || tag == "entry") {
                            inItem = true
                            title = ""
                            link = ""
                            description = ""
                        } else if (inItem) {
                            when (tag) {
                                "title" -> title = safeNextText(parser)
                                "link" -> {
                                    val href = parser.getAttributeValue(null, "href")
                                    link = if (!href.isNullOrBlank()) href else safeNextText(parser)
                                }
                                "description", "summary", "content" -> description = safeNextText(parser)
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val tag = parser.name.lowercase(Locale.US)
                        if ((tag == "item" || tag == "entry") && inItem) {
                            inItem = false
                            val cleanTitle = cleanText(title)
                            val resolvedLink = resolveUrl(sourceUrl, link.ifBlank { sourceUrl })
                            if (cleanTitle.length >= 4 && UrlSecurityValidator.isSafeExternalJobUrl(resolvedLink)) {
                                val cleanDesc = cleanText(description)
                                val comp = extractCompensation("$cleanTitle $cleanDesc")
                                val loc = if ("remote" in "$cleanTitle $cleanDesc".lowercase(Locale.US)) "Remote" else ""
                                output.add(
                                    ParsedJobListing(
                                        fingerprint = computeFingerprint(sourceId, resolvedLink, cleanTitle),
                                        title = cleanTitle.take(190),
                                        company = sourceName.take(95),
                                        location = loc.take(115),
                                        compensation = comp.take(75),
                                        jobUrl = resolvedLink.take(990),
                                        rawSnippet = cleanDesc.take(260)
                                    )
                                )
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (_: Exception) {
            // Gracefully ignore malformed XML
        }
        return output
    }

    private fun parseHtmlAnchors(
        sourceId: String,
        sourceName: String,
        sourceUrl: String,
        html: String
    ): List<ParsedJobListing> {
        val output = mutableListOf<ParsedJobListing>()
        val seenUrls = HashSet<String>()

        for (match in anchorRegex.findAll(html)) {
            if (output.size >= 45) break
            val href = match.groupValues[1].trim()
            if (href.isEmpty() || href.startsWith("#") || href.startsWith("mailto:") || href.startsWith("javascript:")) {
                continue
            }
            val resolved = resolveUrl(sourceUrl, href)
            if (!UrlSecurityValidator.isSafeExternalJobUrl(resolved)) continue
            if (resolved == sourceUrl || !seenUrls.add(resolved)) continue

            val lowerHref = resolved.lowercase(Locale.US)
            val looksLikeJobLink = jobPathHints.any { lowerHref.contains(it) }
            if (!looksLikeJobLink) continue

            val rawInner = cleanText(match.groupValues[2])
            if (rawInner.length < 5 || rawInner.length > 220) continue
            if (rawInner.lowercase(Locale.US) in ignoreLinkText) continue

            val comp = extractCompensation(rawInner)
            val loc = when {
                rawInner.contains("remote", ignoreCase = true) -> "Remote"
                rawInner.contains("global", ignoreCase = true) -> "Global"
                rawInner.contains("hybrid", ignoreCase = true) -> "Hybrid"
                else -> ""
            }
            val cleanedTitle = rawInner.replace(comp, "").trim().trim('-', '|', '·', ',').ifBlank { rawInner }

            output.add(
                ParsedJobListing(
                    fingerprint = computeFingerprint(sourceId, resolved, cleanedTitle),
                    title = cleanedTitle.take(190),
                    company = sourceName.take(95),
                    location = loc.take(115),
                    compensation = comp.take(75),
                    jobUrl = resolved.take(990),
                    rawSnippet = rawInner.take(250)
                )
            )
        }
        return output
    }

    private fun safeNextText(parser: XmlPullParser): String {
        return try {
            parser.nextText().orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractJsonLdLocation(node: JSONObject): String {
        if (node.optString("jobLocationType").equals("TELECOMMUTE", ignoreCase = true)) {
            return "Remote"
        }
        val locObj = node.optJSONObject("jobLocation")
        val addr = locObj?.optJSONObject("address")
        val parts = listOfNotNull(
            addr?.optString("addressLocality")?.takeIf { it.isNotBlank() },
            addr?.optString("addressRegion")?.takeIf { it.isNotBlank() },
            addr?.optString("addressCountry")?.takeIf { it.isNotBlank() }
        )
        return parts.joinToString(", ")
    }

    private fun extractJsonLdSalary(node: JSONObject): String {
        val baseSalary = node.optJSONObject("baseSalary") ?: return ""
        val value = baseSalary.optJSONObject("value") ?: return ""
        val currency = baseSalary.optString("currency").ifBlank { "USD" }
        val symbol = if (currency == "USD") "$" else "$currency "
        val min = value.optDouble("minValue", -1.0)
        val max = value.optDouble("maxValue", -1.0)
        val unit = value.optString("unitText").lowercase(Locale.US)
        val suffix = when {
            unit.contains("hour") -> "/hr"
            unit.contains("year") -> "/yr"
            else -> ""
        }
        return when {
            min > 0 && max > 0 -> "$symbol${min.toInt()}-${max.toInt()}$suffix"
            min > 0 -> "$symbol${min.toInt()}$suffix"
            max > 0 -> "$symbol${max.toInt()}$suffix"
            else -> ""
        }
    }

    private fun formatMinMaxSalary(obj: JSONObject): String {
        val min = obj.optInt("salary_min", 0)
        val max = obj.optInt("salary_max", 0)
        return when {
            min > 0 && max > 0 -> "$$min - $$max"
            min > 0 -> "$$min+"
            max > 0 -> "Up to $$max"
            else -> ""
        }
    }

    private fun extractCompensation(text: String): String {
        return compensationRegex.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    private fun resolveUrl(baseUrl: String, candidate: String): String {
        return try {
            URI(baseUrl).resolve(candidate.trim()).toString()
        } catch (_: Exception) {
            candidate.trim()
        }
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }?.trim()

    private fun cleanText(input: String): String {
        val noTags = htmlTagRegex.replace(input, " ")
        val decoded = noTags
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
        return whitespaceRegex.replace(decoded, " ").trim()
    }

    fun computeFingerprint(sourceId: String, jobUrl: String, title: String): String {
        val normalized = "${sourceId.trim()}|${jobUrl.trim().lowercase(Locale.US)}|${title.trim().lowercase(Locale.US)}"
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }
}
