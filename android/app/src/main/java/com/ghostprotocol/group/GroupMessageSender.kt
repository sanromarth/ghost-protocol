package com.ghostprotocol.group

import android.util.Base64
import android.util.Log
import com.ghostprotocol.IdentityManager
import com.ghostprotocol.ble.BleManager
import com.ghostprotocol.crypto.GhostCrypto
import com.ghostprotocol.data.ContactDao
import com.ghostprotocol.data.GroupDao
import com.ghostprotocol.data.GroupMessageDao
import com.ghostprotocol.data.GroupMessageEntity
import com.ghostprotocol.receipt.DeliveryReceiptProtocol
import com.ghostprotocol.router.GhostRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.security.MessageDigest

/**
 * Orchestrates sending Cell Group messages via individual unicast envelopes.
 * Each verified member receives an envelope encrypted specifically for their X25519 public key.
 */
class GroupMessageSender(
    private val groupDao: GroupDao,
    private val contactDao: ContactDao,
    private val groupMessageDao: GroupMessageDao,
    private val routerProvider: () -> GhostRouter?
) {
    companion object {
        private const val TAG = "GHOST_GROUP"
    }

    private fun resolveTargetAddress(member: com.ghostprotocol.data.Contact): String? {
        return member.bleAddress ?: run {
            try {
                val memberPub = Base64.decode(member.ed25519PubKey, Base64.NO_WRAP)
                val fp = MessageDigest.getInstance("SHA-256").digest(memberPub).copyOfRange(0, 4)
                BleManager.peers.value.find { it.fingerprint?.contentEquals(fp) == true }?.address
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Sends a new message to all members in a group.
     * @param groupId 64-char hex group ID
     * @param text Message text
     * @param replyTo Optional Pair(quotedSender, quotedText)
     * @return The created GroupMessageEntity ID or -1L on failure
     */
    suspend fun sendGroupMessage(
        groupId: String,
        text: String,
        replyTo: Pair<String, String>? = null
    ): Long = withContext(Dispatchers.IO) {
        val group = groupDao.getById(groupId) ?: run {
            Log.e(TAG, "Group $groupId not found in DB")
            return@withContext -1L
        }
        if (!group.isActive) {
            Log.e(TAG, "Cannot send to inactive group $groupId")
            return@withContext -1L
        }

        val myContactId = IdentityManager.getContactId()
        val myName = IdentityManager.getDisplayName()
        val myEd25519Seed = IdentityManager.getEd25519Seed()
        val now = System.currentTimeMillis()

        // 1. Compute deterministic content hash across members
        val contentHash = DeliveryReceiptProtocol.computeMessageHash(
            senderContactId = myContactId,
            timestamp = now,
            plaintext = text
        )

        // 2. Insert locally as pending
        val message = GroupMessageEntity(
            groupId = groupId,
            senderContactId = myContactId,
            text = text,
            timestamp = now,
            status = GroupMessageEntity.STATUS_PENDING,
            replyToSender = replyTo?.first,
            replyToText = replyTo?.second,
            contentHash = contentHash
        )
        val msgId = groupMessageDao.insert(message)
        if (msgId <= 0) {
            Log.e(TAG, "Failed to insert group message into DB")
            return@withContext -1L
        }

        // 3. Format wire payload with metadata (for offline self-healing recipient)
        val meta = Triple(group.name, group.creatorContactId, group.memberContactIdsJson)
        val wireText = GroupProtocol.formatWirePayload(
            senderName = myName,
            replyTo = replyTo,
            meta = meta,
            text = text
        )
        val plaintextBytes = wireText.toByteArray(Charsets.UTF_8)

        val memberIds = try {
            val jsonArray = JSONArray(group.memberContactIdsJson)
            List(jsonArray.length()) { jsonArray.getString(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing member IDs for group $groupId: ${e.message}")
            emptyList()
        }

        var anyDispatched = false
        var directSendAttempted = false
        var meshSprayDispatched = false
        val router = routerProvider()

        // 4. Send individual unicast envelope to each verified member
        for (memberId in memberIds) {
            if (memberId == myContactId) continue // Don't send to self

            val member = contactDao.getByContactId(memberId)
            if (member == null) {
                Log.w(TAG, "Skipping member $memberId: not in contacts DB")
                continue
            }

            try {
                val memberX25519Pub = Base64.decode(member.x25519PubKey, Base64.NO_WRAP)
                val memberEd25519Pub = Base64.decode(member.ed25519PubKey, Base64.NO_WRAP)
                val dstPeerId = MessageDigest.getInstance("SHA-256").digest(memberEd25519Pub)

                // Pairwise encryption: fresh ephemeral X25519 keypair per member
                val ciphertext = GhostCrypto.encrypt(memberX25519Pub, plaintextBytes)
                val envelope = GroupProtocol.encodeEnvelope(
                    groupIdHex = groupId,
                    senderContactId = myContactId,
                    timestamp = message.timestamp,
                    ciphertext = ciphertext,
                    ed25519Seed = myEd25519Seed
                )

                val targetAddress = resolveTargetAddress(member)

                if (router != null) {
                    val (isDirect, blob) = router.sendMessage(dstPeerId, envelope)
                    if (isDirect && blob != null && targetAddress != null) {
                        directSendAttempted = true
                        BleManager.sendMessage(targetAddress, blob) { success ->
                            Log.d(TAG, "Direct send to '${member.name}' ($targetAddress): ${if (success) "SUCCESS" else "FALLBACK TO SPRAY"}")
                            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                                if (success) {
                                    groupMessageDao.updateStatus(msgId, GroupMessageEntity.STATUS_SENT)
                                } else {
                                    groupMessageDao.updateStatus(msgId, GroupMessageEntity.STATUS_SPRAYED)
                                }
                            }
                        }
                    } else if (blob != null) {
                        meshSprayDispatched = true
                    }
                    anyDispatched = true
                } else if (targetAddress != null) {
                    directSendAttempted = true
                    BleManager.sendMessage(targetAddress, envelope) { success ->
                        Log.d(TAG, "Direct fallback send to '${member.name}': $success")
                        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                            if (success) {
                                groupMessageDao.updateStatus(msgId, GroupMessageEntity.STATUS_SENT)
                            } else {
                                groupMessageDao.updateStatus(msgId, GroupMessageEntity.STATUS_FAILED)
                            }
                        }
                    }
                    anyDispatched = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create/send envelope for member ${member.name}: ${e.message}")
            }
        }

        if (!directSendAttempted) {
            val finalStatus = if (meshSprayDispatched || anyDispatched) GroupMessageEntity.STATUS_SPRAYED else GroupMessageEntity.STATUS_FAILED
            groupMessageDao.updateStatus(msgId, finalStatus)
            Log.d(TAG, "Group message $msgId sent via mesh with status $finalStatus")
        } else {
            Log.d(TAG, "Group message $msgId direct send in flight; keeping STATUS_PENDING")
        }
        return@withContext msgId
    }

    /**
     * Sends group invite envelopes (Opcode 0x31) to all verified members.
     * Encrypted pairwise to each member's X25519 public key.
     */
    suspend fun sendGroupInvite(group: com.ghostprotocol.data.GroupEntity): Boolean = withContext(Dispatchers.IO) {
        val myContactId = IdentityManager.getContactId()
        val myEd25519Seed = IdentityManager.getEd25519Seed()
        val router = routerProvider()

        val memberIds = try {
            val jsonArray = JSONArray(group.memberContactIdsJson)
            List(jsonArray.length()) { jsonArray.getString(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing member IDs for group ${group.groupId}: ${e.message}")
            emptyList()
        }

        val invitePlaintext = "INVITE\u0000${group.name}\u0000${group.memberContactIdsJson}".toByteArray(Charsets.UTF_8)
        var anyDispatched = false

        for (memberId in memberIds) {
            if (memberId == myContactId) continue

            val member = contactDao.getByContactId(memberId)
            if (member == null) {
                Log.w(TAG, "Skipping invite for member $memberId: not in contacts DB")
                continue
            }

            try {
                val memberX25519Pub = Base64.decode(member.x25519PubKey, Base64.NO_WRAP)
                val memberEd25519Pub = Base64.decode(member.ed25519PubKey, Base64.NO_WRAP)
                val dstPeerId = MessageDigest.getInstance("SHA-256").digest(memberEd25519Pub)

                val ciphertext = GhostCrypto.encrypt(memberX25519Pub, invitePlaintext)
                val envelope = GroupProtocol.encodeInviteEnvelope(
                    groupIdHex = group.groupId,
                    creatorContactId = myContactId,
                    timestamp = group.createdAt,
                    ciphertext = ciphertext,
                    ed25519Seed = myEd25519Seed
                )

                val targetAddress = resolveTargetAddress(member)

                if (router != null) {
                    val (isDirect, blob) = router.sendMessage(dstPeerId, envelope)
                    if (isDirect && blob != null && targetAddress != null) {
                        BleManager.sendMessage(targetAddress, blob) { success ->
                            Log.d(TAG, "Direct invite send to '${member.name}' ($targetAddress): $success")
                        }
                    }
                    anyDispatched = true
                } else if (targetAddress != null) {
                    BleManager.sendMessage(targetAddress, envelope) { success ->
                        Log.d(TAG, "Direct fallback invite send to '${member.name}' ($targetAddress): $success")
                    }
                    anyDispatched = true
                }
                Log.d(TAG, "Dispatched group invite for '${group.name}' to member '${member.name}'")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send group invite to member ${member.name}: ${e.message}")
            }
        }
        return@withContext anyDispatched
    }

    /**
     * Re-flushes pending or sprayed group messages on peer re-encounter.
     * Preserves original timestamps so message ordering remains strictly chronological.
     */
    suspend fun reflushPendingGroupMessages(groupId: String) = withContext(Dispatchers.IO) {
        val group = groupDao.getById(groupId) ?: return@withContext
        if (!group.isActive) return@withContext

        val myContactId = IdentityManager.getContactId()
        val pending = groupMessageDao.getPendingOrSprayedForGroup(groupId, myContactId)
        if (pending.isEmpty()) return@withContext

        val myName = IdentityManager.getDisplayName()
        val myEd25519Seed = IdentityManager.getEd25519Seed()
        val router = routerProvider() ?: return@withContext

        val memberIds = try {
            val jsonArray = JSONArray(group.memberContactIdsJson)
            List(jsonArray.length()) { jsonArray.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }

        val meta = Triple(group.name, group.creatorContactId, group.memberContactIdsJson)

        for (msg in pending) {
            val replyTo = if (msg.replyToSender != null && msg.replyToText != null) {
                Pair(msg.replyToSender, msg.replyToText)
            } else {
                null
            }
            val wireText = GroupProtocol.formatWirePayload(
                senderName = myName,
                replyTo = replyTo,
                meta = meta,
                text = msg.text
            )
            val plaintextBytes = wireText.toByteArray(Charsets.UTF_8)

            for (memberId in memberIds) {
                if (memberId == myContactId) continue
                val member = contactDao.getByContactId(memberId) ?: continue

                try {
                    val memberX25519Pub = Base64.decode(member.x25519PubKey, Base64.NO_WRAP)
                    val memberEd25519Pub = Base64.decode(member.ed25519PubKey, Base64.NO_WRAP)
                    val dstPeerId = MessageDigest.getInstance("SHA-256").digest(memberEd25519Pub)

                    val ciphertext = GhostCrypto.encrypt(memberX25519Pub, plaintextBytes)
                    val envelope = GroupProtocol.encodeEnvelope(
                        groupIdHex = groupId,
                        senderContactId = myContactId,
                        timestamp = msg.timestamp, // PRESERVE ORIGINAL TIMESTAMP
                        ciphertext = ciphertext,
                        ed25519Seed = myEd25519Seed
                    )

                    val targetAddress = resolveTargetAddress(member)
                    val (isDirect, blob) = router.sendMessage(dstPeerId, envelope)
                    if (isDirect && blob != null && targetAddress != null) {
                        BleManager.sendMessage(targetAddress, blob) {}
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Reflush error for member $memberId in group $groupId: ${e.message}")
                }
            }
            groupMessageDao.updateStatus(msg.id, GroupMessageEntity.STATUS_SPRAYED)
        }
    }
}
