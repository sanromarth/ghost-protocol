package com.ghostprotocol.data

import com.ghostprotocol.ble.BleManager
import com.ghostprotocol.ble.DiscoveredPeer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray

/**
 * Repository that aggregates 1:1 direct mesh contacts and Cell Groups into a
 * unified, chronologically sorted stream of ConversationItem objects.
 *
 * All joins, peer matching, and sorting occur on Dispatchers.Default,
 * ensuring zero overhead or recomposition stalls on the UI thread.
 */
class ConversationRepository(
    private val contactDao: ContactDao,
    private val groupDao: GroupDao,
    private val messageDao: MessageDao,
    private val groupMessageDao: GroupMessageDao,
    private val peerFlow: Flow<List<DiscoveredPeer>>,
    private val myContactIdProvider: () -> String
) {
    fun getConversations(): Flow<List<ConversationItem>> = combine(
        contactDao.getAll(),
        groupDao.getAllActive(),
        messageDao.getAllMessages(),
        groupMessageDao.getAllGroupMessages(),
        peerFlow
    ) { contacts, groups, messages, groupMessages, peers ->
        val now = System.currentTimeMillis()
        val myContactId = myContactIdProvider()

        // 1. Group direct messages by contactId (first entry is latest since ordered by timestamp DESC)
        val latestMessageByContact = HashMap<String, MessageEntity>(contacts.size)
        for (m in messages) {
            if (!latestMessageByContact.containsKey(m.contactId)) {
                latestMessageByContact[m.contactId] = m
            }
        }

        // 2. Group cell group messages by groupId
        val latestGroupMsgByGroup = HashMap<String, GroupMessageEntity>(groups.size)
        for (gm in groupMessages) {
            if (!latestGroupMsgByGroup.containsKey(gm.groupId)) {
                latestGroupMsgByGroup[gm.groupId] = gm
            }
        }

        // 3. Pre-index peers by MAC address and canonical 8-char hex fingerprint
        // peer.fingerprint is 4 bytes -> 8 hex characters matching contact.id.take(8)
        val peersByAddress = HashMap<String, DiscoveredPeer>()
        val peersByFingerprint = HashMap<String, DiscoveredPeer>()
        for (peer in peers) {
            val isRecent = (now - peer.lastSeen < BleManager.PEER_OFFLINE_TIMEOUT_MS)
            if (isRecent) {
                peersByAddress[peer.address] = peer
                if (peer.fingerprint != null && peer.fingerprint.size >= 4) {
                    val fpHex = peer.fingerprint.take(4).joinToString("") { "%02x".format(it) }
                    peersByFingerprint[fpHex] = peer
                }
            }
        }

        val directItems = contacts.map { contact ->
            val lastMsg = latestMessageByContact[contact.id]
            val lastTime = lastMsg?.timestamp ?: contact.createdAt

            // Fast O(1) canonical peer match:
            val matchedPeer = (contact.bleAddress?.let { peersByAddress[it] })
                ?: peersByFingerprint[contact.id.take(8)]

            val isDirect = matchedPeer != null
            val rssi = matchedPeer?.rssi

            ConversationItem.Direct(
                id = contact.id,
                name = contact.name,
                lastMessageText = lastMsg?.content,
                lastMessageTime = lastTime,
                unreadCount = 0,
                ed25519PubKeyBase64 = contact.ed25519PubKey,
                isVerified = contact.isVerified,
                isIntroduced = contact.isIntroduced,
                isDirectRadio = isDirect,
                directRssi = rssi,
                lastMessageStatus = if (lastMsg?.isOutgoing == true) lastMsg.status else null,
                lastMessageIsOutgoing = lastMsg?.isOutgoing == true
            )
        }

        val groupItems = groups.map { group ->
            val lastMsg = latestGroupMsgByGroup[group.groupId]
            val lastTime = lastMsg?.timestamp ?: group.createdAt
            val memberCount = try {
                JSONArray(group.memberContactIdsJson).length()
            } catch (_: Exception) {
                0
            }
            val isOutgoing = lastMsg?.senderContactId == myContactId
            val senderName = when {
                lastMsg == null -> null
                isOutgoing -> "You"
                else -> contacts.find { it.id == lastMsg.senderContactId }?.name
                    ?: "Peer #${lastMsg.senderContactId.take(4)}"
            }

            ConversationItem.Group(
                id = group.groupId,
                name = group.name,
                lastMessageText = lastMsg?.text,
                lastMessageTime = lastTime,
                unreadCount = 0,
                memberCount = memberCount,
                lastMessageSenderName = senderName,
                lastMessageStatus = if (isOutgoing) lastMsg?.status else null,
                lastMessageIsOutgoing = isOutgoing
            )
        }

        // Merge and sort chronologically (most recent conversation first)
        (directItems + groupItems).sortedByDescending { it.lastMessageTime }
    }.flowOn(Dispatchers.Default)

    companion object {
        @Volatile
        private var instance: ConversationRepository? = null

        fun getInstance(context: android.content.Context): ConversationRepository {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val db = GhostDatabase.getInstance(context)
                    ConversationRepository(
                        contactDao = db.contactDao(),
                        groupDao = db.groupDao(),
                        messageDao = db.messageDao(),
                        groupMessageDao = db.groupMessageDao(),
                        peerFlow = BleManager.peers,
                        myContactIdProvider = { com.ghostprotocol.IdentityManager.getContactId() }
                    ).also { instance = it }
                }
            }
        }
    }
}
