package com.ghostprotocol.ui

import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostprotocol.GhostService
import com.ghostprotocol.data.Contact
import com.ghostprotocol.data.GhostDatabase
import com.ghostprotocol.data.MessageEntity
import kotlinx.coroutines.launch

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
    var showIntroduceDialog by remember { mutableStateOf(false) }

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
            val ed25519Bytes = remember(contact.ed25519PubKey) {
                try { Base64.decode(contact.ed25519PubKey, Base64.NO_WRAP) } catch (_: Exception) { null }
            }
            GhostAvatar(
                pubkey = ed25519Bytes,
                name = contact.name,
                size = T.AvatarLarge,
                isMutuallyVerified = contact.isVerified
            )

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

            if (contact.isVerified) {
                SheetActionRow(
                    icon = Icons.Filled.Share,
                    label = "Introduce to...",
                    tint = T.PurpleLight,
                    onClick = { showIntroduceDialog = true }
                )
            }

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

    if (showIntroduceDialog) {
        IntroduceContactDialog(
            contactToIntroduce = contact,
            onDismiss = { showIntroduceDialog = false },
            onIntroductionSent = {
                showIntroduceDialog = false
                onDismiss()
            }
        )
    }
}

// ======================== Shared Components ========================

@Composable
internal fun SheetHandle() {
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

// ======================== Introduce Contact Dialog ========================

@Composable
fun IntroduceContactDialog(
    contactToIntroduce: Contact,
    onDismiss: () -> Unit,
    onIntroductionSent: () -> Unit
) {
    val T = GhostTheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { GhostDatabase.getInstance(context) }
    var verifiedContacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var selectedTarget by remember { mutableStateOf<Contact?>(null) }
    var showConfirm by remember { mutableStateOf(false) }
    var isSending by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val all = db.contactDao().getAllOnce()
        verifiedContacts = all.filter { it.isVerified && it.id != contactToIntroduce.id }
    }

    if (showConfirm && selectedTarget != null) {
        AlertDialog(
            onDismissRequest = { if (!isSending) showConfirm = false },
            title = { Text("Cryptographic Introduction", color = T.PurpleLight, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Introduce ${contactToIntroduce.name} to ${selectedTarget!!.name}?\n\nThey will receive a signed cryptographic envelope with ${contactToIntroduce.name}'s identity keys.",
                    color = T.TextPrimary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        isSending = true
                        scope.launch {
                            val handler = GhostService.activeIntroductionHandler
                            if (handler != null) {
                                handler.sendIntroduction(contactToIntroduce, selectedTarget!!)
                            }
                            isSending = false
                            showConfirm = false
                            onIntroductionSent()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = T.Purple),
                    enabled = !isSending
                ) {
                    Text(if (isSending) "Signing & Sending..." else "Confirm & Sign", color = Color.White)
                }
            },
            dismissButton = {
                if (!isSending) {
                    TextButton(onClick = { showConfirm = false }) {
                        Text("Cancel", color = T.TextSecondary)
                    }
                }
            }
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    "Introduce ${contactToIntroduce.name} to...",
                    fontWeight = FontWeight.Bold,
                    color = T.TextPrimary
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 350.dp)) {
                    if (verifiedContacts.isEmpty()) {
                        Text(
                            "No other mutually verified contacts found. You can only introduce to contacts you have verified.",
                            color = T.TextMuted,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            items(verifiedContacts) { target ->
                                val targetPub = remember(target.ed25519PubKey) {
                                    try { Base64.decode(target.ed25519PubKey, Base64.NO_WRAP) } catch (_: Exception) { null }
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedTarget = target
                                            showConfirm = true
                                        }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    GhostAvatar(
                                        pubkey = targetPub,
                                        name = target.name,
                                        size = T.AvatarSmall,
                                        isMutuallyVerified = true
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = target.name,
                                            fontWeight = FontWeight.SemiBold,
                                            color = T.TextPrimary,
                                            fontSize = 15.sp
                                        )
                                        Text(
                                            text = "#${target.id.take(6)}",
                                            color = T.TextMuted,
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                                HorizontalDivider(color = T.Surface2)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Close", color = T.TextSecondary)
                }
            }
        )
    }
}

