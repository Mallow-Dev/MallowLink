package com.mallowlink.app.ui.onboarding

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.rememberPermissionState

// ─────────────────────────────────────────────────────────────────────────────
// OnboardingScreen – first-run setup wizard
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun OnboardingScreen(
    onComplete: (List<Uri>) -> Unit,
) {
    var page by remember { mutableIntStateOf(0) }
    val selectedUris = remember { mutableStateListOf<Uri>() }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { if (!selectedUris.contains(it)) selectedUris.add(it) }
    }

    // Storage permissions
    val storagePermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberMultiplePermissionsState(
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        )
    } else {
        rememberMultiplePermissionsState(
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        )
    }

    AnimatedContent(
        targetState = page,
        transitionSpec = {
            fadeIn(tween(400)) togetherWith fadeOut(tween(200))
        },
        label = "onboarding",
    ) { targetPage ->
        when (targetPage) {
            0 -> WelcomePage(onNext = { page = 1 })
            1 -> PermissionsPage(
                permissions = storagePermissions,
                onNext = { page = 2 }
            )
            2 -> SelectFoldersPage(
                selectedUris = selectedUris,
                onAddFolder = { folderPicker.launch(null) },
                onRemoveFolder = { selectedUris.remove(it) },
                onNext = { page = 3 },
                onSkip = { onComplete(emptyList()) },
            )
            3 -> PrivacyPage(
                onComplete = { onComplete(selectedUris.toList()) }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Pages
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun WelcomePage(onNext: () -> Unit) {
    OnboardingScaffold(
        emoji = "🌸",
        title = "Meet MallowLink",
        subtitle = "Your personal AI assistant, 100% on-device.\nSearch and chat with your own files — privately.",
        features = listOf(
            "PDF, DOCX, images, notes" to Icons.Filled.Search,
            "Zero cloud calls — runs fully offline" to Icons.Filled.Lock,
            "AI answers with citations to your files" to Icons.Filled.Check,
        ),
        primaryLabel = "Get Started",
        onPrimary = onNext,
    )
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionsPage(
    permissions: com.google.accompanist.permissions.MultiplePermissionsState,
    onNext: () -> Unit,
) {
    val allGranted = permissions.allPermissionsGranted
    OnboardingScaffold(
        emoji = "📂",
        title = "Storage Access",
        subtitle = "MallowLink needs permission to read your files.\nNo data is ever uploaded or shared.",
        features = listOf(
            "Read photos & media (OCR on screenshots)" to Icons.Filled.Search,
            "SAF folder access (you choose exactly which folders)" to Icons.Filled.Folder,
            "Notification permission (indexing progress)" to Icons.Filled.Check,
        ),
        primaryLabel = if (allGranted) "Permissions Granted ✓" else "Grant Permissions",
        onPrimary = {
            if (!allGranted) permissions.launchMultiplePermissionRequest()
            else onNext()
        },
        secondaryLabel = if (allGranted) "Continue" else null,
        onSecondary = if (allGranted) onNext else null,
    )
}

@Composable
fun SelectFoldersPage(
    selectedUris: List<Uri>,
    onAddFolder: () -> Unit,
    onRemoveFolder: (Uri) -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        Text("📁", style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.align(Alignment.CenterHorizontally))
        Text(
            text = "Choose Folders to Index",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "Pick the folders you want MallowLink to learn from.\nYou can add or remove folders anytime in Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        LazyColumn(
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(selectedUris.size) { i ->
                val uri = selectedUris[i]
                FolderChip(
                    label = uri.lastPathSegment?.substringAfterLast(':') ?: uri.toString(),
                    onRemove = { onRemoveFolder(uri) },
                )
            }
            item {
                OutlinedButton(
                    onClick = onAddFolder,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Folder, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add a Folder")
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onSkip, modifier = Modifier.weight(1f)) { Text("Skip for now") }
            Button(
                onClick = onNext,
                modifier = Modifier.weight(1f),
                enabled = selectedUris.isNotEmpty(),
            ) { Text("Continue") }
        }
    }
}

@Composable
fun PrivacyPage(onComplete: () -> Unit) {
    OnboardingScaffold(
        emoji = "🔒",
        title = "Your Privacy, Guaranteed",
        subtitle = "MallowLink is built with privacy as the foundation.",
        features = listOf(
            "All data indexed & stored locally on your device" to Icons.Filled.Lock,
            "No analytics events contain your content or file names" to Icons.Filled.Check,
            "You can delete any folder or file index with one tap" to Icons.Filled.Check,
        ),
        primaryLabel = "Start Indexing",
        onPrimary = onComplete,
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Reusable scaffold for onboarding pages
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun OnboardingScaffold(
    emoji: String,
    title: String,
    subtitle: String,
    features: List<Pair<String, ImageVector>>,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Text(emoji, style = MaterialTheme.typography.headlineLarge)
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)

        Spacer(Modifier.height(8.dp))

        features.forEach { (text, icon) ->
            FeatureRow(icon = icon, label = text)
        }

        Spacer(Modifier.weight(1f))

        if (secondaryLabel != null && onSecondary != null) {
            OutlinedButton(onClick = onSecondary, modifier = Modifier.fillMaxWidth()) {
                Text(secondaryLabel)
            }
        }
        Button(onClick = onPrimary, modifier = Modifier.fillMaxWidth()) {
            Text(primaryLabel)
        }
    }
}

@Composable
fun FeatureRow(icon: ImageVector, label: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun FolderChip(label: String, onRemove: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            androidx.compose.material3.IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Check, "Remove", modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
