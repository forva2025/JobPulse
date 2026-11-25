package com.example.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.radarDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "jobpulse_radar_prefs"
)

data class RadarPreferences(
    val liveRadarEnabled: Boolean = false,
    val defaultPollIntervalSeconds: Int = 15,
    val vibrationEnabled: Boolean = true,
    val soundEnabled: Boolean = true
)

class RadarPreferencesStore(private val context: Context) {

    private object Keys {
        val LIVE_RADAR_ENABLED = booleanPreferencesKey("live_radar_enabled")
        val DEFAULT_POLL_SECONDS = intPreferencesKey("default_poll_seconds")
        val VIBRATION_ENABLED = booleanPreferencesKey("vibration_enabled")
        val SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
        val SEEN_FINGERPRINTS = stringSetPreferencesKey("seen_fingerprints")
        val BASELINED_SOURCES = stringSetPreferencesKey("baselined_sources")
    }

    val preferencesFlow: Flow<RadarPreferences> = context.radarDataStore.data.map { prefs ->
        RadarPreferences(
            liveRadarEnabled = prefs[Keys.LIVE_RADAR_ENABLED] ?: false,
            defaultPollIntervalSeconds = prefs[Keys.DEFAULT_POLL_SECONDS] ?: 15,
            vibrationEnabled = prefs[Keys.VIBRATION_ENABLED] ?: true,
            soundEnabled = prefs[Keys.SOUND_ENABLED] ?: true
        )
    }

    suspend fun setLiveRadarEnabled(enabled: Boolean) {
        context.radarDataStore.edit { it[Keys.LIVE_RADAR_ENABLED] = enabled }
    }

    suspend fun setDefaultPollIntervalSeconds(seconds: Int) {
        val clamped = seconds.coerceIn(10, 3600)
        context.radarDataStore.edit { it[Keys.DEFAULT_POLL_SECONDS] = clamped }
    }

    suspend fun setVibrationEnabled(enabled: Boolean) {
        context.radarDataStore.edit { it[Keys.VIBRATION_ENABLED] = enabled }
    }

    suspend fun setSoundEnabled(enabled: Boolean) {
        context.radarDataStore.edit { it[Keys.SOUND_ENABLED] = enabled }
    }

    suspend fun getSeenFingerprints(): Set<String> {
        return context.radarDataStore.data.first()[Keys.SEEN_FINGERPRINTS] ?: emptySet()
    }

    suspend fun isSourceBaselined(sourceId: String): Boolean {
        val baselined = context.radarDataStore.data.first()[Keys.BASELINED_SOURCES] ?: emptySet()
        return sourceId in baselined
    }

    suspend fun recordSeenFingerprints(sourceId: String, newFingerprints: Collection<String>) {
        context.radarDataStore.edit { prefs ->
            val currentSeen = prefs[Keys.SEEN_FINGERPRINTS] ?: emptySet()
            // Keep bounded to most recent 1500 hashes so DataStore stays ultra-fast
            val combined = (currentSeen + newFingerprints).toList().takeLast(1500).toSet()
            prefs[Keys.SEEN_FINGERPRINTS] = combined
            val currentBaselined = prefs[Keys.BASELINED_SOURCES] ?: emptySet()
            prefs[Keys.BASELINED_SOURCES] = currentBaselined + sourceId
        }
    }

    suspend fun clearSourceBaseline(sourceId: String) {
        context.radarDataStore.edit { prefs ->
            val currentBaselined = prefs[Keys.BASELINED_SOURCES] ?: emptySet()
            prefs[Keys.BASELINED_SOURCES] = currentBaselined - sourceId
        }
    }
}
