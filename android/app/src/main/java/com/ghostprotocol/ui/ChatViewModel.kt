package com.ghostprotocol.ui

import android.app.Application
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ghostprotocol.IdentityManager
import com.ghostprotocol.ble.BleManager
import com.ghostprotocol.crypto.GhostCrypto
import com.ghostprotocol.data.Contact
import com.ghostprotocol.data.GhostDatabase
import com.ghostprotocol.data.MessageEntity
import com.ghostprotocol.receipt.DeliveryReceiptProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * ViewModel managing 1:1 chat state, message flow, and send pipeline.
 *
 * Implements optimistic message presentation:
 * - Immediate synchronous state acknowledgement (<1ms) rendering STATUS_PENDING
 * - Asynchronous Room persistence and background crypto/transport
 * - Authoritative protocol status updates (STATUS_SENT, STATUS_SPRAYED, STATUS_FAILED)
 *   without synthetic or manufactured delivery ticks.
 */
class ChatViewModel(application: Application, private val contactId: String) : AndroidViewModel(application) {
    private val db = GhostDatabase.getInstance(application)
    private val contactDao = db.contactDao()
    private val messageDao = db.messageDao()

    private val _contact = MutableStateFlow<Contact?>(null)
    val contact: StateFlow<Contact?> = _contact.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    // In-memory optimistic pending messages for instantaneous UI feedback
    private val _optimisticPendingMessages = MutableStateFlow<Map<String, MessageEntity>>(emptyMap())

    // Merged stream of Room database messages and in-memory optimistic pending messages
    val messages: StateFlow<List<MessageEntity>> = combine(
        messageDao.getForContact(contactId),
        _optimisticPendingMessages
    ) { roomMessages, optimisticMap ->
        if (optimisticMap.isEmpty()) {
            roomMessages
        } else {
            val existingIds = roomMessages.mapTo(HashSet()) { it.id }
            val unmerged = optimisticMap.values.filter { it.id !in existingIds }
            if (unmerged.isEmpty()) roomMessages else (roomMessages + unmerged).sortedBy { it.timestamp }
        }
    }.flowOn(Dispatchers.Default)
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
        val trimmedText = text.trim()
        if (trimmedText.isEmpty()) return

        val myContactId = IdentityManager.getContactId()
        val timestamp = System.currentTimeMillis()
        val contentHash = DeliveryReceiptProtocol.computeMessageHash(
            senderContactId = myContactId,
            timestamp = timestamp,
            plaintext = trimmedText
        )

        // 1. Immediate UI Acknowledgement: create STATUS_PENDING bubble instantly
        val messageId = UUID.randomUUID().toString()
        val optimisticMessage = MessageEntity(
            id = messageId,
            contactId = contactId,
            content = trimmedText,
            isOutgoing = true,
            timestamp = timestamp,
            isVerified = true,
            status = MessageEntity.STATUS_PENDING,
            replyToId = replyTo?.id,
            replyToSender = if (replyTo != null) (if (replyTo.isOutgoing) "You" else (replySenderName ?: "Contact")) else null,
            replyToText = replyTo?.content?.take(120),
            contentHash = contentHash
        )

        // Immediately update state flow (< 1ms perceived latency)
        _optimisticPendingMessages.update { it + (messageId to optimisticMessage) }

        // 2. Offload Room persistence, cryptography, and transport to background coroutine
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Persist pending message into Room DB asynchronously
                messageDao.insert(optimisticMessage)
                // Remove from in-memory optimistic map once handed off to Room
                _optimisticPendingMessages.update { it - messageId }

                val freshContact = contactDao.getById(contactId) ?: run {
                    messageDao.updateStatus(messageId, MessageEntity.STATUS_FAILED)
                    return@launch
                }
                _contact.value = freshContact

                val myEd25519PubKey = IdentityManager.getEd25519PubKey()
                val myName = IdentityManager.getDisplayName()

                // Wire format with TS token:
                // Normal: senderName\u0000TS\u0000timestamp\u0000message
                // Reply:  senderName\u0000TS\u0000timestamp\u0000REPLY\u0000quotedSender\u0000quotedText\u0000message
                val plaintextBytes = if (replyTo != null) {
                    val quotedSender = if (replyTo.isOutgoing) "You" else (replySenderName ?: "Contact")
                    val quotedText = replyTo.content.take(120)
                    (myName + "\u0000TS\u0000" + timestamp + "\u0000REPLY\u0000" + quotedSender + "\u0000" + quotedText + "\u0000" + trimmedText).toByteArray(Charsets.UTF_8)
                } else {
                    (myName + "\u0000TS\u0000" + timestamp + "\u0000" + trimmedText).toByteArray(Charsets.UTF_8)
                }

                val payload = myEd25519PubKey + plaintextBytes
                val signature = GhostCrypto.sign(IdentityManager.getEd25519Seed(), payload)
                val fullPayload = payload + signature
                val contactX25519Pub = Base64.decode(freshContact.x25519PubKey, Base64.NO_WRAP)
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
                                    messageId,
                                    if (success) MessageEntity.STATUS_SENT else MessageEntity.STATUS_SPRAYED
                                )
                            }
                        }
                    } else {
                        // Queued in Go router for spray delivery
                        messageDao.updateStatus(messageId, MessageEntity.STATUS_SPRAYED)
                    }
                } else if (freshContact.bleAddress != null) {
                    // Fallback to direct BLE write
                    BleManager.sendMessage(freshContact.bleAddress, ciphertext) { success ->
                        viewModelScope.launch(Dispatchers.IO) {
                            messageDao.updateStatus(
                                messageId,
                                if (success) MessageEntity.STATUS_SENT else MessageEntity.STATUS_SPRAYED
                            )
                        }
                    }
                } else {
                    messageDao.updateStatus(messageId, MessageEntity.STATUS_SPRAYED)
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Send message failed: ${e.message}", e)
                messageDao.updateStatus(messageId, MessageEntity.STATUS_FAILED)
                _optimisticPendingMessages.update { it - messageId }
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
                val plaintextBytes = (myName + "\u0000TS\u0000" + message.timestamp + "\u0000" + message.content).toByteArray(Charsets.UTF_8)
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
                messageDao.updateStatus(message.id, MessageEntity.STATUS_FAILED)
            }
        }
    }

    fun deleteMessage(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            messageDao.deleteById(id)
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
