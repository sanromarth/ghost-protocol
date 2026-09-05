package com.ghostprotocol.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostprotocol.data.MessageEntity

/**
 * Authoritative message status indicator.
 * Strictly reflects the underlying protocol state:
 * - STATUS_PENDING (0): Micro-dot pulse
 * - STATUS_SENT (1): Single tick ✓
 * - STATUS_DELIVERED (2): Double tick ✓✓
 * - STATUS_FAILED (3): Warning ! with optional retry
 * - STATUS_SPRAYED (4): Physical mesh wave ∿ (DTN relay)
 */
@Composable
fun GhostStatusIndicator(
    status: Int,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null
) {
    val T = GhostTheme

    when (status) {
        MessageEntity.STATUS_PENDING -> {
            CircularProgressIndicator(
                modifier = modifier.size(10.dp),
                strokeWidth = 1.5.dp,
                color = Color.White.copy(alpha = 0.5f)
            )
        }
        MessageEntity.STATUS_SENT -> {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Sent to direct peer",
                tint = T.SentCheck,
                modifier = modifier.size(13.dp)
            )
        }
        MessageEntity.STATUS_DELIVERED -> {
            Box(
                modifier = modifier.width(16.dp).height(13.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = T.SentCheck.copy(alpha = 0.65f),
                    modifier = Modifier.size(12.dp)
                )
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Delivered to recipient",
                    tint = T.SentCheck,
                    modifier = Modifier
                        .size(12.dp)
                        .offset(x = 4.dp)
                )
            }
        }
        MessageEntity.STATUS_FAILED -> {
            Box(
                modifier = modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(T.Failed)
                    .then(
                        if (onRetry != null) {
                            Modifier.clickable(
                                role = Role.Button,
                                onClick = onRetry
                            )
                        } else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PriorityHigh,
                    contentDescription = "Failed — tap to retry",
                    tint = Color.White,
                    modifier = Modifier.size(10.dp)
                )
            }
        }
        MessageEntity.STATUS_SPRAYED -> {
            // Refined DTN mesh wave indicator: physical radio symbol, zero raw emojis
            Text(
                text = "∿",
                color = T.Sprayed,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = modifier
            )
        }
    }
}

/**
 * Compact chip badge for metadata (e.g. CELL, INTRODUCED, unread count).
 */
@Composable
fun GhostBadge(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = GhostTheme.Purple,
    contentColor: Color = Color.White
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(containerColor)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = contentColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.5.sp
        )
    }
}

/**
 * Minimalist, confident empty state (zero childish emojis).
 */
@Composable
fun GhostEmptyState(
    icon: ImageVector?,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    val T = GhostTheme

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(T.Surface2),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = T.TextMuted,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(modifier = Modifier.height(T.SpaceLg))
        }

        Text(
            text = title,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = T.TextPrimary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(T.SpaceSm))

        Text(
            text = subtitle,
            fontSize = 14.sp,
            color = T.TextMuted,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )

        if (actionText != null && onAction != null) {
            Spacer(modifier = Modifier.height(T.SpaceXl))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(
                    containerColor = T.Purple,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(T.RadiusInput),
                modifier = Modifier
                    .heightIn(min = T.MinTouchTarget)
                    .fillMaxWidth(0.7f)
            ) {
                Text(
                    text = actionText,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
            }
        }
    }
}

/**
 * Primary Floating Action Button: single sleek 56dp FAB replacing the 4 stacked buttons.
 */
@Composable
fun GhostActionFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = "New conversation"
) {
    val T = GhostTheme

    FloatingActionButton(
        onClick = onClick,
        containerColor = T.Purple,
        contentColor = Color.White,
        shape = CircleShape,
        modifier = modifier.size(56.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = contentDescription,
            modifier = Modifier.size(24.dp)
        )
    }
}

/**
 * Modern New Chat Bottom Sheet replacing the 4 stacked FABs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatBottomSheet(
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onNewCellGroup: () -> Unit,
    onScanQr: () -> Unit,
    onShowQr: () -> Unit,
    onAddShortCode: () -> Unit
) {
    val T = GhostTheme

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = T.SurfaceOverlay,
        dragHandle = { SheetHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "New Connection",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = T.TextPrimary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )

            Spacer(modifier = Modifier.height(4.dp))

            NewChatActionRow(
                icon = Icons.Default.Groups,
                title = "New Cell Group",
                subtitle = "Encrypted multi-peer broadcast group (up to 8 members)",
                onClick = {
                    onDismiss()
                    onNewCellGroup()
                }
            )

            NewChatActionRow(
                icon = Icons.Default.QrCodeScanner,
                title = "Scan Contact QR",
                subtitle = "Verify cryptographic keys in person",
                onClick = {
                    onDismiss()
                    onScanQr()
                }
            )

            NewChatActionRow(
                icon = Icons.Default.QrCode,
                title = "My QR Code & Key",
                subtitle = "Display your identity for others to scan",
                onClick = {
                    onDismiss()
                    onShowQr()
                }
            )

            NewChatActionRow(
                icon = Icons.Default.Pin,
                title = "Add by Short Code",
                subtitle = "Temporary 4-character discovery code",
                onClick = {
                    onDismiss()
                    onAddShortCode()
                }
            )
        }
    }
}

@Composable
private fun NewChatActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val T = GhostTheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = T.MinTouchTarget)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(T.Surface2),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = T.PurpleLight,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = T.TextPrimary
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = T.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
