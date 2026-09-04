package com.ghostprotocol.receipt

import android.util.Base64
import android.util.Log
import com.ghostprotocol.IdentityManager
import com.ghostprotocol.ble.BleManager
import com.ghostprotocol.data.ContactDao
import com.ghostprotocol.data.GroupMessageDao
import com.ghostprotocol.data.MessageDao
import com.ghostprotocol.data.MessageEntity
import com.ghostprotocol.router.GhostRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Orchestrator for sending and processing v0.3.7 Delivery Receipts (Opcode 0x40).
 * Handles signature verification, first-delivery state transitions, and group delivery tracking.
 */
class DeliveryReceiptHandler(
    private val contactDao: ContactDao,
    private val messageDao: MessageDao,
    private val groupMessageDao: GroupMessageDao,
    private val routerProvider: () -> GhostRouter?,
    private val scope: CoroutineScope,
    private val sharedSignatureCache: ConcurrentHashMap<String, Long> = ConcurrentHashMap()
) {
    companion object {
        private const val TAG = "GHOST_RECEIPT"
    }

    /**
     * Constructs and sends an Ed25519-signed delivery receipt back to the original message sender.
     */
    fun sendReceipt(originalSenderContactId: String, messageHash: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val senderContact = contactDao.getByContactId(originalSenderContactId)
                if (senderContact == null) {
                    Log.w(TAG, "Cannot send receipt: sender contact '$originalSenderContactId' not in DB")
                    return@launch
                }

                val localContactId = IdentityManager.getContactId()
                val myEd25519Seed = IdentityManager.getEd25519Seed()

                val receiptBytes = DeliveryReceiptProtocol.encodeReceipt(
                    messageHash = messageHash,
                    recipientContactId = localContactId,
                    ed25519Seed = myEd25519Seed
                )

                val senderEd25519Pub = Base64.decode(senderContact.ed25519PubKey, Base64.NO_WRAP)
                val dstPeerId = MessageDigest.getInstance("SHA-256").digest(senderEd25519Pub)

                val router = routerProvider()
                if (router != null) {
                    val (isDirect, blob) = router.sendMessage(dstPeerId, receiptBytes)
                    if (isDirect && blob != null && senderContact.bleAddress != null) {
                        BleManager.sendMessage(senderContact.bleAddress, blob) { success ->
                            Log.d(TAG, "Direct send receipt to '${senderContact.name}': ${if (success) "SUCCESS" else "FALLBACK TO SPRAY"}")
                        }
                    } else {
                        Log.d(TAG, "Queued receipt via router for '${senderContact.name}' (isDirect=$isDirect)")
                    }
                } else if (senderContact.bleAddress != null) {
                    BleManager.sendMessage(senderContact.bleAddress, receiptBytes) { success ->
                        Log.d(TAG, "Direct fallback send receipt to '${senderContact.name}': $success")
                    }
                }
                Log.d(TAG, "Sent receipt for hash $messageHash to $originalSenderContactId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send receipt: ${e.message}")
            }
        }
    }

    /**
     * Processes an incoming delivery receipt packet (Opcode 0x40).
     * Verifies recipient signature and marks 1:1 or Group messages as delivered.
     */
    fun onReceiptReceived(data: ByteArray) {
        scope.launch(Dispatchers.IO) {
            try {
                val receipt = DeliveryReceiptProtocol.decodeReceipt(data)
                if (receipt == null) {
                    Log.w(TAG, "Failed to decode receipt packet (${data.size} bytes), dropping")
                    return@launch
                }

                // Dedup check using receipt signature
                val sigHex = receipt.signature.joinToString("") { "%02x".format(it) }
                val now = System.currentTimeMillis()
                val lastSeen = sharedSignatureCache.put(sigHex, now)
                if (lastSeen != null && (now - lastSeen < 60_000L)) {
                    Log.d(TAG, "Dropping duplicate receipt (signature match) for hash ${receipt.messageHash}")
                    return@launch
                }

                val recipientContact = contactDao.getByContactId(receipt.recipientContactId)
                if (recipientContact == null) {
                    Log.w(TAG, "Unknown recipient contact '${receipt.recipientContactId}', dropping receipt")
                    return@launch
                }

                val recipientEd25519Pub = Base64.decode(recipientContact.ed25519PubKey, Base64.NO_WRAP)
                val isValid = DeliveryReceiptProtocol.verifyReceipt(receipt, recipientEd25519Pub)
                if (!isValid) {
                    Log.w(TAG, "Invalid Ed25519 signature from '${receipt.recipientContactId}', dropping receipt")
                    return@launch
                }

                // 1. Check 1:1 messages table
                val msg1to1 = messageDao.getByContentHash(receipt.messageHash)
                if (msg1to1 != null) {
                    if (msg1to1.status != MessageEntity.STATUS_DELIVERED) {
                        messageDao.updateStatusByHash(receipt.messageHash, MessageEntity.STATUS_DELIVERED)
                        Log.d(TAG, "1:1 message delivered, hash ${receipt.messageHash}")
                    }
                    return@launch
                }

                // 2. Check Group messages table
                val groupMsg = groupMessageDao.getByContentHash(receipt.messageHash)
                if (groupMsg != null) {
                    val list = try {
                        val arr = JSONArray(groupMsg.deliveredMemberIdsJson)
                        MutableList(arr.length()) { arr.getString(it) }
                    } catch (_: Exception) {
                        mutableListOf<String>()
                    }

                    if (!list.contains(receipt.recipientContactId)) {
                        list.add(receipt.recipientContactId)
                        val newJson = JSONArray(list).toString()
                        groupMessageDao.updateDeliveredMembers(groupMsg.id, newJson)
                        Log.d(TAG, "Group message ${groupMsg.id} delivered to ${receipt.recipientContactId}, count ${list.size}")
                    }
                    return@launch
                }

                Log.d(TAG, "Orphan receipt for hash ${receipt.messageHash}")
            } catch (e: Exception) {
                Log.e(TAG, "Error handling receipt: ${e.message}")
            }
        }
    }
}
