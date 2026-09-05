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
import com.ghostprotocol.receipt.DeliveryReceiptHandler
import com.ghostprotocol.receipt.DeliveryReceiptProtocol
import com.ghostprotocol.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles incoming Cell Group messages (Opcode 0x30).
 * Validates group membership, verifies Ed25519 signature, decrypts envelope with X25519,
 * deduplicates, saves to Room, fires delivery receipt (Opcode 0x40), and shows batched notification.
 */
class GroupMessageReceiver(
    private val context: Context,
    private val groupDao: GroupDao,
    private val contactDao: ContactDao,
    private val groupMessageDao: GroupMessageDao,
    private val scope: CoroutineScope,
    private val sharedSignatureCache: ConcurrentHashMap<String, Long> = ConcurrentHashMap(),
    private val deliveryReceiptHandler: DeliveryReceiptHandler? = null
) {
    companion object {
        private const val TAG = "GHOST_GROUP"
    }

    // Dedup cache: Signature hex -> timestamp (shared instance with 1:1 message pipeline)
    // Dedup cache: Signature hex -> timestamp (shared instance with 1:1 message pipeline)
    private val recentSignatures: ConcurrentHashMap<String, Long> = sharedSignatureCache

    /**
     * Handles explicit group invites (Opcode 0x31).
     * Decrypts pairwise payload, verifies creator authenticity, creates GroupEntity in Room,
     * logs system welcome message, and posts high-priority group invite notification.
     */
    fun onGroupInviteReceived(data: ByteArray) {
        if (data.size < GroupProtocol.MIN_INVITE_SIZE) return
        if (data[0] != GroupProtocol.OPCODE_GROUP_INVITE) return

        scope.launch(Dispatchers.IO) {
            try {
                // 1. Peek creator contact ID
                val creatorContactId = GroupProtocol.peekInviteCreatorContactId(data) ?: return@launch
                val creator = contactDao.getByContactId(creatorContactId)
                if (creator == null || !creator.isVerified) {
                    Log.w(TAG, "Dropping group invite: creator $creatorContactId not in contacts or not verified")
                    return@launch
                }

                // 2. Cryptographically verify creator Ed25519 signature
                val pubKeyClean = creator.ed25519PubKey.trim()
                val creatorEd25519Pub = try {
                    Base64.decode(pubKeyClean, Base64.DEFAULT)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode creator Ed25519 public key Base64 for creator ${creator.id.take(8)}")
                    return@launch
                }
                if (creatorEd25519Pub.size != 32) {
                    Log.e(TAG, "Invalid creator Ed25519 public key length: ${creatorEd25519Pub.size} bytes (expected 32)")
                    return@launch
                }

                val invite = GroupProtocol.decodeInviteEnvelope(data, creatorEd25519Pub)
                if (invite == null) {
                    Log.w(TAG, "Dropping group invite: invalid Ed25519 signature from ${creator.name}")
                    return@launch
                }

                // 3. Deduplication check via deterministic signature
                val sigHex = GroupProtocol.bytesToHex(invite.signature)
                val now = System.currentTimeMillis()
                val lastSeen = recentSignatures.put(sigHex, now)
                if (lastSeen != null && (now - lastSeen < 60_000L)) {
                    Log.d(TAG, "Dropping duplicate group invite (signature match)")
                    return@launch
                }
                recentSignatures.entries.removeAll { now - it.value > 120_000L }

                // 4. Decrypt invite ciphertext using local X25519 private key
                val myX25519Secret = IdentityManager.getX25519Secret()
                val decryptedBytes = GhostCrypto.decrypt(myX25519Secret, invite.ciphertext)
                val rawText = String(decryptedBytes, Charsets.UTF_8)

                // Wire format: INVITE\0groupName\0membersJson
                val parts = rawText.split('\u0000')
                if (parts.size < 3 || parts[0] != "INVITE") {
                    Log.w(TAG, "Dropping malformed invite payload from ${creator.name}")
                    return@launch
                }
                val groupName = parts[1]
                val membersJson = parts[2]

                // 5. Verify local node is in member list
                val myContactId = IdentityManager.getContactId()
                val isMember = try {
                    val jsonArray = JSONArray(membersJson)
                    (0 until jsonArray.length()).any { jsonArray.getString(it) == myContactId }
                } catch (_: Exception) {
                    false
                }
                if (!isMember) {
                    Log.w(TAG, "Dropping group invite: local user $myContactId not in member list")
                    return@launch
                }

                // 6. Check if group already exists in Room
                val existingGroup = groupDao.getById(invite.groupIdHex)
                if (existingGroup == null) {
                    val newGroup = com.ghostprotocol.data.GroupEntity(
                        groupId = invite.groupIdHex,
                        name = groupName,
                        creatorContactId = creatorContactId,
                        memberContactIdsJson = membersJson,
                        createdAt = invite.timestamp,
                        isActive = true
                    )
                    groupDao.insert(newGroup)

                    // Insert system notice
                    val systemNotice = GroupMessageEntity(
                        groupId = invite.groupIdHex,
                        senderContactId = creatorContactId,
                        text = "* You were added to cell group \"$groupName\" by ${creator.name} *",
                        timestamp = invite.timestamp,
                        status = GroupMessageEntity.STATUS_DELIVERED
                    )
                    groupMessageDao.insert(systemNotice)

                    // Show invite notification
                    NotificationHelper.showGroupInviteNotification(
                        context = context,
                        groupName = groupName,
                        creatorName = creator.name,
                        groupId = invite.groupIdHex
                    )
                    Log.d(TAG, "Processed new group invite for '$groupName' (id=${invite.groupIdHex}) from ${creator.name}")
                } else {
                    if (!existingGroup.isActive || existingGroup.name != groupName || existingGroup.memberContactIdsJson != membersJson) {
                        val updated = existingGroup.copy(name = groupName, memberContactIdsJson = membersJson, isActive = true)
                        groupDao.insert(updated)
                        Log.d(TAG, "Updated existing group '$groupName' from invite")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process group invite: ${e.message}", e)
            }
        }
    }

    fun onGroupMessageReceived(data: ByteArray) {
        if (data.size < GroupProtocol.MIN_ENVELOPE_SIZE) return
        if (data[0] != GroupProtocol.OPCODE_GROUP_ENVELOPE) return

        scope.launch(Dispatchers.IO) {
            try {
                // 1. Peek sender contact ID
                val senderContactId = GroupProtocol.peekSenderContactId(data) ?: return@launch
                val sender = contactDao.getByContactId(senderContactId)
                if (sender == null || !sender.isVerified) {
                    Log.d(TAG, "Dropping envelope: sender $senderContactId unknown or not verified")
                    return@launch
                }

                // 2. Peek group ID
                val groupIdHex = GroupProtocol.peekGroupIdHex(data) ?: return@launch

                // 3. Verify Ed25519 signature using sender's known public key
                val pubKeyClean = sender.ed25519PubKey.trim()
                val senderEd25519Pub = try {
                    Base64.decode(pubKeyClean, Base64.DEFAULT)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode sender Ed25519 public key Base64 for sender ${sender.id.take(8)}")
                    return@launch
                }
                if (senderEd25519Pub.size != 32) {
                    Log.e(TAG, "Invalid sender Ed25519 public key length: ${senderEd25519Pub.size} bytes (expected 32)")
                    return@launch
                }

                val envelope = GroupProtocol.decodeEnvelope(data, senderEd25519Pub)
                if (envelope == null) {
                    val unsignedLen = if (data.size >= 64) data.size - 64 else 0
                    val sigLen = if (data.size >= 64) 64 else 0
                    val keyFp = try {
                        val md = java.security.MessageDigest.getInstance("SHA-256")
                        md.digest(senderEd25519Pub).take(4).joinToString("") { "%02x".format(it) }
                    } catch (_: Exception) { "unknown" }
                    Log.w(TAG, "Dropping envelope: invalid Ed25519 signature from sender=${sender.id.take(8)} (dataLen=${data.size}, unsignedLen=$unsignedLen, sigLen=$sigLen, keyFp=$keyFp)")
                    return@launch
                }

                // 4. Deduplication check via deterministic signature
                val sigHex = GroupProtocol.bytesToHex(envelope.signature)
                val now = System.currentTimeMillis()
                val lastSeen = recentSignatures.put(sigHex, now)
                if (lastSeen != null && (now - lastSeen < 60_000L)) {
                    Log.d(TAG, "Dropping duplicate group message (signature match)")
                    return@launch
                }
                recentSignatures.entries.removeAll { now - it.value > 120_000L }

                // 5. Decrypt ciphertext using local X25519 secret
                val myX25519Secret = IdentityManager.getX25519Secret()
                val decryptedBytes = GhostCrypto.decrypt(myX25519Secret, envelope.ciphertext)
                val rawText = String(decryptedBytes, Charsets.UTF_8)

                // 6. Parse wire plaintext payload
                val parsed = GroupProtocol.parseWirePayload(rawText)
                val myContactId = IdentityManager.getContactId()

                // 7. Resolve or self-heal group entity
                var group = groupDao.getById(groupIdHex)
                if (group == null) {
                    if (parsed.metaGroupName != null && parsed.metaCreatorId != null && parsed.metaMembersJson != null) {
                        val isMember = try {
                            val jsonArray = JSONArray(parsed.metaMembersJson)
                            (0 until jsonArray.length()).any { jsonArray.getString(it) == myContactId }
                        } catch (_: Exception) {
                            false
                        }

                        if (!isMember) {
                            Log.d(TAG, "Dropping envelope: local user is not a member of self-healed group '${parsed.metaGroupName}'")
                            return@launch
                        }

                        val newGroup = com.ghostprotocol.data.GroupEntity(
                            groupId = groupIdHex,
                            name = parsed.metaGroupName,
                            creatorContactId = parsed.metaCreatorId,
                            memberContactIdsJson = parsed.metaMembersJson,
                            createdAt = envelope.timestamp,
                            isActive = true
                        )
                        groupDao.insert(newGroup)

                        // Insert system notice
                        val systemNotice = GroupMessageEntity(
                            groupId = groupIdHex,
                            senderContactId = parsed.metaCreatorId,
                            text = "* You were added to cell group \"${parsed.metaGroupName}\" *",
                            timestamp = envelope.timestamp,
                            status = GroupMessageEntity.STATUS_DELIVERED
                        )
                        groupMessageDao.insert(systemNotice)

                        // Show invite notification
                        NotificationHelper.showGroupInviteNotification(
                            context = context,
                            groupName = parsed.metaGroupName,
                            creatorName = sender.name,
                            groupId = groupIdHex
                        )
                        Log.d(TAG, "Self-healed missing group '${parsed.metaGroupName}' (id=$groupIdHex) from incoming message")
                        group = newGroup
                    } else {
                        Log.d(TAG, "Dropping envelope: group $groupIdHex not found and no META metadata in payload")
                        return@launch
                    }
                }

                if (!group.isActive) {
                    Log.d(TAG, "Dropping envelope: group $groupIdHex is inactive")
                    return@launch
                }

                // Verify local node is an authorized member
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

                // 8. Compute contentHash and check for first delivery
                val contentHash = DeliveryReceiptProtocol.computeMessageHash(
                    senderContactId = senderContactId,
                    timestamp = envelope.timestamp,
                    plaintext = parsed.text
                )
                val existing = groupMessageDao.getByContentHash(contentHash)
                if (existing != null) {
                    Log.d(TAG, "Dropping duplicate group message (contentHash match) in '${group.name}'")
                    return@launch
                }

                val message = GroupMessageEntity(
                    groupId = groupIdHex,
                    senderContactId = senderContactId,
                    text = parsed.text,
                    timestamp = envelope.timestamp,
                    status = GroupMessageEntity.STATUS_DELIVERED,
                    replyToSender = parsed.replySender,
                    replyToText = parsed.replyText,
                    contentHash = contentHash
                )
                val newId = groupMessageDao.insert(message)
                if (newId > 0) {
                    Log.d(TAG, "Decrypted and saved group message in '${group.name}' from '${parsed.senderName ?: sender.name}'")

                    // Fire delivery receipt back to original sender (first delivery only)
                    deliveryReceiptHandler?.sendReceipt(envelope.senderContactId, contentHash)

                    // 9. Show batched notification with unread count
                    val unreadCount = groupMessageDao.getUnreadCountForGroup(groupIdHex)
                    NotificationHelper.showGroupMessageNotification(
                        context = context,
                        groupName = group.name,
                        senderName = parsed.senderName ?: sender.name,
                        preview = parsed.text.take(40),
                        groupId = groupIdHex,
                        unreadCount = unreadCount
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process group message: ${e.message}", e)
            }
        }
    }
}
