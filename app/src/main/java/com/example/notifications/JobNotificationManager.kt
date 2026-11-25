package com.example.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.network.UrlSecurityValidator
import com.example.service.JobMonitorService

object JobNotificationManager {

    const val CHANNEL_INSTANT_ALERTS = "jobpulse_instant_alerts"
    const val CHANNEL_RADAR_SERVICE = "jobpulse_radar_service"
    const val FOREGROUND_NOTIFICATION_ID = 1001
    private const val TEST_NOTIFICATION_ID = 1002

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val alertChannel = NotificationChannel(
            CHANNEL_INSTANT_ALERTS,
            context.getString(R.string.notif_channel_alerts_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notif_channel_alerts_desc)
            enableVibration(true)
            vibrationPattern = longArrayOf(0L, 180L, 80L, 220L)
            enableLights(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(soundUri, audioAttributes)
        }

        val serviceChannel = NotificationChannel(
            CHANNEL_RADAR_SERVICE,
            context.getString(R.string.notif_channel_radar_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notif_channel_radar_desc)
            setShowBadge(false)
        }

        manager.createNotificationChannel(alertChannel)
        manager.createNotificationChannel(serviceChannel)
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    fun buildRadarForegroundNotification(
        context: Context,
        activeSiteCount: Int,
        cadenceSeconds: Int,
        lastLatencyMs: Long
    ): Notification {
        ensureChannels(context)

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            context,
            2001,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(context, JobMonitorService::class.java).apply {
            action = JobMonitorService.ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            context,
            2002,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val subtitle = buildString {
            append("Watching $activeSiteCount site")
            if (activeSiteCount != 1) append("s")
            append(" · every ${cadenceSeconds}s")
            if (lastLatencyMs > 0L) append(" · ${lastLatencyMs}ms")
        }

        return NotificationCompat.Builder(context, CHANNEL_RADAR_SERVICE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("JobPulse Live Radar Active")
            .setContentText(subtitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openPending)
            .addAction(0, "Stop Radar", stopPending)
            .build()
    }

    fun showJobDropNotification(
        context: Context,
        sourceName: String,
        title: String,
        company: String,
        compensation: String,
        location: String,
        jobUrl: String,
        matchedFilterNames: List<String>,
        detectionLatencyMs: Long,
        notificationId: Int
    ) {
        if (!hasNotificationPermission(context)) return
        ensureChannels(context)

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPending = PendingIntent.getActivity(
            context,
            notificationId,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val metaParts = mutableListOf<String>()
        if (compensation.isNotBlank()) metaParts.add(compensation)
        if (location.isNotBlank()) metaParts.add(location)
        if (matchedFilterNames.isNotEmpty()) {
            metaParts.add("Matched: ${matchedFilterNames.joinToString(", ")}")
        }
        metaParts.add("${detectionLatencyMs}ms")
        val contentText = metaParts.joinToString(" · ")

        val builder = NotificationCompat.Builder(context, CHANNEL_INSTANT_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("New on $sourceName: $title")
            .setContentText(contentText)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$company — $title\n$contentText")
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(openAppPending)

        if (UrlSecurityValidator.isSafeExternalJobUrl(jobUrl)) {
            val applyIntent = Intent(Intent.ACTION_VIEW, Uri.parse(jobUrl))
            val applyPending = PendingIntent.getActivity(
                context,
                notificationId + 10_000,
                applyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, "Apply Now", applyPending)
        }

        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        } catch (_: SecurityException) {
            // Permission revoked concurrently
        }
    }

    fun showTestAlertNotification(context: Context): Boolean {
        if (!hasNotificationPermission(context)) return false
        ensureChannels(context)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            context,
            TEST_NOTIFICATION_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_INSTANT_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("JobPulse Instant Push Ready")
            .setContentText("High-priority heads-up channel verified · Sub-second alerts active")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(openPending)
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(TEST_NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }
}
