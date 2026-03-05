package com.mallowlink.app.calls.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mallowlink.app.calls.ui.viewmodel.RecordingSettingsViewModel

// ─────────────────────────────────────────────────────────────────────────────
// RecordingSettingsScreen
//
// Entry point for all consent controls and privacy options.
// This screen is intentionally explicit and verbose — the user must
// understand exactly what they are enabling before doing so.
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingSettingsScreen(
    onNavigateBack: () -> Unit,
    onViewHistory: () -> Unit,
    viewModel: RecordingSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDisableAllDialog by remember { mutableStateOf(false) }
    var showDeleteAllDialog  by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Call & Meeting Summary") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {

            // ── Master toggle ─────────────────────────────────────────────────
            MasterToggleCard(
                enabled = state.featureEnabled,
                onToggle = { enabled ->
                    if (!enabled) showDisableAllDialog = true
                    else viewModel.setFeatureEnabled(true)
                },
            )

            if (state.featureEnabled) {

                Spacer(Modifier.height(4.dp))

                // ── Capture options ───────────────────────────────────────────
                SettingSection(title = "What to record") {
                    ListItem(
                        headlineContent = { Text("Phone calls") },
                        supportingContent = { Text("Record and summarise incoming and outgoing calls") },
                        leadingContent = { Icon(Icons.Filled.Phone, null) },
                        trailingContent = {
                            Switch(
                                checked = state.callsEnabled,
                                onCheckedChange = viewModel::setCallsEnabled,
                            )
                        },
                    )
                    ListItem(
                        headlineContent = { Text("In-person meetings") },
                        supportingContent = { Text("Tap a recording button in the app or quick tile") },
                        leadingContent = { Icon(Icons.Filled.RecordVoiceOver, null) },
                        trailingContent = {
                            Switch(
                                checked = state.meetingsEnabled,
                                onCheckedChange = viewModel::setMeetingsEnabled,
                            )
                        },
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // ── Processing options ────────────────────────────────────────
                SettingSection(title = "Processing") {
                    ListItem(
                        headlineContent = { Text("Live transcription") },
                        supportingContent = {
                            Text(
                                if (state.deviceSupportsLiveTranscription)
                                    "Show transcript while recording (uses more battery)"
                                else
                                    "Your device transcribes after the call ends (recommended)"
                            )
                        },
                        leadingContent = { Icon(Icons.Filled.Mic, null) },
                        trailingContent = {
                            Switch(
                                checked = state.liveTranscriptionEnabled,
                                onCheckedChange = viewModel::setLiveTranscriptionEnabled,
                                enabled = state.deviceSupportsLiveTranscription,
                            )
                        },
                    )
                    ListItem(
                        headlineContent = { Text("ASR model") },
                        supportingContent = { Text("Whisper-tiny (39 MB) · On-device only") },
                        leadingContent = { Icon(Icons.Filled.Memory, null) },
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // ── Legal / jurisdiction ──────────────────────────────────────
                SettingSection(title = "Legal") {
                    ListItem(
                        headlineContent = { Text("Require all-party consent prompt") },
                        supportingContent = {
                            Text(
                                "Shows a checkbox requiring you to confirm you've notified " +
                                "all participants. Enable if you're in a two-party consent jurisdiction."
                            )
                        },
                        leadingContent = { Icon(Icons.Filled.Gavel, null) },
                        trailingContent = {
                            Switch(
                                checked = state.requireAllPartyConsent,
                                onCheckedChange = viewModel::setRequireAllPartyConsent,
                            )
                        },
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // ── Data management ───────────────────────────────────────────
                SettingSection(title = "Data management") {
                    ListItem(
                        headlineContent = { Text("View recording history") },
                        supportingContent = { Text("${state.totalSessions} sessions · ${state.totalStorageMb} MB") },
                        leadingContent = { Icon(Icons.Filled.Mic, null) },
                        trailingContent = {
                            TextButton(onClick = onViewHistory) { Text("View") }
                        },
                    )
                    ListItem(
                        headlineContent = {
                            Text(
                                "Delete all recordings & transcripts",
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Medium,
                            )
                        },
                        supportingContent = { Text("Cryptographically wipes all audio files and index data") },
                        leadingContent = {
                            Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error)
                        },
                        trailingContent = {
                            TextButton(onClick = { showDeleteAllDialog = true }) {
                                Text("Wipe", color = MaterialTheme.colorScheme.error)
                            }
                        },
                    )
                }

                Spacer(Modifier.height(16.dp))

                // ── Privacy note ──────────────────────────────────────────────
                PrivacyFooterCard()
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    if (showDisableAllDialog) {
        AlertDialog(
            onDismissRequest = { showDisableAllDialog = false },
            title = { Text("Disable Call Summary?") },
            text = {
                Text(
                    "This will stop all future recording and summarisation. " +
                    "Existing recordings will not be deleted.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setFeatureEnabled(false)
                    showDisableAllDialog = false
                }) { Text("Disable", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDisableAllDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text("Wipe all recordings?") },
            text = {
                Text(
                    "This will permanently and irreversibly delete all audio recordings, " +
                    "transcripts, summaries, and their encryption keys. " +
                    "This action cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAllData()
                    showDeleteAllDialog = false
                }) { Text("Wipe everything", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllDialog = false }) { Text("Cancel") }
            },
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Supporting composables
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun MasterToggleCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        ListItem(
            headlineContent = {
                Text(
                    "Call & Meeting Summary",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            supportingContent = {
                Text(
                    if (enabled)
                        "Active — recording is available when you start a call"
                    else
                        "Off — no audio is captured",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            leadingContent = {
                Icon(
                    if (enabled) Icons.Filled.Mic else Icons.Filled.MicOff,
                    null,
                    tint = if (enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = {
                Switch(checked = enabled, onCheckedChange = onToggle)
            },
        )
    }
}

@Composable
private fun SettingSection(title: String, content: @Composable () -> Unit) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
    content()
}

@Composable
private fun PrivacyFooterCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B5E20).copy(alpha = 0.08f)),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                "Privacy guarantees",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF2E7D32),
            )
            Spacer(Modifier.height(4.dp))
            listOf(
                "Audio and transcripts stored encrypted with AES-256-GCM",
                "Encryption keys in Android Keystore (hardware-backed where available)",
                "No audio, text, or summary ever sent off-device",
                "Deleting a session destroys the key, making the data unrecoverable",
            ).forEach { bullet ->
                Text(
                    "• $bullet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Suppress("UnusedReceiverParameter")
private val androidx.compose.material3.Typography.bodySmall
    get() = androidx.compose.ui.text.TextStyle(
        fontSize = androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp)
    )
