package com.ghostprotocol.ui

import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostprotocol.data.Contact
import com.ghostprotocol.data.MessageEntity

// ======================== Message Actions Sheet ========================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionsBottomSheet(
    message: MessageEntity,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    val T = GhostTheme

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = T.SurfaceOverlay,
        dragHandle = { SheetHandle() }
    ) {
        Column(modifier = Modifier.padding(bottom = 32.dp)) {
            // Preview of the message
            Text(
                text = message.content,
                fontSize = 14.sp,
                color = T.TextSecondary,
                maxLines = 2,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            SheetActionRow(
                icon = Icons.Filled.Reply,
                label = "Reply",
                tint = T.TextPrimary,
                onClick = { onReply(); onDismiss() }
            )
            SheetActionRow(
                icon = Icons.Filled.ContentCopy,
                label = "Copy",
                tint = T.TextPrimary,
                onClick = { onCopy(); onDismiss() }
            )

            HorizontalDivider(
                color = T.Surface3,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            SheetActionRow(
                icon = Icons.Filled.Delete,
                label = "Delete",
                tint = T.Failed,
                onClick = { onDelete(); onDismiss() }
            )
        }
    }
}

// ======================== Contact Info Sheet ========================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactInfoBottomSheet(
    contact: Contact,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onShowQR: () -> Unit,
    onClearChat: () -> Unit,
    onDeleteContact: () -> Unit
) {
    val T = GhostTheme
    var showClearConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = T.SurfaceOverlay,
        dragHandle = { SheetHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Large avatar
            val avatar = AvatarGenerator.fromPubkey(
                Base64.decode(contact.ed25519PubKey, Base64.NO_WRAP),
                contact.name
            )
            Box(
                modifier = Modifier
                    .size(T.AvatarLarge)
                    .background(avatar.backgroundColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = avatar.initial.toString(),
                    color = avatar.textColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 32.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                contact.name,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = T.TextPrimary
            )
            Text(
                "#${contact.id.take(6)}",
                fontSize = 14.sp,
                color = T.TextSecondary
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Show QR button
            Button(
                onClick = { onShowQR(); onDismiss() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = T.Purple,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(T.RadiusInput),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(48.dp)
            ) {
                Icon(Icons.Filled.QrCode, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Show QR Code", fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = T.Surface3, modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(modifier = Modifier.height(4.dp))

            SheetActionRow(
                icon = Icons.Filled.Delete,
                label = "Clear Chat",
                tint = T.TextSecondary,
                onClick = { showClearConfirm = true }
            )
            SheetActionRow(
                icon = Icons.Filled.Delete,
                label = "Delete Contact",
                tint = T.Failed,
                onClick = { showDeleteConfirm = true }
            )
        }
    }

    // Confirmation dialogs
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear Chat") },
            text = { Text("Delete all messages with ${contact.name}? This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    onClearChat()
                    onDismiss()
                }) { Text("Clear", color = T.Failed) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Contact") },
            text = { Text("Remove ${contact.name} and all messages? This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDeleteContact()
                    onDismiss()
                }) { Text("Delete", color = T.Failed) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

// ======================== Shared Components ========================

@Composable
private fun SheetHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(GhostTheme.Surface3)
        )
    }
}

@Composable
private fun SheetActionRow(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            label,
            fontSize = 15.sp,
            color = tint,
            fontWeight = FontWeight.Medium
        )
    }
}
