package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.CustomFilter
import com.example.data.model.JobAlert
import com.example.data.model.MatchMode
import com.example.data.model.MonitoredSource
import com.example.data.model.PresetSource
import com.example.data.model.SourceCategory
import com.example.data.model.SourceStatus
import com.example.data.model.WorkMode
import com.example.data.network.UrlSecurityValidator
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class JobPulseRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    private fun sourcesCollection(uid: String) =
        db.collection("users").document(uid).collection("sources")

    private fun filtersCollection(uid: String) =
        db.collection("users").document(uid).collection("filters")

    private fun alertsCollection(uid: String) =
        db.collection("users").document(uid).collection("alerts")

    // --- REALTIME STREAMS ---

    fun observeSources(userId: String): Flow<List<MonitoredSource>> = flow {
        require(userId.isNotBlank()) { "User ID must not be blank" }
        val path = "users/$userId/sources"
        emitAll(
            sourcesCollection(userId)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        MonitoredSource::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    logStreamErrorIfActive(error, userId, path)
                    throw error
                }
        )
    }

    fun observeFilters(userId: String): Flow<List<CustomFilter>> = flow {
        require(userId.isNotBlank()) { "User ID must not be blank" }
        val path = "users/$userId/filters"
        emitAll(
            filtersCollection(userId)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        CustomFilter::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    logStreamErrorIfActive(error, userId, path)
                    throw error
                }
        )
    }

    fun observeAlerts(userId: String): Flow<List<JobAlert>> = flow {
        require(userId.isNotBlank()) { "User ID must not be blank" }
        val path = "users/$userId/alerts"
        emitAll(
            alertsCollection(userId)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(150)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        JobAlert::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    logStreamErrorIfActive(error, userId, path)
                    throw error
                }
        )
    }

    private fun logStreamErrorIfActive(error: Throwable, userId: String, path: String) {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) return
        val exceptionToLog = firestoreError ?: (error as? Exception)
        if (exceptionToLog != null && auth.currentUser?.uid == userId) {
            handleFirestoreError(exceptionToLog, OperationType.LIST, path)
        }
    }

    // --- ONE-SHOT READS (for background service polling) ---

    suspend fun getEnabledSourcesOnce(): Result<List<MonitoredSource>> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not signed in"))
        val path = "users/$uid/sources"
        return try {
            val snap = sourcesCollection(uid).get().await()
            val sources = snap.toObjects(
                MonitoredSource::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            ).filter { it.enabled }
            Result.success(sources)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path)
            Result.failure(e)
        }
    }

    suspend fun getFiltersOnce(): Result<List<CustomFilter>> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not signed in"))
        val path = "users/$uid/filters"
        return try {
            val snap = filtersCollection(uid).get().await()
            val filters = snap.toObjects(
                CustomFilter::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            )
            Result.success(filters)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path)
            Result.failure(e)
        }
    }

    // --- SOURCES CRUD ---

    suspend fun addSource(
        name: String,
        rawUrl: String,
        category: SourceCategory,
        pollIntervalSeconds: Long
    ): Result<String> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/sources"
        return try {
            val uid = requireUserId()
            val cleanName = name.trim().take(80)
            require(cleanName.isNotEmpty()) { "Website name is required." }
            val validatedUrl = UrlSecurityValidator.normalizeAndValidateSourceUrl(rawUrl).getOrThrow()
            val safeInterval = pollIntervalSeconds.coerceIn(10L, 3600L)

            val docRef = sourcesCollection(uid).document()
            docPath = docRef.path
            val payload = mapOf(
                "userId" to uid,
                "name" to cleanName,
                "url" to validatedUrl,
                "category" to category.name,
                "enabled" to true,
                "pollIntervalSeconds" to safeInterval,
                "lastStatus" to SourceStatus.IDLE.name,
                "lastLatencyMs" to 0L,
                "lastJobCount" to 0L,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun addPresetSources(presets: List<PresetSource>, existingUrls: Set<String>): Result<Int> {
        var added = 0
        for (preset in presets) {
            if (preset.url.lowercase() in existingUrls) continue
            val res = addSource(
                name = preset.name,
                rawUrl = preset.url,
                category = preset.category,
                pollIntervalSeconds = preset.defaultIntervalSeconds
            )
            if (res.isSuccess) added++
        }
        return Result.success(added)
    }

    suspend fun updateSourceConfig(
        sourceId: String,
        name: String,
        rawUrl: String,
        category: SourceCategory,
        enabled: Boolean,
        pollIntervalSeconds: Long
    ): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/sources/$sourceId"
        return try {
            val uid = requireUserId()
            require(sourceId.isNotBlank()) { "Source ID is required." }
            val cleanName = name.trim().take(80)
            require(cleanName.isNotEmpty()) { "Website name is required." }
            val validatedUrl = UrlSecurityValidator.normalizeAndValidateSourceUrl(rawUrl).getOrThrow()
            val safeInterval = pollIntervalSeconds.coerceIn(10L, 3600L)

            val docRef = sourcesCollection(uid).document(sourceId)
            docPath = docRef.path
            val updates = mapOf(
                "name" to cleanName,
                "url" to validatedUrl,
                "category" to category.name,
                "enabled" to enabled,
                "pollIntervalSeconds" to safeInterval,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun toggleSourceEnabled(sourceId: String, enabled: Boolean): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/sources/$sourceId"
        return try {
            val uid = requireUserId()
            require(sourceId.isNotBlank()) { "Source ID is required." }
            val docRef = sourcesCollection(uid).document(sourceId)
            docPath = docRef.path
            docRef.update(
                mapOf(
                    "enabled" to enabled,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateSourceTelemetry(
        sourceId: String,
        status: SourceStatus,
        latencyMs: Long,
        jobCount: Long,
        errorMessage: String?,
        etag: String?,
        lastModified: String?
    ): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/sources/$sourceId"
        return try {
            val uid = requireUserId()
            require(sourceId.isNotBlank()) { "Source ID is required." }
            val docRef = sourcesCollection(uid).document(sourceId)
            docPath = docRef.path

            val rawUpdates = mutableMapOf<String, Any?>(
                "lastStatus" to status.name,
                "lastLatencyMs" to latencyMs.coerceIn(0L, 600_000L),
                "lastJobCount" to jobCount.coerceIn(0L, 100_000L),
                "lastError" to (errorMessage?.take(300) ?: ""),
                "etag" to etag?.take(300),
                "lastModifiedHeader" to lastModified?.take(200),
                "lastCheckedAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            @Suppress("UNCHECKED_CAST")
            val cleanUpdates = rawUpdates.filterValues { it != null } as Map<String, Any>
            docRef.update(cleanUpdates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteSource(sourceId: String): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/sources/$sourceId"
        return try {
            val uid = requireUserId()
            require(sourceId.isNotBlank()) { "Source ID is required." }
            val docRef = sourcesCollection(uid).document(sourceId)
            docPath = docRef.path
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    // --- CUSTOM FILTERS CRUD ---

    suspend fun addFilter(
        name: String,
        matchMode: MatchMode,
        includeKeywords: List<String>,
        excludeKeywords: List<String>,
        workMode: WorkMode,
        minHourlyRate: Long,
        targetSourceIds: List<String>,
        instantPush: Boolean
    ): Result<String> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/filters"
        return try {
            val uid = requireUserId()
            val cleanName = name.trim().take(80)
            require(cleanName.isNotEmpty()) { "Filter name is required." }

            val docRef = filtersCollection(uid).document()
            docPath = docRef.path
            val payload = mapOf(
                "userId" to uid,
                "name" to cleanName,
                "enabled" to true,
                "matchMode" to matchMode.name,
                "includeKeywords" to includeKeywords.map { it.trim() }.filter { it.isNotEmpty() }.take(25),
                "excludeKeywords" to excludeKeywords.map { it.trim() }.filter { it.isNotEmpty() }.take(25),
                "workMode" to workMode.name,
                "minHourlyRate" to minHourlyRate.coerceIn(0L, 10_000L),
                "targetSourceIds" to targetSourceIds.filter { it.isNotBlank() }.take(30),
                "instantPush" to instantPush,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateFilter(
        filterId: String,
        name: String,
        enabled: Boolean,
        matchMode: MatchMode,
        includeKeywords: List<String>,
        excludeKeywords: List<String>,
        workMode: WorkMode,
        minHourlyRate: Long,
        targetSourceIds: List<String>,
        instantPush: Boolean
    ): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/filters/$filterId"
        return try {
            val uid = requireUserId()
            require(filterId.isNotBlank()) { "Filter ID is required." }
            val cleanName = name.trim().take(80)
            require(cleanName.isNotEmpty()) { "Filter name is required." }

            val docRef = filtersCollection(uid).document(filterId)
            docPath = docRef.path
            val updates = mapOf(
                "name" to cleanName,
                "enabled" to enabled,
                "matchMode" to matchMode.name,
                "includeKeywords" to includeKeywords.map { it.trim() }.filter { it.isNotEmpty() }.take(25),
                "excludeKeywords" to excludeKeywords.map { it.trim() }.filter { it.isNotEmpty() }.take(25),
                "workMode" to workMode.name,
                "minHourlyRate" to minHourlyRate.coerceIn(0L, 10_000L),
                "targetSourceIds" to targetSourceIds.filter { it.isNotBlank() }.take(30),
                "instantPush" to instantPush,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun toggleFilterEnabled(filterId: String, enabled: Boolean): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/filters/$filterId"
        return try {
            val uid = requireUserId()
            require(filterId.isNotBlank()) { "Filter ID is required." }
            val docRef = filtersCollection(uid).document(filterId)
            docPath = docRef.path
            docRef.update(
                mapOf(
                    "enabled" to enabled,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteFilter(filterId: String): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/filters/$filterId"
        return try {
            val uid = requireUserId()
            require(filterId.isNotBlank()) { "Filter ID is required." }
            val docRef = filtersCollection(uid).document(filterId)
            docPath = docRef.path
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    // --- JOB ALERTS CRUD ---

    suspend fun saveDetectedAlert(
        sourceId: String,
        sourceName: String,
        fingerprint: String,
        title: String,
        company: String,
        location: String,
        compensation: String,
        jobUrl: String,
        matchedFilterNames: List<String>,
        detectionLatencyMs: Long,
        freshAlert: Boolean
    ): Result<String> {
        val docId = "job_${fingerprint.take(32)}"
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/alerts/$docId"
        return try {
            val uid = requireUserId()
            if (!UrlSecurityValidator.isSafeExternalJobUrl(jobUrl)) {
                return Result.failure(IllegalArgumentException("Unsafe job URL skipped."))
            }
            val docRef = alertsCollection(uid).document(docId)
            docPath = docRef.path

            // Skip overwriting if already exists to preserve createdAt and bookmark/applied state
            val existingSnap = docRef.get().await()
            if (existingSnap.exists()) {
                return Result.success(docId)
            }

            val payload = mapOf(
                "userId" to uid,
                "sourceId" to sourceId.take(128),
                "sourceName" to sourceName.take(80),
                "fingerprint" to fingerprint.take(128),
                "title" to title.trim().ifBlank { "Untitled Role" }.take(200),
                "company" to company.trim().ifBlank { sourceName }.take(100),
                "location" to location.trim().take(120),
                "compensation" to compensation.trim().take(80),
                "jobUrl" to jobUrl.trim().take(1000),
                "matchedFilterNames" to matchedFilterNames.take(20),
                "detectionLatencyMs" to detectionLatencyMs.coerceIn(0L, 600_000L),
                "freshAlert" to freshAlert,
                "bookmarked" to false,
                "read" to !freshAlert,
                "applied" to false,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(docId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateAlertState(
        alertId: String,
        bookmarked: Boolean? = null,
        read: Boolean? = null,
        applied: Boolean? = null
    ): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/alerts/$alertId"
        return try {
            val uid = requireUserId()
            require(alertId.isNotBlank()) { "Alert ID is required." }
            val docRef = alertsCollection(uid).document(alertId)
            docPath = docRef.path

            val rawUpdates = mutableMapOf<String, Any?>(
                "bookmarked" to bookmarked,
                "read" to read,
                "applied" to applied,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            @Suppress("UNCHECKED_CAST")
            val cleanUpdates = rawUpdates.filterValues { it != null } as Map<String, Any>
            docRef.update(cleanUpdates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteAlert(alertId: String): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/alerts/$alertId"
        return try {
            val uid = requireUserId()
            require(alertId.isNotBlank()) { "Alert ID is required." }
            val docRef = alertsCollection(uid).document(alertId)
            docPath = docRef.path
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }
}
