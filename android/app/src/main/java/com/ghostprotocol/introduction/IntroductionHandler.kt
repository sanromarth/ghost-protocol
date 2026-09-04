package com.ghostprotocol.introduction

import android.content.Context
import android.util.Base64
import android.util.Log
import com.ghostprotocol.IdentityManager
import com.ghostprotocol.ble.BleManager
import com.ghostprotocol.crypto.GhostCrypto
import com.ghostprotocol.data.Contact
import com.ghostprotocol.data.ContactDao
import com.ghostprotocol.data.MessageDao
import com.ghostprotocol.data.MessageEntity
import com.ghostprotocol.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class PendingIntroduction(
    val envelope: IntroductionEnvelope,
    val voucherContact: Contact,
    val receivedAt: Long = System.currentTimeMillis()
)

class IntroductionHandler(
    private val context: Context,
    private val contactDao: ContactDao,
    private val messageDao: MessageDao,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "IntroductionHandler"
        const val EXPIRY_MS = 600_000L // 10 minutes
    }

    private val pendingIntroductions = ConcurrentHashMap<String, PendingIntroduction>()

    fun getPendingIntroduction(introducedContactId: String): PendingIntroduction? {
        val pending = pendingIntroductions[introducedContactId] ?: return null
        if (System.currentTimeMillis() - pending.receivedAt > EXPIRY_MS) {
            pendingIntroductions.remove(introducedContactId)
            return null
        }
        return pending
    }

    suspend fun sendIntroduction(introducedContact: Contact, recipientContact: Contact): Boolean = withContext(Dispatchers.IO) {
        if (!introducedContact.isVerified) {
            throw IllegalStateException("Can only introduce verified contacts")
        }
        if (!recipientContact.isVerified) {
            throw IllegalStateException("Can only introduce to verified contacts")
        }

        try {
            val myContactId = IdentityManager.getContactId()
            val myEd25519Seed = IdentityManager.getEd25519Seed()
            val myEd25519PubKey = IdentityManager.getEd25519PubKey()

            // 1. Build and sign introduction envelope (Opcode 0x50)
            val envelopeBytes = IntroductionProtocol.encodeIntroduction(
                introducedContact = introducedContact,
                voucherSeed = myEd25519Seed,
                voucherContactId = myContactId
            )

            // 2. Package into 1:1 message payload: myEd25519PubKey + envelopeBytes + outerSig
            val payload = myEd25519PubKey + envelopeBytes
            val signature = GhostCrypto.sign(myEd25519Seed, payload)
            val fullPayload = payload + signature

            // 3. Encrypt to recipient's X25519 public key
            val recipientX25519Pub = Base64.decode(recipientContact.x25519PubKey, Base64.NO_WRAP)
            val ciphertext = GhostCrypto.encrypt(recipientX25519Pub, fullPayload)

            // 4. Compute destination peer ID for Go router
            val recipientEd25519Pub = Base64.decode(recipientContact.ed25519PubKey, Base64.NO_WRAP)
            val dstPeerId = MessageDigest.getInstance("SHA-256").digest(recipientEd25519Pub)

            // 5. Dispatch via Go router or direct GATT
            val router = BleManager.getRouter()
            if (router != null) {
                val (isDirect, blob) = router.sendMessage(dstPeerId, ciphertext)
                if (isDirect && blob != null && recipientContact.bleAddress != null) {
                    BleManager.sendMessage(recipientContact.bleAddress, blob) {}
                }
            }

            // 6. Insert system message into Alice+Carol chat
            val systemMsg = MessageEntity(
                id = UUID.randomUUID().toString(),
                contactId = recipientContact.id,
                content = "* You introduced ${introducedContact.name} to ${recipientContact.name} *",
                isOutgoing = true,
                timestamp = System.currentTimeMillis(),
                isVerified = true,
                status = MessageEntity.STATUS_DELIVERED
            )
            messageDao.insert(systemMsg)

            Log.d(TAG, "GHOST_INTRO: Sent introduction of ${introducedContact.name} to ${recipientContact.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "GHOST_INTRO: Failed to send introduction: ${e.message}", e)
            false
        }
    }

    fun onIntroductionReceived(senderContactId: String, plaintextBytes: ByteArray) {
        scope.launch(Dispatchers.IO) {
            try {
                // 1. Decode envelope
                val envelope = IntroductionProtocol.decodeIntroduction(plaintextBytes)
                if (envelope == null) {
                    Log.w(TAG, "GHOST_INTRO: Failed to decode envelope from $senderContactId")
                    return@launch
                }

                // 2. Validate voucher exists and is mutually verified
                val voucher = contactDao.getById(envelope.voucherContactId)
                if (voucher == null || !voucher.isVerified) {
                    Log.w(TAG, "GHOST_INTRO: Unknown or unverified voucher ${envelope.voucherContactId}")
                    return@launch
                }

                // 3. Verify signature using voucher's public key
                val voucherEd25519Pub = Base64.decode(voucher.ed25519PubKey, Base64.NO_WRAP)
                val isValid = IntroductionProtocol.verifyIntroduction(envelope, voucherEd25519Pub)
                if (!isValid) {
                    Log.w(TAG, "GHOST_INTRO: Invalid signature on introduction from ${voucher.name}")
                    return@launch
                }

                // 4. Compute introduced contact ID
                val introducedContactId = IntroductionProtocol.computeContactId(envelope.introducedEd25519Pub)

                // 5. Check if contact is already mutually verified
                val existing = contactDao.getById(introducedContactId)
                if (existing != null && existing.isVerified) {
                    Log.d(TAG, "GHOST_INTRO: Contact ${envelope.introducedName} already verified, ignoring introduction")
                    return@launch
                }

                // 6. Cache introduction for review
                pendingIntroductions[introducedContactId] = PendingIntroduction(
                    envelope = envelope,
                    voucherContact = voucher,
                    receivedAt = System.currentTimeMillis()
                )

                // 7. Post notification
                NotificationHelper.showIntroductionNotification(
                    context = context,
                    voucherName = voucher.name,
                    introducedName = envelope.introducedName,
                    introducedContactId = introducedContactId
                )

                Log.d(TAG, "GHOST_INTRO: Received valid introduction for ${envelope.introducedName} from ${voucher.name}")
            } catch (e: Exception) {
                Log.e(TAG, "GHOST_INTRO: Error processing introduction: ${e.message}", e)
            }
        }
    }

    suspend fun acceptIntroduction(introducedContactId: String): Boolean = withContext(Dispatchers.IO) {
        val pending = getPendingIntroduction(introducedContactId) ?: return@withContext false

        try {
            val ed25519PubB64 = Base64.encodeToString(pending.envelope.introducedEd25519Pub, Base64.NO_WRAP)
            val x25519PubB64 = Base64.encodeToString(pending.envelope.introducedX25519Pub, Base64.NO_WRAP)

            // Insert as introduced contact with slate border (isIntroduced = true, isVerified = false)
            val newContact = Contact(
                id = introducedContactId,
                name = pending.envelope.introducedName,
                ed25519PubKey = ed25519PubB64,
                x25519PubKey = x25519PubB64,
                bleAddress = null,
                isVerified = false,
                isIntroduced = true
            )
            contactDao.insertOrUpdate(newContact)

            // Insert system message into voucher's chat
            val systemMsg = MessageEntity(
                id = UUID.randomUUID().toString(),
                contactId = pending.voucherContact.id,
                content = "* ${pending.voucherContact.name} introduced ${pending.envelope.introducedName} to you *",
                isOutgoing = false,
                timestamp = System.currentTimeMillis(),
                isVerified = true,
                status = MessageEntity.STATUS_DELIVERED
            )
            messageDao.insert(systemMsg)

            pendingIntroductions.remove(introducedContactId)
            Log.d(TAG, "GHOST_INTRO: Accepted introduction of ${pending.envelope.introducedName}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "GHOST_INTRO: Failed to accept introduction: ${e.message}", e)
            false
        }
    }

    fun declineIntroduction(introducedContactId: String) {
        pendingIntroductions.remove(introducedContactId)
        Log.d(TAG, "GHOST_INTRO: Declined introduction for $introducedContactId")
    }
}
