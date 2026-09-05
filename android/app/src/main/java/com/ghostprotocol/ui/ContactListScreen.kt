package com.ghostprotocol.ui

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.ghostprotocol.GhostService
import com.ghostprotocol.ble.BleManager
import com.ghostprotocol.data.ConversationItem
import com.ghostprotocol.data.ConversationRepository
import com.ghostprotocol.data.GroupMessageEntity
import com.ghostprotocol.data.MessageEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.*

class ContactListViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = ConversationRepository.getInstance(application)

    val conversations: StateFlow<List<ConversationItem>> = repo.getConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactListScreen(
    navController: NavController,
    viewModel: ContactListViewModel = viewModel()
) {
    val conversations by viewModel.conversations.collectAsStateWithLifecycle(initialValue = emptyList())
    val blePeers by BleManager.peers.collectAsStateWithLifecycle(initialValue = emptyList())
    var searchQuery by remember { mutableStateOf("") }
    var showNewChatSheet by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val currentPowerPolicy by GhostService.currentPowerPolicy.collectAsStateWithLifecycle()
    val batteryPercent = remember {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        if (scale > 0) (level * 100) / scale else -1
    }

    val filteredConversations = remember(conversations, searchQuery) {
        if (searchQuery.isBlank()) {
            conversations
        } else {
            conversations.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                it.id.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Scaffold(
        containerColor = GhostTheme.Surface0,
        topBar = {
            Surface(color = GhostTheme.Surface0) {
                Column(
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(horizontal = GhostTheme.SpaceMd, vertical = GhostTheme.SpaceSm)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "GHOST",
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = GhostTheme.TextPrimary,
                            letterSpacing = 2.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Survival HUD 1-Tap Toggle
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (GhostTheme.isSurvivalHudEnabled) GhostTheme.SurvivalPhosphor.copy(alpha = 0.2f) else GhostTheme.Surface2)
                                    .border(
                                        1.dp,
                                        if (GhostTheme.isSurvivalHudEnabled) GhostTheme.SurvivalPhosphor else Color.Transparent,
                                        RoundedCornerShape(12.dp)
                                    )
                                    .clickable {
                                        GhostTheme.toggleSurvivalHud(context)
                                    }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = if (GhostTheme.isSurvivalHudEnabled) "⚡ HUD ON" else "⚡ HUD",
                                    color = if (GhostTheme.isSurvivalHudEnabled) GhostTheme.SurvivalPhosphor else GhostTheme.TextSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Spacer(modifier = Modifier.width(GhostTheme.SpaceSm))
                            IconButton(
                                onClick = { navController.navigate("settings") },
                                modifier = Modifier.size(GhostTheme.MinTouchTarget)
                            ) {
                                Icon(
                                    Icons.Default.Settings,
                                    contentDescription = "Settings",
                                    tint = GhostTheme.TextSecondary
                                )
                            }
                        }
                    }

                    // Persistent Tactical Survival HUD strip when enabled
                    SurvivalHudStrip(
                        batteryPercent = batteryPercent,
                        powerPolicy = currentPowerPolicy,
                        peerCount = blePeers.size,
                        onToggle = { GhostTheme.toggleSurvivalHud(context) }
                    )

                    Spacer(modifier = Modifier.height(GhostTheme.SpaceSm))

                    // Clean Search bar
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(GhostTheme.RadiusInput)),
                        placeholder = {
                            Text(
                                "Search contacts and cells...",
                                color = GhostTheme.TextMuted,
                                fontSize = 14.sp
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = "Search",
                                tint = GhostTheme.TextMuted
                            )
                        },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = GhostTheme.Surface2,
                            unfocusedContainerColor = GhostTheme.Surface1,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = GhostTheme.Purple,
                            focusedTextColor = GhostTheme.TextPrimary,
                            unfocusedTextColor = GhostTheme.TextPrimary
                        ),
                        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
                    )
                }
            }
        },
        floatingActionButton = {
            GhostActionFab(
                contentDescription = "New Conversation or Cell",
                onClick = { showNewChatSheet = true }
            )
        }
    ) { padding ->
        when {
            conversations.isEmpty() && searchQuery.isBlank() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    GhostEmptyState(
                        icon = Icons.Default.QrCodeScanner,
                        title = "Your mesh is quiet",
                        subtitle = "No active conversations. Scan a peer's QR code or create an encrypted Cell Group to begin.",
                        actionText = "Scan QR Code",
                        onAction = { navController.navigate("qr_scan") }
                    )
                }
            }
            filteredConversations.isEmpty() && searchQuery.isNotBlank() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    GhostEmptyState(
                        icon = Icons.Default.Search,
                        title = "No conversations found",
                        subtitle = "No contacts or cells match \"$searchQuery\"."
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .background(GhostTheme.Surface0),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(filteredConversations, key = { it.key }) { item ->
                        when (item) {
                            is ConversationItem.Direct -> {
                                DirectConversationRow(
                                    item = item,
                                    onClick = {
                                        navController.navigate("chat/${item.id}") {
                                            launchSingleTop = true
                                        }
                                    }
                                )
                            }
                            is ConversationItem.Group -> {
                                GroupConversationRow(
                                    item = item,
                                    onClick = {
                                        navController.navigate("group_chat/${item.id}") {
                                            launchSingleTop = true
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNewChatSheet) {
        val sheetState = rememberModalBottomSheetState()
        NewChatBottomSheet(
            sheetState = sheetState,
            onDismiss = { showNewChatSheet = false },
            onNewCellGroup = {
                showNewChatSheet = false
                navController.navigate("group_creation")
            },
            onScanQr = {
                showNewChatSheet = false
                navController.navigate("qr_scan")
            },
            onShowQr = {
                showNewChatSheet = false
                navController.navigate("qr_show")
            },
            onAddShortCode = {
                showNewChatSheet = false
                navController.navigate("short_code_input")
            }
        )
    }
}

@Composable
private fun DirectConversationRow(
    item: ConversationItem.Direct,
    onClick: () -> Unit
) {
    val ed25519Bytes = remember(item.ed25519PubKeyBase64) {
        try {
            Base64.decode(item.ed25519PubKeyBase64, Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }
    val timeFormatted = remember(item.lastMessageTime) {
        formatConversationTime(item.lastMessageTime)
    }

    Surface(
        color = GhostTheme.Surface0,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = GhostTheme.SpaceMd, vertical = GhostTheme.SpaceSm)
                .fillMaxWidth()
                .defaultMinSize(minHeight = GhostTheme.MinTouchTarget),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GhostAvatar(
                pubkey = ed25519Bytes,
                name = item.name,
                size = GhostTheme.AvatarMedium,
                isMutuallyVerified = item.isVerified,
                animateEtherealRing = false
            )

            Spacer(modifier = Modifier.width(GhostTheme.SpaceMd))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        Text(
                            text = item.name,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = GhostTheme.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (item.isVerified) {
                            Spacer(modifier = Modifier.width(6.dp))
                            GhostBadge(
                                text = "VERIFIED",
                                containerColor = GhostTheme.Surface2,
                                contentColor = GhostTheme.PurpleLight
                            )
                        } else if (item.isIntroduced) {
                            Spacer(modifier = Modifier.width(6.dp))
                            GhostBadge(
                                text = "INTRODUCED",
                                containerColor = GhostTheme.Surface2,
                                contentColor = GhostTheme.TextSecondary
                            )
                        }
                    }
                    if (timeFormatted.isNotBlank()) {
                        Text(
                            text = timeFormatted,
                            fontSize = 11.sp,
                            color = GhostTheme.TextMuted,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(modifier = Modifier.height(3.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (item.lastMessageIsOutgoing && item.lastMessageStatus != null) {
                            GhostStatusIndicator(status = item.lastMessageStatus)
                            Spacer(modifier = Modifier.width(4.dp))
                        }

                        Text(
                            text = item.lastMessageText ?: "#${item.id.take(8)}",
                            fontSize = 13.sp,
                            color = if (item.lastMessageText != null) GhostTheme.TextSecondary else GhostTheme.TextMuted,
                            fontFamily = if (item.lastMessageText != null) FontFamily.Default else FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    RadioProximityWave(
                        rssi = item.directRssi,
                        isOnline = item.isDirectRadio
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupConversationRow(
    item: ConversationItem.Group,
    onClick: () -> Unit
) {
    val timeFormatted = remember(item.lastMessageTime) {
        formatConversationTime(item.lastMessageTime)
    }

    Surface(
        color = GhostTheme.Surface0,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = GhostTheme.SpaceMd, vertical = GhostTheme.SpaceSm)
                .fillMaxWidth()
                .defaultMinSize(minHeight = GhostTheme.MinTouchTarget),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HexagonAvatar(name = item.name, size = GhostTheme.AvatarMedium)

            Spacer(modifier = Modifier.width(GhostTheme.SpaceMd))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        Text(
                            text = item.name,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = GhostTheme.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        GhostBadge(
                            text = "CELL",
                            containerColor = GhostTheme.Purple,
                            contentColor = Color.White
                        )
                    }
                    if (timeFormatted.isNotBlank()) {
                        Text(
                            text = timeFormatted,
                            fontSize = 11.sp,
                            color = GhostTheme.TextMuted,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(modifier = Modifier.height(3.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (item.lastMessageIsOutgoing && item.lastMessageStatus != null) {
                            GhostStatusIndicator(status = item.lastMessageStatus)
                            Spacer(modifier = Modifier.width(4.dp))
                        }

                        val previewText = when {
                            item.lastMessageText != null -> {
                                val sender = item.lastMessageSenderName?.let { "$it: " } ?: ""
                                "$sender${item.lastMessageText}"
                            }
                            else -> "${item.memberCount} members · Private mesh cell"
                        }

                        Text(
                            text = previewText,
                            fontSize = 13.sp,
                            color = if (item.lastMessageText != null) GhostTheme.TextSecondary else GhostTheme.TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = "MESH",
                        color = GhostTheme.PurpleLight,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

private fun formatConversationTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val now = Calendar.getInstance()
    val time = Calendar.getInstance().apply { timeInMillis = timestamp }
    return when {
        now.get(Calendar.YEAR) == time.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == time.get(Calendar.DAY_OF_YEAR) -> {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        }
        now.get(Calendar.YEAR) == time.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) - time.get(Calendar.DAY_OF_YEAR) == 1 -> {
            "Yesterday"
        }
        else -> {
            SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
        }
    }
}
