package com.mallowlink.app.ui.settings

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
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Storage
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// ─────────────────────────────────────────────────────────────────────────────
// SettingsScreen
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
) {
    var batteryThreshold by remember { mutableFloatStateOf(15f) }
    var indexOnCharging by remember { mutableStateOf(true) }
    var backgroundSync by remember { mutableStateOf(true) }
    var showClearDialog by remember { mutableStateOf(false) }
    var dynamicColors by remember { mutableStateOf(true) }
    var indexImages by remember { mutableStateOf(true) }
    var maxChunkTokens by remember { mutableFloatStateOf(300f) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
            // ── Model ─────────────────────────────────────────────────────────
            SettingsSectionHeader("On-Device Model")

            SettingsInfoItem(
                icon = Icons.Filled.Memory,
                title = "Active model",
                value = "Phi-2 (2.7B, INT4 quantised)",
            )
            SettingsInfoItem(
                icon = Icons.Filled.Storage,
                title = "Model size",
                value = "1.6 GB on-device",
            )
            ListItem(
                headlineContent = { Text("Chunk size (tokens)") },
                supportingContent = { Text("${maxChunkTokens.toInt()} tokens per embedding chunk") },
                trailingContent = {
                    Column {
                        Slider(
                            value = maxChunkTokens,
                            onValueChange = { maxChunkTokens = it },
                            valueRange = 100f..500f,
                            steps = 7,
                            modifier = Modifier.fillMaxWidth(0.5f),
                        )
                    }
                },
                leadingContent = { Icon(Icons.Filled.Memory, null) },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ── Indexing ──────────────────────────────────────────────────────
            SettingsSectionHeader("Indexing & Sync")

            SettingsToggleItem(
                icon = Icons.Filled.BatteryAlert,
                title = "Only index when charging",
                subtitle = "Prevents battery drain during large indexing jobs",
                checked = indexOnCharging,
                onCheckedChange = { indexOnCharging = it },
            )

            ListItem(
                headlineContent = { Text("Battery threshold") },
                supportingContent = { Text("Stop indexing below ${batteryThreshold.toInt()}%") },
                leadingContent = { Icon(Icons.Filled.BatteryAlert, null) },
                trailingContent = {
                    Slider(
                        value = batteryThreshold,
                        onValueChange = { batteryThreshold = it },
                        valueRange = 5f..50f,
                        steps = 8,
                        modifier = Modifier.fillMaxWidth(0.5f),
                    )
                },
            )

            SettingsToggleItem(
                icon = Icons.Filled.Notifications,
                title = "Background sync",
                subtitle = "Automatically re-index every 6 hours",
                checked = backgroundSync,
                onCheckedChange = { backgroundSync = it },
            )

            SettingsToggleItem(
                icon = Icons.Filled.Storage,
                title = "Index images via OCR",
                subtitle = "Run text recognition on photos and screenshots",
                checked = indexImages,
                onCheckedChange = { indexImages = it },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ── Privacy ───────────────────────────────────────────────────────
            SettingsSectionHeader("Privacy")

            ListItem(
                headlineContent = { Text("Clear all indexed data", fontWeight = FontWeight.Medium) },
                supportingContent = { Text("Removes all embeddings and document metadata. Original files are not deleted.") },
                leadingContent = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                trailingContent = {
                    TextButton(onClick = { showClearDialog = true }) {
                        Text("Clear", color = MaterialTheme.colorScheme.error)
                    }
                },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ── About ─────────────────────────────────────────────────────────
            SettingsSectionHeader("About")
            SettingsInfoItem(
                icon = Icons.Filled.Info,
                title = "MallowLink",
                value = "Version 1.0.0 • All inference runs on-device",
            )

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear All Index Data?") },
            text = { Text("This will delete all embeddings and metadata. Your original files will not be affected. You will need to re-index your sources.") },
            confirmButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            },
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Reusable settings items
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
fun SettingsToggleItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, null) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
    )
}

@Composable
fun SettingsInfoItem(icon: ImageVector, title: String, value: String) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(value) },
        leadingContent = { Icon(icon, null) },
    )
}
