package com.ghostprotocol.group

import android.content.Context
import android.util.Base64
import android.util.Log
import com.ghostprotocol.IdentityManager
import com.ghostprotocol.crypto.GhostCrypto
import com.ghostprotocol.data.ContactDao
import com.ghostprotocol.data.GroupDao
import com.ghostprotocol.data.GroupMessageDao
import com.ghostprotocol.data.GroupMessageEntity
import com.ghostprotocol.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles incoming Cell Group messages (Opcode 0x30).
 * Validates group membership, verifies Ed25519 signature, decrypts envelope with X25519,
 * deduplicates, saves to Room, and shows batched notification.
 */
class GroupMessageReceiver(
    private val context: Context,
    private val groupDao: GroupDao,
    private val contactDao: ContactDao,
    private val groupMessageDao: GroupMessageDao,
    private val scope: CoroutineScope,
    private val sharedSignatureCache: ConcurrentHashMap<String, Long> = ConcurrentHashMap()
) {
    companion object {
        private const val TAG = "GHOST_GROUP"
    }

    // Dedup cache: Signature hex -> timestamp (shared instance with 1:1 message pipeline)
    private val recentSignatures: ConcurrentHashMap<String, Long> = sharedSignatureCache

    fun onGroupMessageReceived(data: ByteArray) {
        if (data.size < GroupProtocol.MIN_ENVELOPE_SIZE) return
        if (data[0] != GroupProtocol.OPCODE_GROUP_ENVELOPE) return

        scope.launch(Dispatchers.IO) {
            try {
                // 1. Peek sender contact ID
                val senderContactId = GroupProtocol.peekSenderContactId(data) ?: return@launch
                val sender = contactDao.getByContactId(senderContactId)
                if (sender == null) {
                    Log.d(TAG, "Dropping envelope: unknown sender $senderContactId")
                    return@launch
                }

                // 2. Peek group ID
                val groupIdHex = GroupProtocol.peekGroupIdHex(data) ?: return@launch
                val group = groupDao.getById(groupIdHex)
                if (group == null || !group.isActive) {
                    Log.d(TAG, "Dropping envelope: group $groupIdHex not found or inactive")
                    return@launch
                }

                // 3. Verify local node is an authorized member
                val myContactId = IdentityManager.getContactId()
                val isMember = try {
                    val jsonArray = JSONArray(group.memberContactIdsJson)
                    (0 until jsonArray.length()).any { jsonArray.getString(it) == myContactId }
                } catch (_: Exception) {
                    false
                }
                if (!isMember) {
                    Log.d(TAG, "Dropping envelope: local user is not a member of group '${group.name}'")
                    return@launch
                }

                // 4. Verify Ed25519 signature using sender's known public key
                val senderEd25519Pub = Base64.decode(sender.ed25519PubKey, Base64.NO_WRAP)
                val envelope = GroupProtocol.decodeEnvelope(data, senderEd25519Pub)
                if (envelope == null) {
                    Log.w(TAG, "Dropping envelope: invalid Ed25519 signature from ${sender.name}")
                    return@launch
                }

                // 5. Deduplication check via deterministic signature
                val sigHex = GroupProtocol.bytesToHex(envelope.signature)
                val now = System.currentTimeMillis()
                val lastSeen = recentSignatures.put(sigHex, now)
                if (lastSeen != null && (now - lastSeen < 60_000L)) {
                    Log.d(TAG, "Dropping duplicate group message (signature match) in '${group.name}'")
                    return@launch
                }
                recentSignatures.entries.removeAll { now - it.value > 120_000L }

                // 6. Decrypt ciphertext using local X25519 secret
                val myX25519Secret = IdentityManager.getX25519Secret()
                val decryptedBytes = GhostCrypto.decrypt(myX25519Secret, envelope.ciphertext)
                val rawText = String(decryptedBytes, Charsets.UTF_8)

                // 7. Parse wire plaintext format: name\0[REPLY\0quotedSender\0quotedText\0]message
                val parts = rawText.split('\u0000')
                val parsedSenderName: String?
                val replySender: String?
                val replyText: String?
                val text: String

                if (parts.size >= 5 && parts[1] == "REPLY") {
                    parsedSenderName = parts[0].ifEmpty { null }
                    replySender = parts[2].ifEmpty { null }
                    replyText = parts[3].ifEmpty { null }
                    text = parts.drop(4).joinToString("\u0000")
                } else if (parts.size >= 2) {
                    parsedSenderName = parts[0].ifEmpty { null }
                    replySender = null
                    replyText = null
                    text = parts.drop(1).joinToString("\u0000")
                } else {
                    parsedSenderName = null
                    replySender = null
                    replyText = null
                    text = rawText
                }

                // 8. Insert into group_messages table with STATUS_DELIVERED
                val message = GroupMessageEntity(
                    groupId = groupIdHex,
                    senderContactId = senderContactId,
                    text = text,
                    timestamp = envelope.timestamp,
                    status = GroupMessageEntity.STATUS_DELIVERED,
                    replyToSender = replySender,
                    replyToText = replyText
                )
                groupMessageDao.insert(message)
                Log.d(TAG, "Decrypted and saved group message in '${group.name}' from '${parsedSenderName ?: sender.name}'")

                // 9. Show batched notification with unread count
                val unreadCount = groupMessageDao.getUnreadCountForGroup(groupIdHex)
                NotificationHelper.showGroupMessageNotification(
                    context = context,
                    groupName = group.name,
                    senderName = parsedSenderName ?: sender.name,
                    preview = text.take(40),
                    groupId = groupIdHex,
                    unreadCount = unreadCount
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process group message: ${e.message}", e)
            }
        }
    }
}
