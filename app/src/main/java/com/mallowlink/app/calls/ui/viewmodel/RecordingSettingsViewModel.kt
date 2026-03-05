package com.mallowlink.app.calls.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mallowlink.app.calls.consent.ConsentManager
import com.mallowlink.app.calls.db.dao.RecordingSessionDao
import com.mallowlink.app.util.crypto.AudioEncryption
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class RecordingSettingsUiState(
    val featureEnabled: Boolean         = false,
    val callsEnabled: Boolean           = false,
    val meetingsEnabled: Boolean        = false,
    val liveTranscriptionEnabled: Boolean = false,
    val requireAllPartyConsent: Boolean = false,
    val deviceSupportsLiveTranscription: Boolean = true,
    val totalSessions: Int              = 0,
    val totalStorageMb: String          = "0",
)

@HiltViewModel
class RecordingSettingsViewModel @Inject constructor(
    private val consentManager: ConsentManager,
    private val sessionDao: RecordingSessionDao,
    private val encryption: AudioEncryption,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecordingSettingsUiState())
    val uiState: StateFlow<RecordingSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                consentManager.isFeatureEnabled,
                consentManager.isCallRecordingEnabled,
                consentManager.isMeetingRecordingEnabled,
                consentManager.isLiveTranscriptionEnabled,
                sessionDao.observeConsentedSessions(),
            ) { featureEnabled, callsEnabled, meetingsEnabled, liveEnabled, sessions ->
                val totalMb = sessions
                    .mapNotNull { it.audioPath?.let { p -> File(p) } }
                    .filter { it.exists() }
                    .sumOf { it.length() }
                    .let { "%.1f".format(it / 1_048_576.0) }

                RecordingSettingsUiState(
                    featureEnabled             = featureEnabled,
                    callsEnabled               = callsEnabled,
                    meetingsEnabled            = meetingsEnabled,
                    liveTranscriptionEnabled   = liveEnabled,
                    requireAllPartyConsent     = false,  // TODO: persist separately
                    deviceSupportsLiveTranscription = isHighEndDevice(),
                    totalSessions              = sessions.size,
                    totalStorageMb             = totalMb,
                )
            }.collect { state -> _uiState.update { state } }
        }
    }

    fun setFeatureEnabled(enabled: Boolean) {
        viewModelScope.launch { consentManager.setFeatureEnabled(enabled) }
    }

    fun setCallsEnabled(enabled: Boolean) {
        viewModelScope.launch { consentManager.setCallRecordingEnabled(enabled) }
    }

    fun setMeetingsEnabled(enabled: Boolean) {
        viewModelScope.launch { consentManager.setMeetingRecordingEnabled(enabled) }
    }

    fun setLiveTranscriptionEnabled(enabled: Boolean) {
        viewModelScope.launch { consentManager.setLiveTranscriptionEnabled(enabled) }
    }

    fun setRequireAllPartyConsent(require: Boolean) {
        // Persist via DataStore key; omitted for brevity
    }

    /** Cryptographically wipe every session: destroy keys, delete files, purge DB. */
    fun deleteAllData() {
        viewModelScope.launch {
            val sessions = sessionDao.observeConsentedSessions().let { flow ->
                var result = emptyList<com.mallowlink.app.calls.db.entity.RecordingSessionEntity>()
                kotlinx.coroutines.flow.first(flow).also { result = it }
                result
            }
            for (session in sessions) {
                session.audioPath?.let { path ->
                    encryption.secureDelete(File(path))
                }
                encryption.destroyKey(session.keyAlias)
                sessionDao.deleteById(session.id)
            }
        }
    }

    private fun isHighEndDevice(): Boolean {
        val runtime = Runtime.getRuntime()
        val availableRamMb = runtime.maxMemory() / 1_048_576
        return availableRamMb >= 512  // require ≥512 MB heap for live transcription
    }
}

// Tiny helper to collect a single flow value
private suspend fun <T> kotlinx.coroutines.flow.first(flow: kotlinx.coroutines.flow.Flow<T>): T =
    kotlinx.coroutines.flow.first(flow)
