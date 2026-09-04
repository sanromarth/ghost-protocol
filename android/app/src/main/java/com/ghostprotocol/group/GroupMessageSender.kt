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
import com.ghostprotocol.router.GhostRouter
import kotlinx.coroutines.Dispatchers
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
            Log.e(TAG, "Cannot send message: group $groupId not found")
            return@withContext -1L
        }

        if (!group.isActive) {
            Log.e(TAG, "Cannot send message: group $groupId is inactive")
            return@withContext -1L
        }

        val myContactId = IdentityManager.getContactId()
        val myName = IdentityManager.getDisplayName()
        val myEd25519Seed = IdentityManager.getEd25519Seed()
        val now = System.currentTimeMillis()

        // 1. Insert message into Room DB with STATUS_PENDING
        val message = GroupMessageEntity(
            groupId = groupId,
            senderContactId = myContactId,
            text = text,
            timestamp = now,
            status = GroupMessageEntity.STATUS_PENDING,
            replyToSender = replyTo?.first,
            replyToText = replyTo?.second
        )
        val msgId = groupMessageDao.insert(message)

        // 2. Format wire plaintext with optional reply token
        val wireText = if (replyTo != null) {
            val qSender = replyTo.first.replace("\u0000", " ")
            val qText = replyTo.second.take(120).replace("\u0000", " ")
            "$myName\u0000REPLY\u0000$qSender\u0000$qText\u0000$text"
        } else {
            "$myName\u0000$text"
        }
        val plaintextBytes = wireText.toByteArray(Charsets.UTF_8)

        // 3. Parse member IDs
        val memberIds = try {
            val jsonArray = JSONArray(group.memberContactIdsJson)
            List(jsonArray.length()) { jsonArray.getString(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing member IDs for group $groupId: ${e.message}")
            emptyList()
        }

        var anyDispatched = false
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

                if (router != null) {
                    val (isDirect, blob) = router.sendMessage(dstPeerId, envelope)
                    if (isDirect && blob != null && member.bleAddress != null) {
                        BleManager.sendMessage(member.bleAddress, blob) { success ->
                            Log.d(TAG, "Direct send to '${member.name}' (${member.id}): ${if (success) "SUCCESS" else "FALLBACK TO SPRAY"}")
                        }
                    }
                    anyDispatched = true
                } else if (member.bleAddress != null) {
                    BleManager.sendMessage(member.bleAddress, envelope) { success ->
                        Log.d(TAG, "Direct fallback send to '${member.name}': $success")
                    }
                    anyDispatched = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create/send envelope for member ${member.name}: ${e.message}")
            }
        }

        val finalStatus = if (anyDispatched) GroupMessageEntity.STATUS_SPRAYED else GroupMessageEntity.STATUS_FAILED
        groupMessageDao.updateStatus(msgId, finalStatus)
        Log.d(TAG, "Group message $msgId sent to ${memberIds.size - 1} members with status $finalStatus")
        return@withContext msgId
    }

    /**
     * Re-flushes pending or sprayed group messages on peer re-encounter.
     * Preserves original timestamps so message ordering remains strictly chronological.
     */
    suspend fun reflushPendingGroupMessages(groupId: String) = withContext(Dispatchers.IO) {
        val group = groupDao.getById(groupId) ?: return@withContext
        if (!group.isActive) return@withContext

        val pending = groupMessageDao.getPendingOrSprayedForGroup(groupId)
        if (pending.isEmpty()) return@withContext

        val myContactId = IdentityManager.getContactId()
        val myName = IdentityManager.getDisplayName()
        val myEd25519Seed = IdentityManager.getEd25519Seed()
        val router = routerProvider() ?: return@withContext

        val memberIds = try {
            val jsonArray = JSONArray(group.memberContactIdsJson)
            List(jsonArray.length()) { jsonArray.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }

        for (msg in pending) {
            val wireText = if (msg.replyToSender != null && msg.replyToText != null) {
                "$myName\u0000REPLY\u0000${msg.replyToSender}\u0000${msg.replyToText}\u0000${msg.text}"
            } else {
                "$myName\u0000${msg.text}"
            }
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

                    val (isDirect, blob) = router.sendMessage(dstPeerId, envelope)
                    if (isDirect && blob != null && member.bleAddress != null) {
                        BleManager.sendMessage(member.bleAddress, blob) {}
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Reflush error for member $memberId in group $groupId: ${e.message}")
                }
            }
            groupMessageDao.updateStatus(msg.id, GroupMessageEntity.STATUS_SPRAYED)
        }
    }
}
