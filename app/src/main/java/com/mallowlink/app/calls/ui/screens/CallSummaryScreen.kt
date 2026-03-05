package com.mallowlink.app.calls.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mallowlink.app.calls.summary.ActionItem
import com.mallowlink.app.calls.summary.KeyDecision
import com.mallowlink.app.calls.ui.viewmodel.CallSummaryUiState
import com.mallowlink.app.calls.ui.viewmodel.CallSummaryViewModel

// ─────────────────────────────────────────────────────────────────────────────
// CallSummaryScreen
//
// Displays the AI-generated summary for a recorded call or meeting.
// Sections:
//   1. Recording meta (type, date, duration)
//   2. Overview paragraph
//   3. Action items (expandable, with timestamp deep-links)
//   4. Key decisions (expandable, with timestamp deep-links)
//   5. Related files & calendar events (auto-suggested, accept/dismiss)
//   6. Full transcript (scrollable, grouped by speaker)
//   7. Delete controls (delete summary only / delete recording / delete all)
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallSummaryScreen(
    sessionId: String,
    onNavigateBack: () -> Unit,
    onOpenDocument: (String) -> Unit,
    onSeekAudio: (Float) -> Unit,
    viewModel: CallSummaryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showDeleteDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                    }
                },
            )
        },
    ) { padding ->
        when {
            uiState.isLoading -> LoadingState()
            uiState.isProcessing -> ProcessingState(uiState.processingLabel)
            uiState.error != null -> ErrorState(uiState.error!!)
            else -> SummaryContent(
                uiState = uiState,
                padding = padding,
                onOpenDocument = onOpenDocument,
                onSeekAudio = onSeekAudio,
                onAcceptLink = viewModel::acceptLink,
                onDismissLink = viewModel::dismissLink,
            )
        }
    }

    if (showDeleteDialog) {
        DeleteOptionsDialog(
            onDeleteSummaryOnly = {
                viewModel.deleteSummary()
                showDeleteDialog = false
                onNavigateBack()
            },
            onDeleteRecording = {
                viewModel.deleteRecording()
                showDeleteDialog = false
                onNavigateBack()
            },
            onDeleteAll = {
                viewModel.deleteAll()
                showDeleteDialog = false
                onNavigateBack()
            },
            onDismiss = { showDeleteDialog = false },
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Summary content
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SummaryContent(
    uiState: CallSummaryUiState,
    padding: PaddingValues,
    onOpenDocument: (String) -> Unit,
    onSeekAudio: (Float) -> Unit,
    onAcceptLink: (String) -> Unit,
    onDismissLink: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Meta bar
        item {
            RecordingMetaBar(uiState)
        }

        // Overview
        item {
            SummarySection(title = "Overview", icon = Icons.Filled.Description) {
                Text(
                    text = uiState.overview,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        // Action items
        if (uiState.actionItems.isNotEmpty()) {
            item {
                ExpandableSection(
                    title = "Action Items (${uiState.actionItems.size})",
                    icon = Icons.Filled.TaskAlt,
                    initiallyExpanded = true,
                ) {
                    uiState.actionItems.forEachIndexed { i, item ->
                        ActionItemRow(
                            item = item,
                            index = i + 1,
                            onSeekToSegment = {
                                /* find startSec from segmentIds */
                                onSeekAudio(0f)
                            },
                        )
                        if (i < uiState.actionItems.lastIndex) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                        }
                    }
                }
            }
        }

        // Key decisions
        if (uiState.keyDecisions.isNotEmpty()) {
            item {
                ExpandableSection(
                    title = "Key Decisions (${uiState.keyDecisions.size})",
                    icon = Icons.Filled.Gavel,
                    initiallyExpanded = true,
                ) {
                    uiState.keyDecisions.forEachIndexed { i, decision ->
                        KeyDecisionRow(decision = decision, onSeekAudio = onSeekAudio)
                        if (i < uiState.keyDecisions.lastIndex) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                        }
                    }
                }
            }
        }

        // Linked resources
        if (uiState.linkedResources.isNotEmpty()) {
            item {
                ExpandableSection(
                    title = "Related (${uiState.linkedResources.size})",
                    icon = Icons.Filled.Link,
                    initiallyExpanded = false,
                ) {
                    uiState.linkedResources.forEach { link ->
                        LinkedResourceRow(
                            title = link.displayTitle,
                            resourceType = link.resourceType,
                            relevanceScore = link.relevanceScore,
                            userAccepted = link.userAccepted,
                            onOpen = { onOpenDocument(link.uriString) },
                            onAccept = { onAcceptLink(link.id) },
                            onDismiss = { onDismissLink(link.id) },
                        )
                    }
                }
            }
        }

        // Transcript
        if (uiState.transcriptSegments.isNotEmpty()) {
            item {
                ExpandableSection(
                    title = "Transcript",
                    icon = Icons.Filled.Description,
                    initiallyExpanded = false,
                ) {
                    uiState.transcriptSegments.forEach { seg ->
                        TranscriptSegmentRow(
                            speaker   = seg.speaker,
                            startSec  = seg.startSec,
                            text      = seg.text,
                            onSeek    = { onSeekAudio(seg.startSec) },
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Composable rows
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun RecordingMetaBar(state: CallSummaryUiState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MetaChip(
                label = state.sessionTypeLabel,
                color = if (state.sessionTypeLabel == "Call") Color(0xFF1565C0) else Color(0xFF2E7D32),
            )
            Text(state.formattedDate, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(state.formattedDuration, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun MetaChip(label: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = color,
            fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ActionItemRow(item: ActionItem, index: Int, onSeekToSegment: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = "$index.",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(item.description, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface)
            if (item.owner != "Unknown" || item.dueHint.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.owner != "Unknown") {
                        Text("👤 ${item.owner}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (item.dueHint.isNotBlank()) {
                        Text("📅 ${item.dueHint}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (item.segmentIds.isNotEmpty()) {
            IconButton(onClick = onSeekToSegment, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.PlayArrow, "Jump to in transcript",
                    modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun KeyDecisionRow(decision: KeyDecision, onSeekAudio: (Float) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.Gavel, null, modifier = Modifier.size(16.dp).padding(top = 2.dp),
            tint = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(decision.summary, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface)
            if (decision.startSec > 0f) {
                Spacer(Modifier.height(2.dp))
                Text("@ ${formatTimestamp(decision.startSec)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (decision.startSec >= 0f) {
            IconButton(onClick = { onSeekAudio(decision.startSec) }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.PlayArrow, "Jump", modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun LinkedResourceRow(
    title: String,
    resourceType: String,
    relevanceScore: Float,
    userAccepted: Boolean?,
    onOpen: () -> Unit,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (userAccepted == false) return  // dismissed; don't show
    val icon = when (resourceType) {
        "CALENDAR_EVENT" -> Icons.Filled.CalendarToday
        "DOCUMENT" -> Icons.Filled.Description
        else -> Icons.Filled.Link
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium)
                Text("${(relevanceScore * 100).toInt()}% match",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (userAccepted == null) {
                // Pending suggestion
                IconButton(onClick = onAccept, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.CheckCircle, "Accept", modifier = Modifier.size(18.dp),
                        tint = Color(0xFF2E7D32))
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Close, "Dismiss", modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error)
                }
            } else {
                // Accepted
                IconButton(onClick = onOpen, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open",
                        modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
fun TranscriptSegmentRow(speaker: String, startSec: Float, text: String, onSeek: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.width(56.dp)) {
            Text(
                text = formatTimestamp(startSec),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable { onSeek() },
            )
            Text(
                text = speaker,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Section scaffold
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun SummarySection(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
fun ExpandableSection(
    title: String,
    icon: ImageVector,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    HorizontalDivider(modifier = Modifier.padding(bottom = 12.dp))
                    content()
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Delete dialog
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun DeleteOptionsDialog(
    onDeleteSummaryOnly: () -> Unit,
    onDeleteRecording: () -> Unit,
    onDeleteAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete data") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Choose what to delete. This action cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalButton(onClick = onDeleteAll, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete everything (audio + transcript + summary)",
                        color = MaterialTheme.colorScheme.error)
                }
                OutlinedButton(onClick = onDeleteRecording, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete audio only (keep summary)")
                }
                OutlinedButton(onClick = onDeleteSummaryOnly, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete summary only (keep audio)")
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// State placeholders
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun ProcessingState(label: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ErrorState(message: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text("Error: $message", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

fun formatTimestamp(secs: Float): String {
    val m = (secs / 60).toInt()
    val s = (secs % 60).toInt()
    return "%d:%02d".format(m, s)
}

@Suppress("UnusedReceiverParameter")
private val androidx.compose.material3.Typography.bodySmall
    get() = androidx.compose.ui.text.TextStyle(
        fontSize = androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp),
        lineHeight = androidx.compose.ui.unit.TextUnit(18f, androidx.compose.ui.unit.TextUnitType.Sp),
    )
