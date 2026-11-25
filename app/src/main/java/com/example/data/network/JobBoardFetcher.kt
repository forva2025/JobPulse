package com.example.data.network

import android.os.SystemClock
import com.example.data.model.MonitoredSource
import com.example.data.model.SourceScanOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.TimeUnit

class JobBoardFetcher {

    private val safeDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val resolved = Dns.SYSTEM.lookup(hostname)
            val safe = resolved.filter { UrlSecurityValidator.isResolvedAddressSafe(it) }
            if (safe.isEmpty()) {
                throw IOException("Blocked non-public network destination: $hostname")
            }
            return safe
        }
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .dns(safeDns)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun fetchSource(source: MonitoredSource, bypassCache: Boolean = false): SourceScanOutcome =
        withContext(Dispatchers.IO) {
            val startMs = SystemClock.elapsedRealtime()
            val validated = UrlSecurityValidator.normalizeAndValidateSourceUrl(source.url)
            if (validated.isFailure) {
                return@withContext SourceScanOutcome(
                    sourceId = source.id,
                    sourceName = source.name,
                    httpCode = 0,
                    notModified = false,
                    latencyMs = SystemClock.elapsedRealtime() - startMs,
                    jobs = emptyList(),
                    etag = source.etag,
                    lastModified = source.lastModifiedHeader,
                    errorMessage = validated.exceptionOrNull()?.message ?: "Invalid website URL"
                )
            }

            val fetchUrl = resolveSmartApiEndpoint(validated.getOrThrow())
            val reqBuilder = Request.Builder()
                .url(fetchUrl)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36 JobPulseRadar/1.0"
                )
                .header("Accept", "application/json, application/rss+xml, application/xml, text/html;q=0.9,*/*;q=0.8")
                .header("Cache-Control", "no-cache")

            if (!bypassCache) {
                source.etag?.takeIf { it.isNotBlank() }?.let {
                    reqBuilder.header("If-None-Match", it)
                }
                source.lastModifiedHeader?.takeIf { it.isNotBlank() }?.let {
                    reqBuilder.header("If-Modified-Since", it)
                }
            }

            try {
                client.newCall(reqBuilder.build()).execute().use { response ->
                    val latency = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
                    val newEtag = response.header("ETag") ?: source.etag
                    val newLastModified = response.header("Last-Modified") ?: source.lastModifiedHeader

                    if (response.code == 304) {
                        return@withContext SourceScanOutcome(
                            sourceId = source.id,
                            sourceName = source.name,
                            httpCode = 304,
                            notModified = true,
                            latencyMs = latency,
                            jobs = emptyList(),
                            etag = newEtag,
                            lastModified = newLastModified
                        )
                    }

                    if (!response.isSuccessful) {
                        return@withContext SourceScanOutcome(
                            sourceId = source.id,
                            sourceName = source.name,
                            httpCode = response.code,
                            notModified = false,
                            latencyMs = latency,
                            jobs = emptyList(),
                            etag = newEtag,
                            lastModified = newLastModified,
                            errorMessage = "HTTP ${response.code} ${response.message}".trim()
                        )
                    }

                    val bodyText = readBoundedBody(response.body, MAX_BODY_BYTES)
                    val contentType = response.header("Content-Type").orEmpty()
                    val jobs = JobContentParser.parse(
                        sourceId = source.id,
                        sourceName = source.name,
                        sourceUrl = source.url,
                        contentType = contentType,
                        body = bodyText
                    )

                    SourceScanOutcome(
                        sourceId = source.id,
                        sourceName = source.name,
                        httpCode = response.code,
                        notModified = false,
                        latencyMs = latency,
                        jobs = jobs,
                        etag = newEtag,
                        lastModified = newLastModified
                    )
                }
            } catch (e: Exception) {
                val latency = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
                SourceScanOutcome(
                    sourceId = source.id,
                    sourceName = source.name,
                    httpCode = 0,
                    notModified = false,
                    latencyMs = latency,
                    jobs = emptyList(),
                    etag = source.etag,
                    lastModified = source.lastModifiedHeader,
                    errorMessage = e.localizedMessage?.take(250) ?: "Network error"
                )
            }
        }

    private fun readBoundedBody(body: okhttp3.ResponseBody?, maxBytes: Long): String {
        if (body == null) return ""
        val source = body.source()
        source.request(maxBytes)
        val buffer = source.buffer.clone()
        val byteCount = minOf(buffer.size, maxBytes)
        return buffer.readUtf8(byteCount)
    }

    /**
     * Automatically upgrades popular ATS board URLs (Greenhouse, Lever, Ashby)
     * to their ultra-fast public JSON endpoints for sub-second low-latency diffing.
     */
    private fun resolveSmartApiEndpoint(url: String): String {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return url
        }
        val host = uri.host?.lowercase() ?: return url
        val pathSegments = uri.path.orEmpty().split('/').filter { it.isNotBlank() }

        return when {
            host == "boards.greenhouse.io" && pathSegments.isNotEmpty() -> {
                "https://boards-api.greenhouse.io/v1/boards/${pathSegments[0]}/jobs"
            }
            host == "jobs.lever.co" && pathSegments.isNotEmpty() -> {
                "https://api.lever.co/v0/postings/${pathSegments[0]}?mode=json"
            }
            host == "jobs.ashbyhq.com" && pathSegments.isNotEmpty() -> {
                "https://api.ashbyhq.com/posting-api/job-board/${pathSegments[0]}"
            }
            else -> url
        }
    }

    companion object {
        private const val MAX_BODY_BYTES = 2L * 1024L * 1024L // 2 MB cap
    }
}
