package com.ghostprotocol.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostprotocol.GhostService
import com.ghostprotocol.IdentityManager
import com.ghostprotocol.ble.BleManager
import com.ghostprotocol.data.Contact
import com.ghostprotocol.data.GhostDatabase
import com.ghostprotocol.data.GroupEntity
import com.ghostprotocol.data.GroupMessageEntity
import com.ghostprotocol.group.GroupMessageSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatScreen(
    groupId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { GhostDatabase.getInstance(context) }
    val myContactId = remember { IdentityManager.getContactId() }

    var group by remember { mutableStateOf<GroupEntity?>(null) }
    var contactsMap by remember { mutableStateOf<Map<String, Contact>>(emptyMap()) }
    var showInfoSheet by remember { mutableStateOf(false) }

    val messages by db.groupMessageDao().getMessagesForGroup(groupId).collectAsState(initial = emptyList())
    val listState = rememberLazyListState()

    var inputText by remember { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<Pair<String, String>?>(null) } // Pair(senderName, previewText)

    // Load group and contact map
    LaunchedEffect(groupId) {
        withContext(Dispatchers.IO) {
            val g = db.groupDao().getById(groupId)
            group = g
            val allContacts = db.contactDao().getAllOnce()
            contactsMap = allContacts.associateBy { it.id }
        }
    }

    // Auto-scroll to bottom on new messages
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    val sender = remember {
        GhostService.activeGroupSender ?: GroupMessageSender(
            groupDao = db.groupDao(),
            contactDao = db.contactDao(),
            groupMessageDao = db.groupMessageDao(),
            routerProvider = { BleManager.getRouter() }
        )
    }

    val memberCount = remember(group) {
        try {
            group?.let { JSONArray(it.memberContactIdsJson).length() } ?: 0
        } catch (_: Exception) {
            0
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showInfoSheet = true }
                            .padding(4.dp)
                    ) {
                        HexagonAvatar(name = group?.name ?: "Cell", size = 36.dp)
                        Spacer(modifier = Modifier.width(GhostTheme.SpaceSm))
                        Column {
                            Text(
                                text = group?.name ?: "Cell Group",
                                color = GhostTheme.TextPrimary,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = "$memberCount MEMBERS · CELL",
                                color = GhostTheme.PurpleLight,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = GhostTheme.TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = GhostTheme.Surface0)
            )
        },
        containerColor = GhostTheme.Surface0
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Message List
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = GhostTheme.SpaceMd),
                contentPadding = PaddingValues(vertical = GhostTheme.SpaceSm)
            ) {
                items(messages, key = { it.id }) { message ->
                    val isOutgoing = message.senderContactId == myContactId
                    val senderName = if (isOutgoing) {
                        "You"
                    } else {
                        contactsMap[message.senderContactId]?.name ?: "Peer ${message.senderContactId.take(4)}"
                    }

                    GroupMessageBubble(
                        message = message,
                        senderName = senderName,
                        isOutgoing = isOutgoing,
                        onLongClick = {
                            replyTo = Pair(senderName, message.text)
                        }
                    )
                    Spacer(modifier = Modifier.height(GhostTheme.SpaceSm))
                }
            }

            // Reply Preview Banner
            replyTo?.let { reply ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(GhostTheme.Surface2)
                        .padding(horizontal = GhostTheme.SpaceMd, vertical = GhostTheme.SpaceXs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(28.dp)
                            .background(GhostTheme.Purple)
                    )
                    Spacer(modifier = Modifier.width(GhostTheme.SpaceSm))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Replying to ${reply.first}",
                            color = GhostTheme.PurpleLight,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                        Text(
                            text = reply.second.take(50),
                            color = GhostTheme.TextSecondary,
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }
                    IconButton(
                        onClick = { replyTo = null },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Cancel reply",
                            tint = GhostTheme.TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Chat Input Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(GhostTheme.Surface1)
                    .padding(horizontal = GhostTheme.SpaceMd, vertical = GhostTheme.SpaceSm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Encrypted cell message...", color = GhostTheme.TextMuted, fontSize = 14.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = GhostTheme.Purple,
                        unfocusedBorderColor = GhostTheme.Surface3,
                        focusedTextColor = GhostTheme.TextPrimary,
                        unfocusedTextColor = GhostTheme.TextPrimary,
                        cursorColor = GhostTheme.Purple
                    ),
                    shape = RoundedCornerShape(GhostTheme.RadiusInput),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp, max = 120.dp)
                )

                Spacer(modifier = Modifier.width(GhostTheme.SpaceSm))

                val canSend = inputText.trim().isNotEmpty()
                IconButton(
                    onClick = {
                        if (!canSend) return@IconButton
                        val textToSend = inputText.trim()
                        val currentReply = replyTo
                        inputText = ""
                        replyTo = null

                        scope.launch {
                            sender.sendGroupMessage(
                                groupId = groupId,
                                text = textToSend,
                                replyTo = currentReply
                            )
                        }
                    },
                    enabled = canSend,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (canSend) GhostTheme.Purple else GhostTheme.Surface2)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (canSend) GhostTheme.TextOnPurple else GhostTheme.TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }

    if (showInfoSheet) {
        GroupInfoBottomSheet(
            groupId = groupId,
            onDismiss = { showInfoSheet = false },
            onLeaveOrDelete = {
                showInfoSheet = false
                onBack()
            }
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun GroupMessageBubble(
    message: GroupMessageEntity,
    senderName: String,
    isOutgoing: Boolean,
    onLongClick: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val timeStr = remember(message.timestamp) { timeFormat.format(Date(message.timestamp)) }

    // Violet shimmer border animation for STATUS_SPRAYED
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmerAlpha"
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start
    ) {
        // Sender Name for incoming messages
        if (!isOutgoing) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
            ) {
                Text(
                    text = senderName,
                    color = GhostTheme.PurpleLight,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }
        }

        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (!isOutgoing) {
                HexagonAvatar(name = senderName, size = 26.dp)
                Spacer(modifier = Modifier.width(6.dp))
            }

            val bubbleColor = if (isOutgoing) GhostTheme.PurpleDark else GhostTheme.Surface2
            val isSprayed = isOutgoing && message.status == GroupMessageEntity.STATUS_SPRAYED

            Box(
                modifier = Modifier
                    .widthIn(max = GhostTheme.BubbleMaxWidth)
                    .clip(
                        RoundedCornerShape(
                            topStart = GhostTheme.RadiusBubble,
                            topEnd = GhostTheme.RadiusBubble,
                            bottomStart = if (isOutgoing) GhostTheme.RadiusBubble else GhostTheme.RadiusBubbleSharp,
                            bottomEnd = if (isOutgoing) GhostTheme.RadiusBubbleSharp else GhostTheme.RadiusBubble
                        )
                    )
                    .background(bubbleColor)
                    .then(
                        if (isSprayed) {
                            Modifier.border(
                                width = 1.dp,
                                brush = Brush.linearGradient(
                                    listOf(
                                        GhostTheme.PurpleLight.copy(alpha = shimmerAlpha),
                                        GhostTheme.Purple.copy(alpha = shimmerAlpha * 0.5f)
                                    )
                                ),
                                shape = RoundedCornerShape(
                                    topStart = GhostTheme.RadiusBubble,
                                    topEnd = GhostTheme.RadiusBubble,
                                    bottomStart = if (isOutgoing) GhostTheme.RadiusBubble else GhostTheme.RadiusBubbleSharp,
                                    bottomEnd = if (isOutgoing) GhostTheme.RadiusBubbleSharp else GhostTheme.RadiusBubble
                                )
                            )
                        } else Modifier
                    )
                    .combinedClickable(
                        onClick = {},
                        onLongClick = onLongClick
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Column {
                    // Quoted message inside bubble if replying
                    if (message.replyToSender != null && message.replyToText != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Black.copy(alpha = 0.25f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(2.dp)
                                    .height(24.dp)
                                    .background(GhostTheme.PurpleLight)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(
                                    text = message.replyToSender,
                                    color = GhostTheme.PurpleLight,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                )
                                Text(
                                    text = message.replyToText,
                                    color = GhostTheme.TextSecondary,
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    Text(
                        text = message.text,
                        color = GhostTheme.TextPrimary,
                        fontSize = 14.sp
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Row(
                        modifier = Modifier.align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = timeStr,
                            color = GhostTheme.TextMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )

                        if (isOutgoing) {
                            Spacer(modifier = Modifier.width(4.dp))
                            when (message.status) {
                                GroupMessageEntity.STATUS_PENDING -> {
                                    Text(
                                        text = "•",
                                        color = GhostTheme.TextMuted,
                                        fontSize = 12.sp
                                    )
                                }
                                GroupMessageEntity.STATUS_SPRAYED -> {
                                    Text(
                                        text = "MESH",
                                        color = GhostTheme.PurpleLight,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 9.sp
                                    )
                                }
                                GroupMessageEntity.STATUS_DELIVERED, GroupMessageEntity.STATUS_SENT -> {
                                    Icon(
                                        Icons.Default.DoneAll,
                                        contentDescription = "Delivered",
                                        tint = GhostTheme.SentCheck,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                                GroupMessageEntity.STATUS_FAILED -> {
                                    Text(
                                        text = "!",
                                        color = GhostTheme.Failed,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
