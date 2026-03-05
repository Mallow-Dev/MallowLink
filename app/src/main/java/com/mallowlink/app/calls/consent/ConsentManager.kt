package com.mallowlink.app.calls.consent

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
// ConsentManager
//
// Single source of truth for all consent signals:
//  • Global feature toggle (opt-in / opt-out) — default OFF
//  • Per-type toggles: phone calls vs. in-person meetings
//  • Per-session revocation list
//  • Live-transcription toggle (for low-end device graceful degradation)
// ─────────────────────────────────────────────────────────────────────────────

private val Context.consentDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "calls_consent")

@Singleton
class ConsentManager @Inject constructor(
    private val context: Context,
) {
    private val store = context.consentDataStore

    companion object {
        val KEY_FEATURE_ENABLED    = booleanPreferencesKey("feature_enabled")
        val KEY_CALLS_ENABLED      = booleanPreferencesKey("calls_enabled")
        val KEY_MEETINGS_ENABLED   = booleanPreferencesKey("meetings_enabled")
        val KEY_LIVE_TRANSCRIPTION = booleanPreferencesKey("live_transcription")
        val KEY_REVOKED_SESSIONS   = stringSetPreferencesKey("revoked_sessions")
        val KEY_GLOBAL_OPT_OUT_AT  = longPreferencesKey("global_opt_out_at")
        /** ONE_PARTY | ALL_PARTY — affects counterparty notification prompt */
        val KEY_JURISDICTION       = stringSetPreferencesKey("jurisdiction")
    }

    // ── Observable state ──────────────────────────────────────────────────────

    val isFeatureEnabled: Flow<Boolean> = store.data.map {
        it[KEY_FEATURE_ENABLED] ?: false   // opt-IN: disabled until user enables
    }

    val isCallRecordingEnabled: Flow<Boolean> = store.data.map {
        (it[KEY_FEATURE_ENABLED] ?: false) && (it[KEY_CALLS_ENABLED] ?: false)
    }

    val isMeetingRecordingEnabled: Flow<Boolean> = store.data.map {
        (it[KEY_FEATURE_ENABLED] ?: false) && (it[KEY_MEETINGS_ENABLED] ?: false)
    }

    val isLiveTranscriptionEnabled: Flow<Boolean> = store.data.map {
        it[KEY_LIVE_TRANSCRIPTION] ?: false
    }

    val revokedSessions: Flow<Set<String>> = store.data.map {
        it[KEY_REVOKED_SESSIONS] ?: emptySet()
    }

    // ── Writes ────────────────────────────────────────────────────────────────

    /** Master switch — when disabled, all active capture is stopped immediately. */
    suspend fun setFeatureEnabled(enabled: Boolean) {
        store.edit { prefs ->
            prefs[KEY_FEATURE_ENABLED] = enabled
            if (!enabled) prefs[KEY_GLOBAL_OPT_OUT_AT] = System.currentTimeMillis()
        }
    }

    suspend fun setCallRecordingEnabled(enabled: Boolean) {
        store.edit { it[KEY_CALLS_ENABLED] = enabled }
    }

    suspend fun setMeetingRecordingEnabled(enabled: Boolean) {
        store.edit { it[KEY_MEETINGS_ENABLED] = enabled }
    }

    suspend fun setLiveTranscriptionEnabled(enabled: Boolean) {
        store.edit { it[KEY_LIVE_TRANSCRIPTION] = enabled }
    }

    /**
     * Revoke consent for one session post-hoc.
     * The CallDataManager observes [revokedSessions] and triggers wipe.
     */
    suspend fun revokeSession(sessionId: String) {
        store.edit { prefs ->
            val existing = prefs[KEY_REVOKED_SESSIONS] ?: emptySet()
            prefs[KEY_REVOKED_SESSIONS] = existing + sessionId
        }
    }

    suspend fun isSessionRevoked(sessionId: String): Boolean =
        revokedSessions.first().contains(sessionId)

    suspend fun isFeatureCurrentlyEnabled(): Boolean =
        isFeatureEnabled.first()
}
