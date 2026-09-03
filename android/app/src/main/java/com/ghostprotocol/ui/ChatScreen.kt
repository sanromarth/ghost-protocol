package com.ghostprotocol.ui

import android.app.Application
import android.util.Base64
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.ghostprotocol.IdentityManager
import com.ghostprotocol.ble.BleManager
import com.ghostprotocol.crypto.GhostCrypto
import com.ghostprotocol.data.Contact
import com.ghostprotocol.data.GhostDatabase
import com.ghostprotocol.data.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

// ======================== ViewModel ========================

class ChatViewModel(application: Application, private val contactId: String) : AndroidViewModel(application) {
    private val db = GhostDatabase.getInstance(application)
    private val contactDao = db.contactDao()
    private val messageDao = db.messageDao()

    private val _contact = MutableStateFlow<Contact?>(null)
    val contact: StateFlow<Contact?> = _contact

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending

    val messages: StateFlow<List<MessageEntity>> = messageDao.getForContact(contactId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _contact.value = contactDao.getById(contactId)
        }
    }

    fun sendMessage(
        text: String,
        replyTo: MessageEntity? = null,
        replySenderName: String? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            // Re-entrancy guard: prevent duplicate sends on rapid taps
            if (!_isSending.compareAndSet(false, true)) return@launch
            var messageId: String? = null
            try {
                val freshContact = contactDao.getById(contactId) ?: return@launch
                _contact.value = freshContact

                val myEd25519PubKey = IdentityManager.getEd25519PubKey()
                val myName = IdentityManager.getDisplayName()
                // Reply wire format: senderName\u0000REPLY\u0000quotedSender\u0000quotedText\u0000message
                // Backward-compatible: non-reply messages use senderName\u0000message (no REPLY token)
                val plaintextBytes = if (replyTo != null) {
                    val quotedSender = if (replyTo.isOutgoing) "You" else (replySenderName ?: "Contact")
                    val quotedText = replyTo.content.take(120)
                    (myName + "\u0000REPLY\u0000" + quotedSender + "\u0000" + quotedText + "\u0000" + text).toByteArray(Charsets.UTF_8)
                } else {
                    (myName + "\u0000" + text).toByteArray(Charsets.UTF_8)
                }

                val payload = myEd25519PubKey + plaintextBytes
                val signature = GhostCrypto.sign(IdentityManager.getEd25519Seed(), payload)
                val fullPayload = payload + signature
                val contactX25519Pub = Base64.decode(freshContact.x25519PubKey, Base64.NO_WRAP)
                val ciphertext = GhostCrypto.encrypt(contactX25519Pub, fullPayload)

                val message = MessageEntity(
                    id = UUID.randomUUID().toString(),
                    contactId = contactId,
                    content = text,
                    isOutgoing = true,
                    timestamp = System.currentTimeMillis(),
                    isVerified = true,
                    status = MessageEntity.STATUS_PENDING,
                    replyToId = replyTo?.id,
                    replyToSender = if (replyTo != null) (if (replyTo.isOutgoing) "You" else (replySenderName ?: "Contact")) else null,
                    replyToText = replyTo?.content?.take(120)
                )
                messageId = message.id
                messageDao.insert(message)

                val contactEd25519Pub = Base64.decode(freshContact.ed25519PubKey, Base64.NO_WRAP)
                val dstId = java.security.MessageDigest.getInstance("SHA-256").digest(contactEd25519Pub)

                val router = BleManager.getRouter()
                if (router != null) {
                    val (isDirect, blob) = router.sendMessage(dstId, ciphertext)
                    if (isDirect && blob != null && freshContact.bleAddress != null) {
                        BleManager.sendMessage(freshContact.bleAddress, blob) { success ->
                            viewModelScope.launch(Dispatchers.IO) {
                                if (success) {
                                    messageDao.updateStatus(message.id, MessageEntity.STATUS_SENT)
                                } else {
                                    // Direct send failed (peer disconnected, screen off, or out of range)
                                    // Hold as SPRAYED in Room (already queued in Go router on line 135 for spray delivery)
                                    messageDao.updateStatus(message.id, MessageEntity.STATUS_SPRAYED)
                                }
                            }
                        }
                    } else {
                        // Peer offline or no direct BLE address — queued in mesh router
                        messageDao.updateStatus(message.id, MessageEntity.STATUS_SPRAYED)
                    }
                } else if (freshContact.bleAddress != null) {
                    BleManager.sendMessage(freshContact.bleAddress, ciphertext) { success ->
                        viewModelScope.launch(Dispatchers.IO) {
                            messageDao.updateStatus(
                                message.id,
                                if (success) MessageEntity.STATUS_SENT else MessageEntity.STATUS_SPRAYED
                            )
                        }
                    }
                } else {
                    messageDao.updateStatus(message.id, MessageEntity.STATUS_SPRAYED)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // Don't leave message spinning forever — mark as SPRAYED to allow DTN delivery
                messageId?.let { id ->
                    try { messageDao.updateStatus(id, MessageEntity.STATUS_SPRAYED) } catch (_: Exception) {}
                }
            } finally {
                _isSending.value = false
            }
        }
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            messageDao.deleteById(messageId)
        }
    }

    fun clearChat() {
        viewModelScope.launch(Dispatchers.IO) {
            messageDao.deleteForContact(contactId)
        }
    }

    fun deleteContact(onDeleted: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            messageDao.deleteForContact(contactId)
            contactDao.getById(contactId)?.let { contactDao.delete(it) }
            withContext(Dispatchers.Main) {
                onDeleted()
            }
        }
    }

    fun retryMessage(message: MessageEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val freshContact = contactDao.getById(contactId) ?: return@launch
            messageDao.updateStatus(message.id, MessageEntity.STATUS_PENDING)
            try {
                val contactX25519Pub = Base64.decode(freshContact.x25519PubKey, Base64.NO_WRAP)
                val myEd25519PubKey = IdentityManager.getEd25519PubKey()
                val myName = IdentityManager.getDisplayName()
                val plaintextBytes = (myName + "\u0000" + message.content).toByteArray(Charsets.UTF_8)
                val payload = myEd25519PubKey + plaintextBytes
                val signature = GhostCrypto.sign(IdentityManager.getEd25519Seed(), payload)
                val fullPayload = payload + signature
                val ciphertext = GhostCrypto.encrypt(contactX25519Pub, fullPayload)

                val contactEd25519Pub = Base64.decode(freshContact.ed25519PubKey, Base64.NO_WRAP)
                val dstId = java.security.MessageDigest.getInstance("SHA-256").digest(contactEd25519Pub)

                val router = BleManager.getRouter()
                if (router != null) {
                    val (isDirect, blob) = router.sendMessage(dstId, ciphertext)
                    if (isDirect && blob != null && freshContact.bleAddress != null) {
                        BleManager.sendMessage(freshContact.bleAddress, blob) { success ->
                            viewModelScope.launch(Dispatchers.IO) {
                                messageDao.updateStatus(
                                    message.id,
                                    if (success) MessageEntity.STATUS_SENT else MessageEntity.STATUS_SPRAYED
                                )
                            }
                        }
                    } else {
                        messageDao.updateStatus(message.id, MessageEntity.STATUS_SPRAYED)
                    }
                } else if (freshContact.bleAddress != null) {
                    BleManager.sendMessage(freshContact.bleAddress, ciphertext) { success ->
                        viewModelScope.launch(Dispatchers.IO) {
                            messageDao.updateStatus(message.id, if (success) MessageEntity.STATUS_SENT else MessageEntity.STATUS_SPRAYED)
                        }
                    }
                } else {
                    messageDao.updateStatus(message.id, MessageEntity.STATUS_SPRAYED)
                }
            } catch (e: Exception) {
                messageDao.updateStatus(message.id, MessageEntity.STATUS_SPRAYED)
            }
        }
    }
}

class ChatViewModelFactory(private val application: Application, private val contactId: String) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ChatViewModel(application, contactId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

// ======================== Chat Screen ========================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(contactId: String, navController: NavController, application: Application = navController.context.applicationContext as Application) {
    val viewModel: ChatViewModel = viewModel(factory = ChatViewModelFactory(application, contactId))
    val contact by viewModel.contact.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle(initialValue = emptyList())
    val isSending by viewModel.isSending.collectAsStateWithLifecycle()
    var inputText by remember { mutableStateOf("") }
    val haptics = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboardManager: ClipboardManager = LocalClipboardManager.current
    val T = GhostTheme
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1_000L)
            currentTime = System.currentTimeMillis()
        }
    }

    // Reply mode
    var replyToMessage by remember { mutableStateOf<MessageEntity?>(null) }

    // Bottom sheets
    var showMessageActions by remember { mutableStateOf(false) }
    var selectedMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var showContactInfo by remember { mutableStateOf(false) }
    val messageSheetState = rememberModalBottomSheetState()
    val contactSheetState = rememberModalBottomSheetState()

    val showScrollFab by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 3 }
    }

    val messageCount = messages.size
    LaunchedEffect(messageCount) {
        if (listState.firstVisibleItemIndex <= 3) {
            listState.animateScrollToItem(0)
        }
    }

    // Build grouped message items (messages + time separators)
    val reversedMessages = messages.reversed()
    val chatItems = remember(reversedMessages) { buildChatItems(reversedMessages) }

    Scaffold(
        containerColor = T.Surface0,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            // Premium app bar
            Surface(
                color = T.Surface0,
                tonalElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = T.TextPrimary
                        )
                    }

                    val isMutuallyVerified = messages.any { it.content.startsWith("* mutual verification with ") } || contact?.isVerified == true
                    val blePeers by BleManager.peers.collectAsState()
                    val matchedPeer = contact?.let { c ->
                        blePeers.find { peer ->
                            val matchesAddress = c.bleAddress != null && peer.address == c.bleAddress
                            val matchesFp = peer.fingerprint != null && try {
                                val contactFp = java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(Base64.decode(c.ed25519PubKey, Base64.NO_WRAP))
                                    .copyOfRange(0, 4)
                                peer.fingerprint.contentEquals(contactFp)
                            } catch (_: Exception) { false }
                            (matchesAddress || matchesFp) && (currentTime - peer.lastSeen < BleManager.PEER_OFFLINE_TIMEOUT_MS)
                        }
                    }
                    val isOnline = matchedPeer != null

                    // Avatar with Ghost Aura / Ethereal Ring — tap for contact info
                    if (contact != null) {
                        val ed25519Bytes = remember(contact!!.ed25519PubKey) {
                            try { Base64.decode(contact!!.ed25519PubKey, Base64.NO_WRAP) } catch (_: Exception) { null }
                        }
                        GhostAvatar(
                            pubkey = ed25519Bytes,
                            name = contact!!.name,
                            size = T.AvatarSmall,
                            isMutuallyVerified = isMutuallyVerified,
                            onClick = { showContactInfo = true }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            contact?.name ?: "Chat",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 18.sp,
                            color = T.TextPrimary
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "#" + (contact?.id?.take(6) ?: ""),
                                fontSize = 12.sp,
                                color = T.TextSecondary,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            RadioProximityWave(
                                rssi = matchedPeer?.rssi,
                                isOnline = isOnline
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            // Premium input bar
            Surface(
                color = T.Surface1,
                tonalElevation = 0.dp
            ) {
                Column(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .imePadding()
                ) {
                    // Reply bar (above input)
                    ReplyBar(
                        quotedMessage = replyToMessage,
                        quotedSenderName = contact?.name ?: "Contact",
                        onCancel = { replyToMessage = null },
                        onTapPreview = {
                            replyToMessage?.let { msg ->
                                val idx = chatItems.indexOfFirst {
                                    it is ChatItem.Msg && it.message.id == msg.id
                                }
                                if (idx >= 0) {
                                    scope.launch { listState.animateScrollToItem(idx) }
                                }
                            }
                        }
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                    // Input field
                    TextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(T.RadiusInput)),
                        placeholder = {
                            Text(
                                "Type a secure message...",
                                color = T.TextMuted,
                                fontSize = 15.sp
                            )
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = T.Surface3,
                            unfocusedContainerColor = T.Surface2,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = T.Purple,
                            focusedTextColor = T.TextPrimary,
                            unfocusedTextColor = T.TextPrimary
                        ),
                        maxLines = 4,
                        enabled = !isSending,
                        textStyle = LocalTextStyle.current.copy(fontSize = 15.sp)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    // Send button
                    val sendEnabled = inputText.isNotBlank() && !isSending
                    Box(
                        modifier = Modifier
                            .size(T.SendButtonSize)
                            .clip(CircleShape)
                            .background(
                                if (sendEnabled) T.Purple else T.Surface3
                            )
                            .then(
                                if (sendEnabled) {
                                    Modifier.clickable {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        viewModel.sendMessage(
                                            text = inputText,
                                            replyTo = replyToMessage,
                                            replySenderName = contact?.name
                                        )
                                        inputText = ""
                                        replyToMessage = null
                                    }
                                } else Modifier
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                        } else {
                            Text(
                                "➤",
                                fontSize = 18.sp,
                                color = if (sendEnabled) Color.White else T.TextMuted
                            )
                        }
                    }
                    }
                }
            }
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = showScrollFab,
                enter = scaleIn(animationSpec = spring(dampingRatio = 0.6f)) + fadeIn(),
                exit = scaleOut() + fadeOut()
            ) {
                SmallFloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    containerColor = T.Surface2,
                    contentColor = T.TextPrimary,
                    modifier = Modifier.padding(bottom = 80.dp)
                ) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Scroll to bottom", modifier = Modifier.size(20.dp))
                }
            }
        }
    ) { padding ->
        if (messages.isEmpty()) {
            // Premium empty state
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(T.Surface0),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("💬", fontSize = 56.sp)
                    Spacer(modifier = Modifier.height(T.SpaceMd))
                    Text(
                        "No messages yet",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = T.TextSecondary
                    )
                    Spacer(modifier = Modifier.height(T.SpaceSm))
                    Text(
                        "Say hi to start the conversation 👋",
                        fontSize = 14.sp,
                        color = T.TextMuted,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(T.Surface0)
                    .padding(horizontal = 12.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                reverseLayout = true
            ) {
                items(chatItems, key = { it.key }) { item ->
                    when (item) {
                        is ChatItem.TimeHeader -> TimeHeaderBubble(item.label)
                        is ChatItem.Msg -> {
                            if (item.message.content.startsWith("* ") && item.message.content.endsWith(" *")) {
                                SystemVerificationBubble(item.message)
                            } else {
                                SwipeableMessage(
                                    onReply = { replyToMessage = item.message },
                                    onCopy = {
                                        clipboardManager.setText(AnnotatedString(item.message.content))
                                    },
                                    onDelete = {
                                        selectedMessage = item.message
                                        showMessageActions = true
                                    }
                                ) {
                                    PremiumMessageBubble(
                                        message = item.message,
                                        groupPosition = item.groupPosition,
                                        onRetry = { viewModel.retryMessage(it) },
                                        onLongPress = {
                                            selectedMessage = item.message
                                            showMessageActions = true
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Bottom Sheets ──

    if (showMessageActions && selectedMessage != null) {
        MessageActionsBottomSheet(
            message = selectedMessage!!,
            sheetState = messageSheetState,
            onDismiss = { showMessageActions = false },
            onReply = {
                replyToMessage = selectedMessage
            },
            onCopy = {
                clipboardManager.setText(AnnotatedString(selectedMessage!!.content))
            },
            onDelete = {
                selectedMessage?.let { viewModel.deleteMessage(it.id) }
            }
        )
    }

    if (showContactInfo && contact != null) {
        ContactInfoBottomSheet(
            contact = contact!!,
            sheetState = contactSheetState,
            onDismiss = { showContactInfo = false },
            onShowQR = { navController.navigate("qr_show") },
            onClearChat = { viewModel.clearChat() },
            onDeleteContact = {
                viewModel.deleteContact {
                    navController.popBackStack()
                }
            }
        )
    }
}

// ======================== Chat Items + Grouping ========================

enum class GroupPosition { SINGLE, FIRST, MIDDLE, LAST }

sealed class ChatItem(val key: String) {
    class TimeHeader(val label: String, val timestamp: Long) : ChatItem("time_$timestamp")
    class Msg(val message: MessageEntity, val groupPosition: GroupPosition) : ChatItem("msg_${message.id}")
}

private fun buildChatItems(messages: List<MessageEntity>): List<ChatItem> {
    if (messages.isEmpty()) return emptyList()
    val items = mutableListOf<ChatItem>()

    for (i in messages.indices) {
        val msg = messages[i]
        val prev = messages.getOrNull(i - 1)
        val next = messages.getOrNull(i + 1)

        // Time header: >5 min gap between consecutive messages
        if (prev == null || (msg.timestamp - prev.timestamp).let { kotlin.math.abs(it) } > 5 * 60 * 1000) {
            items.add(ChatItem.TimeHeader(formatTimeHeader(msg.timestamp), msg.timestamp))
        }

        // Group position
        val sameSenderAsPrev = prev != null && prev.isOutgoing == msg.isOutgoing &&
                (msg.timestamp - prev.timestamp).let { kotlin.math.abs(it) } <= 60_000
        val sameSenderAsNext = next != null && next.isOutgoing == msg.isOutgoing &&
                (next.timestamp - msg.timestamp).let { kotlin.math.abs(it) } <= 60_000

        val position = when {
            !sameSenderAsPrev && !sameSenderAsNext -> GroupPosition.SINGLE
            !sameSenderAsPrev && sameSenderAsNext -> GroupPosition.FIRST
            sameSenderAsPrev && sameSenderAsNext -> GroupPosition.MIDDLE
            else -> GroupPosition.LAST
        }
        items.add(ChatItem.Msg(msg, position))
    }
    return items
}

private fun formatTimeHeader(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val cal = java.util.Calendar.getInstance()
    val todayCal = java.util.Calendar.getInstance()

    cal.timeInMillis = timestamp

    return when {
        diff < 86_400_000L && cal.get(java.util.Calendar.DAY_OF_YEAR) == todayCal.get(java.util.Calendar.DAY_OF_YEAR) -> {
            "Today, ${java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(timestamp))}"
        }
        diff < 172_800_000L -> {
            "Yesterday, ${java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(timestamp))}"
        }
        else -> {
            java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
        }
    }
}

// ======================== Time Header ========================

@Composable
fun TimeHeaderBubble(label: String) {
    val T = GhostTheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = T.TextMuted,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .background(T.Surface1, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun SystemVerificationBubble(message: MessageEntity) {
    val T = GhostTheme
    val timeStr = remember(message.timestamp) {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        sdf.format(java.util.Date(message.timestamp))
    }
    val isMutual = message.content.contains("mutual verification")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(if (isMutual) T.Online.copy(alpha = 0.12f) else T.Surface2.copy(alpha = 0.5f))
                .border(
                    width = 1.dp,
                    color = if (isMutual) T.Online.copy(alpha = 0.4f) else T.Surface3,
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 12.dp, vertical = 5.dp)
        ) {
            Text(
                text = "${message.content} [$timeStr]",
                color = if (isMutual) T.Online else T.TextMuted,
                fontSize = 12.sp,
                fontWeight = if (isMutual) FontWeight.SemiBold else FontWeight.Normal,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )
        }
    }
}

// ======================== Premium Message Bubble ========================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PremiumMessageBubble(
    message: MessageEntity,
    groupPosition: GroupPosition,
    onRetry: (MessageEntity) -> Unit = {},
    onLongPress: () -> Unit = {}
) {
    val T = GhostTheme
    val haptics = LocalHapticFeedback.current
    val isSent = message.isOutgoing

    // Slide-in animation
    val slideOffset = remember { Animatable(if (isSent) 40f else -40f) }
    val alphaAnim = remember { Animatable(0f) }
    LaunchedEffect(message.id) {
        launch { slideOffset.animateTo(0f, tween(180, easing = FastOutSlowInEasing)) }
        launch { alphaAnim.animateTo(1f, tween(180)) }
    }

    val isSprayed = message.status == MessageEntity.STATUS_SPRAYED
    val isSurvival = T.isSurvivalHudEnabled

    // Pulse animation for SPRAYED (disabled in Survival HUD mode to conserve battery)
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by if (!isSurvival && isSprayed) {
        infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.25f,
            animationSpec = infiniteRepeatable(
                animation = tween(750, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseScale"
        )
    } else {
        remember { mutableStateOf(1f) }
    }

    val shimmerAlpha by if (!isSurvival && isSprayed) {
        infiniteTransition.animateFloat(
            initialValue = 0.35f,
            targetValue = 0.95f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "shimmerAlpha"
        )
    } else {
        remember { mutableStateOf(0.6f) }
    }

    // Grouped bubble shape — tighter corners for middle messages
    val bubbleShape = getBubbleShape(isSent, groupPosition)

    // Vertical spacing based on group position
    val topPad = when (groupPosition) {
        GroupPosition.SINGLE, GroupPosition.FIRST -> 4.dp
        else -> 1.dp
    }

    // Delay-Tolerant Sprayed physics border: pulsing ethereal neon shimmer while in transit
    val bubbleBorder = when {
        isSprayed && isSurvival -> Modifier.border(1.dp, T.SurvivalAmber, bubbleShape)
        isSprayed -> Modifier.border(
            1.5.dp,
            Brush.linearGradient(
                listOf(
                    T.NeonViolet1.copy(alpha = shimmerAlpha),
                    T.NeonViolet2.copy(alpha = shimmerAlpha),
                    T.NeonViolet3.copy(alpha = shimmerAlpha)
                )
            ),
            bubbleShape
        )
        isSent && message.status == MessageEntity.STATUS_DELIVERED -> Modifier.border(
            1.dp,
            T.Online.copy(alpha = 0.35f),
            bubbleShape
        )
        else -> Modifier
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPad)
            .offset(x = slideOffset.value.dp)
            .graphicsLayer { alpha = alphaAnim.value },
        horizontalAlignment = if (isSent) Alignment.End else Alignment.Start
    ) {
        Box {
            Surface(
                shape = bubbleShape,
                shadowElevation = if (isSent) 3.dp else 1.dp,
                color = Color.Transparent,
                modifier = Modifier.widthIn(max = T.BubbleMaxWidth)
            ) {
                Box(
                    modifier = Modifier
                        .then(bubbleBorder)
                        .then(
                            if (isSent) {
                                Modifier.background(
                                    brush = Brush.linearGradient(
                                        colors = listOf(T.Purple, T.PurpleDark)
                                    )
                                )
                            } else {
                                Modifier.background(T.Surface2)
                            }
                        )
                        .combinedClickable(
                            onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onLongPress()
                            },
                            onClick = {}
                        )
                        .padding(
                            start = 14.dp, end = 14.dp,
                            top = 8.dp, bottom = 6.dp
                        )
                ) {
                    Column {
                        // Quoted reply preview (if this message is a reply)
                        if (message.replyToText != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 6.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (isSent) Color.White.copy(alpha = 0.15f) else T.Surface3
                                    )
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(28.dp)
                                        .clip(RoundedCornerShape(1.5.dp))
                                        .background(if (isSent) Color.White else T.Purple)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = message.replyToSender ?: "Replied message",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSent) Color.White else T.Purple,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = message.replyToText,
                                        fontSize = 12.sp,
                                        color = if (isSent) Color.White.copy(alpha = 0.85f) else T.TextSecondary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        // Message text
                        Text(
                            text = message.content,
                            color = if (isSent) T.TextOnPurple else T.TextPrimary,
                            fontSize = 15.sp,
                            lineHeight = 20.sp
                        )

                        // Timestamp + status — inline at bottom-right
                        Row(
                            modifier = Modifier
                                .align(Alignment.End)
                                .padding(top = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = formatRelativeTime(message.timestamp),
                                fontSize = 11.sp,
                                color = if (isSent) Color.White.copy(alpha = 0.6f) else T.TextMuted
                            )
                            if (message.isOutgoing) {
                                StatusIndicator(
                                    status = message.status,
                                    isSent = true,
                                    pulseScale = pulseScale
                                )
                            }
                        }
                    }
                }
            }
            // Context menu replaced by bottom sheet (onLongPress)
        }
    }
}

// ======================== Status Indicator ========================

@Composable
private fun StatusIndicator(status: Int, isSent: Boolean, pulseScale: Float) {
    val T = GhostTheme
    when (status) {
        MessageEntity.STATUS_PENDING -> {
            CircularProgressIndicator(
                modifier = Modifier.size(10.dp),
                strokeWidth = 1.dp,
                color = Color.White.copy(alpha = 0.5f)
            )
        }
        MessageEntity.STATUS_SENT -> {
            Text("✓", fontSize = 12.sp, color = T.PurpleLight)
        }
        MessageEntity.STATUS_DELIVERED -> {
            Text("✓✓", fontSize = 12.sp, color = T.PurpleLight)
        }
        MessageEntity.STATUS_FAILED -> {
            Text("!", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = T.Failed)
        }
        MessageEntity.STATUS_SPRAYED -> {
            Text(
                "📡",
                modifier = Modifier.scale(pulseScale),
                fontSize = 11.sp
            )
        }
    }
}

// ======================== Bubble Shape Helper ========================

@Composable
private fun getBubbleShape(isSent: Boolean, position: GroupPosition): RoundedCornerShape {
    val T = GhostTheme
    val big = T.RadiusBubble
    val small = T.RadiusBubbleSharp

    return if (isSent) {
        when (position) {
            GroupPosition.SINGLE -> RoundedCornerShape(big, big, small, big)
            GroupPosition.FIRST -> RoundedCornerShape(big, big, small, big)
            GroupPosition.MIDDLE -> RoundedCornerShape(big, small, small, big)
            GroupPosition.LAST -> RoundedCornerShape(big, small, big, big)
        }
    } else {
        when (position) {
            GroupPosition.SINGLE -> RoundedCornerShape(big, big, big, small)
            GroupPosition.FIRST -> RoundedCornerShape(big, big, big, small)
            GroupPosition.MIDDLE -> RoundedCornerShape(small, big, big, small)
            GroupPosition.LAST -> RoundedCornerShape(small, big, big, big)
        }
    }
}

// ======================== Utilities ========================

fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000L -> "now"
        diff < 3_600_000L -> "${diff / 60_000L}m"
        diff < 86_400_000L -> "${diff / 3_600_000L}h"
        else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
    }
}
