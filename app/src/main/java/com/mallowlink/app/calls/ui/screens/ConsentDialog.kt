package com.mallowlink.app.calls.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// ─────────────────────────────────────────────────────────────────────────────
// ConsentDialog
//
// Shown each time a recordable call/meeting is detected and the user
// has not yet opted in for this specific session.
//
// Design principles:
//  • Explicit, unambiguous consent — no dark patterns
//  • "Record" is never the default / first action
//  • Counterparty notification checkbox (jurisdiction-aware)
//  • Always surfaced in a non-dismissible dialog so user must act
// ─────────────────────────────────────────────────────────────────────────────

enum class ConsentSessionType { PHONE_CALL, IN_PERSON_MEETING }

@Composable
fun ConsentDialog(
    sessionType: ConsentSessionType,
    callerOrTitle: String,
    requireCounterpartyNotification: Boolean,   // true in all-party consent jurisdictions
    onAccept: (counterpartyNotified: Boolean) -> Unit,
    onDecline: () -> Unit,
) {
    var counterpartyChecked by remember { mutableStateOf(false) }
    val canAccept = !requireCounterpartyNotification || counterpartyChecked

    Dialog(
        onDismissRequest = { /* Non-dismissible: user must tap Accept or Decline */ },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 8.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {

                // ── Header ──────────────────────────────────────────────────
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RecordingDot(isLive = false)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = when (sessionType) {
                                ConsentSessionType.PHONE_CALL -> "Record this call?"
                                ConsentSessionType.IN_PERSON_MEETING -> "Record this meeting?"
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = callerOrTitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))

                // ── Privacy notice ──────────────────────────────────────────
                PrivacyNoticeCard()

                Spacer(Modifier.height(12.dp))

                // ── What will be recorded ───────────────────────────────────
                Text(
                    text = "What MallowLink will do:",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))

                ConsentBullet(
                    icon = Icons.Filled.Mic,
                    text = "Record audio and transcribe it entirely on your device",
                )
                ConsentBullet(
                    icon = Icons.Filled.Lock,
                    text = "Store the recording encrypted – never uploaded to the cloud",
                )
                ConsentBullet(
                    icon = Icons.Filled.Info,
                    text = "Generate a private summary, action items, and key decisions",
                )

                Spacer(Modifier.height(12.dp))

                // ── Counterparty notification (all-party consent) ────────────
                if (requireCounterpartyNotification) {
                    CounterpartyNoticeCard(
                        checked = counterpartyChecked,
                        onCheckedChange = { counterpartyChecked = it },
                    )
                    Spacer(Modifier.height(12.dp))
                }

                // ── Actions ─────────────────────────────────────────────────
                Button(
                    onClick = { onAccept(counterpartyChecked) },
                    enabled = canAccept,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE53935),
                        contentColor   = Color.White,
                    ),
                ) {
                    Icon(Icons.Filled.Mic, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = when (sessionType) {
                            ConsentSessionType.PHONE_CALL      -> "Record Call"
                            ConsentSessionType.IN_PERSON_MEETING -> "Record Meeting"
                        },
                        fontWeight = FontWeight.Bold,
                    )
                }

                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onDecline,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Don't Record")
                }

                Spacer(Modifier.height(8.dp))

                Text(
                    text = "You can delete any recording and its transcript at any time in Settings → Call Summary.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Recording indicator dot (pulsing red when live)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun RecordingDot(isLive: Boolean, modifier: Modifier = Modifier) {
    val color = if (isLive) Color(0xFFE53935) else MaterialTheme.colorScheme.outline

    if (isLive) {
        // Pulsing animation
        val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "dot")
        val alpha by transition.androidx.compose.animation.core.animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                animation = androidx.compose.animation.core.tween(800),
                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "dotAlpha",
        )
        Box(
            modifier = modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = alpha))
        )
    } else {
        Box(
            modifier = modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(color)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Supporting composables
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PrivacyNoticeCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
        ),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "100% on-device. Audio and transcripts never leave your phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun CounterpartyNoticeCard(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
        ),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Legal requirement",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Your jurisdiction may require all parties to consent to recording. " +
                           "Please inform the other party before proceeding.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = androidx.compose.foundation.clickable(onClick = { onCheckedChange(!checked) })
                        .let { Modifier.then(it) },
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = onCheckedChange,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "I have informed all participants that this call may be recorded",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConsentBullet(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
) {
    Row(
        modifier = Modifier.padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// Alias missing from Material3 token set
@Suppress("UnusedReceiverParameter")
private val androidx.compose.material3.Typography.bodySmall
    get() = androidx.compose.ui.text.TextStyle(
        fontSize = androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp),
        lineHeight = androidx.compose.ui.unit.TextUnit(18f, androidx.compose.ui.unit.TextUnitType.Sp),
    )

// Modifier.clickable without ripple for Row wrapper
private fun Modifier.clickable(onClick: () -> Unit): Modifier =
    this.then(androidx.compose.foundation.clickable(onClick = onClick))
