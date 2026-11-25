package com.example.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.data.repository.JobPulseRepository
import com.example.data.repository.RadarPreferencesStore
import com.example.notifications.JobNotificationManager
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class JobMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorLoopJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRadarService()
            return START_NOT_STICKY
        }

        val initialNotification = JobNotificationManager.buildRadarForegroundNotification(
            context = this,
            activeSiteCount = 0,
            cadenceSeconds = 15,
            lastLatencyMs = 0L
        )
        startForegroundCompat(initialNotification)
        JobMonitorCoordinator.setServiceRunning(true)

        if (monitorLoopJob?.isActive != true) {
            startMonitorLoop()
        }

        return START_STICKY
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            JobNotificationManager.FOREGROUND_NOTIFICATION_ID,
            notification,
            serviceType
        )
    }

    private fun startMonitorLoop() {
        val appContext = applicationContext
        val repository = JobPulseRepository(appContext)
        val prefsStore = RadarPreferencesStore(appContext)

        monitorLoopJob = serviceScope.launch {
            while (isActive) {
                try {
                    val currentUser = Firebase.auth.currentUser
                    if (currentUser == null) {
                        Log.i(TAG, "No authenticated user; stopping Live Radar service.")
                        prefsStore.setLiveRadarEnabled(false)
                        stopRadarService()
                        return@launch
                    }

                    val prefs = prefsStore.preferencesFlow.first()
                    val sources = repository.getEnabledSourcesOnce().getOrDefault(emptyList())
                    val filters = repository.getFiltersOnce().getOrDefault(emptyList())

                    val shortestIntervalSeconds = sources
                        .minOfOrNull { it.pollIntervalSeconds.toInt() }
                        ?.coerceIn(10, 3600)
                        ?: prefs.defaultPollIntervalSeconds.coerceIn(10, 3600)

                    if (sources.isNotEmpty()) {
                        JobMonitorCoordinator.scanSourcesNow(
                            context = appContext,
                            repository = repository,
                            prefsStore = prefsStore,
                            sources = sources,
                            filters = filters,
                            bypassCache = false
                        )
                    }

                    val updatedNotif = JobNotificationManager.buildRadarForegroundNotification(
                        context = appContext,
                        activeSiteCount = sources.size,
                        cadenceSeconds = shortestIntervalSeconds,
                        lastLatencyMs = JobMonitorCoordinator.telemetry.value.avgLatencyMs
                    )
                    startForegroundCompat(updatedNotif)

                    delay(shortestIntervalSeconds * 1000L)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Radar loop cycle encountered transient error", e)
                    delay(15_000L)
                }
            }
        }
    }

    private fun stopRadarService() {
        monitorLoopJob?.cancel()
        monitorLoopJob = null
        JobMonitorCoordinator.setServiceRunning(false)
        serviceScope.launch {
            RadarPreferencesStore(applicationContext).setLiveRadarEnabled(false)
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        JobMonitorCoordinator.setServiceRunning(false)
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "JobMonitorService"
        const val ACTION_START = "com.example.action.START_RADAR"
        const val ACTION_STOP = "com.example.action.STOP_RADAR"

        fun start(context: Context) {
            val intent = Intent(context, JobMonitorService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            JobMonitorCoordinator.setServiceRunning(false)
            context.stopService(Intent(context, JobMonitorService::class.java))
        }
    }
}
