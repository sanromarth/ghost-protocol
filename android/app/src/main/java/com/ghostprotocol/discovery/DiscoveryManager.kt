package com.ghostprotocol.discovery

import android.content.Context
import android.util.Base64
import android.util.Log
import com.ghostprotocol.IdentityManager
import com.ghostprotocol.ble.BleManager
import com.ghostprotocol.data.Contact
import com.ghostprotocol.data.ContactDao
import com.ghostprotocol.data.MessageDao
import com.ghostprotocol.data.MessageEntity
import com.ghostprotocol.security.SecurityPosture
import com.ghostprotocol.security.allowsUnknownPeerNotifications
import com.ghostprotocol.util.HapticHelper
import com.ghostprotocol.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

enum class DiscoveryState {
    IDLE,
    REQUEST_SENT,
    REQUEST_RECEIVED,
    RESPONSE_SENT,
    COMPLETED
}

class DiscoveryManager(
    private val context: Context,
    private val contactDao: ContactDao,
    private val messageDao: MessageDao,
    private val coroutineScope: CoroutineScope,
    private val postureProvider: () -> SecurityPosture
) {
    private val inFlightStates = ConcurrentHashMap<String, DiscoveryState>()
    private val inFlightRequests = ConcurrentHashMap<String, DiscoveryRequest>()
    private val inFlightTimeouts = ConcurrentHashMap<String, Job>()
    private val lastNotificationTime = ConcurrentHashMap<String, Long>()

    /**
     * Invoked when BleManager discovers a peer advertisement with a BLE fingerprint.
     */
    fun onUnknownPeerDetected(
        mac: String,
        fingerprint: ByteArray,
        rssi: Int,
        posture: SecurityPosture
    ) {
        // Invariant 1: Silent in STEALTH mode
        if (!posture.allowsUnknownPeerNotifications()) return

        coroutineScope.launch {
            try {
                // Check if this fingerprint matches any existing contact in Room DB
                val contacts = contactDao.getAllOnce()
                val isKnown = contacts.any { contact ->
                    try {
                        val pubKeyBytes = Base64.decode(contact.ed25519PubKey, Base64.NO_WRAP)
                        val hash = MessageDigest.getInstance("SHA-256").digest(pubKeyBytes)
                        val fp = hash.copyOfRange(0, 4)
                        fp.contentEquals(fingerprint)
                    } catch (_: Exception) {
                        false
                    }
                }

                if (isKnown) {
                    // Known contact — handled by normal mesh encounter logic, skip discovery alert
                    return@launch
                }

                // If a discovery handshake is already active for this MAC, do not re-notify
                if (inFlightStates.containsKey(mac)) return@launch

                // Rate limiting: Max 3 notifications per minute per MAC (>= 20 seconds interval)
                val now = System.currentTimeMillis()
                val lastTime = lastNotificationTime[mac] ?: 0L
                if (now - lastTime < 20_000L) {
                    Log.d(TAG, "GHOST_DISCOVERY: Rate limit hit for $mac (${now - lastTime}ms < 20000ms)")
                    return@launch
                }

                lastNotificationTime[mac] = now
                val fpHex = fingerprint.joinToString("") { "%02x".format(it) }
                val shortFp = fpHex.take(4)

                NotificationHelper.showDiscoveryNotification(context, mac, shortFp)
                Log.d(TAG, "GHOST_DISCOVERY: Posted discovery notification for $mac (fp=#$shortFp, rssi=$rssi)")
            } catch (e: Exception) {
                Log.e(TAG, "GHOST_DISCOVERY: Error processing peer detection for $mac: ${e.message}", e)
            }
        }
    }

    /**
     * Initiator taps "Connect" on the discovery notification.
     */
    fun initiateDiscovery(mac: String) {
        val posture = postureProvider()
        if (!posture.allowsUnknownPeerNotifications()) {
            Log.w(TAG, "GHOST_DISCOVERY: Discovery initiated in STEALTH mode - aborted")
            return
        }

        NotificationHelper.cancelDiscoveryNotification(context, mac)
        inFlightStates[mac] = DiscoveryState.REQUEST_SENT

        coroutineScope.launch {
            try {
                val seed = IdentityManager.getEd25519Seed()
                val edPub = IdentityManager.getEd25519PubKey()
                val xPub = IdentityManager.getX25519PubKey()
                val myName = IdentityManager.getDisplayName()

                val packet = DiscoveryProtocol.encodeRequest(seed, edPub, xPub, myName)
                Log.d(TAG, "GHOST_DISCOVERY: Sending discovery request to $mac (${packet.size} bytes)")

                BleManager.sendMessage(mac, packet) { success ->
                    if (!success) {
                        Log.w(TAG, "GHOST_DISCOVERY: Failed to transmit discovery request packet to $mac")
                    } else {
                        Log.d(TAG, "GHOST_DISCOVERY: Discovery request transmitted successfully to $mac")
                    }
                }

                // 10-second timeout for discovery request
                inFlightTimeouts.remove(mac)?.cancel()
                inFlightTimeouts[mac] = coroutineScope.launch {
                    delay(10_000L)
                    if (inFlightStates[mac] == DiscoveryState.REQUEST_SENT) {
                        Log.w(TAG, "GHOST_DISCOVERY: Request to $mac timed out")
                        inFlightStates.remove(mac)
                        inFlightTimeouts.remove(mac)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "GHOST_DISCOVERY: Error initiating discovery to $mac: ${e.message}", e)
                inFlightStates.remove(mac)
            }
        }
    }

    /**
     * Responder receives opcode 0x10 over GATT.
     */
    fun onIncomingRequest(mac: String, data: ByteArray) {
        if (!postureProvider().allowsUnknownPeerNotifications()) {
            Log.d(TAG, "GHOST_DISCOVERY: Ignoring incoming request in STEALTH mode")
            return
        }

        val request = DiscoveryProtocol.decodeRequest(data)
        if (request == null) {
            Log.w(TAG, "GHOST_DISCOVERY: Malformed request packet from $mac")
            return
        }

        if (!DiscoveryProtocol.verifyRequest(request)) {
            Log.e(TAG, "GHOST_DISCOVERY: Invalid signature on request from $mac")
            return
        }

        val hash = MessageDigest.getInstance("SHA-256").digest(request.ed25519Pub)
        val contactId = hash.take(8).joinToString("") { "%02x".format(it) }
        val shortHandle = contactId.take(6)

        inFlightStates[mac] = DiscoveryState.REQUEST_RECEIVED
        inFlightRequests[mac] = request

        // Auto-cleanup timeout for unanswered incoming requests (30s)
        inFlightTimeouts.remove(mac)?.cancel()
        inFlightTimeouts[mac] = coroutineScope.launch {
            delay(30_000L)
            if (inFlightStates[mac] == DiscoveryState.REQUEST_RECEIVED) {
                Log.d(TAG, "GHOST_DISCOVERY: Incoming request from $mac expired without response")
                inFlightStates.remove(mac)
                inFlightRequests.remove(mac)
                NotificationHelper.cancelDiscoveryNotification(context, mac)
            }
        }

        NotificationHelper.showIncomingDiscoveryNotification(context, mac, request.name, shortHandle)
        Log.d(TAG, "GHOST_DISCOVERY: Incoming contact request from '${request.name}' (#$shortHandle) at $mac")
    }

    /**
     * Responder taps "Accept" on incoming request notification.
     */
    fun acceptRequest(mac: String) {
        NotificationHelper.cancelDiscoveryNotification(context, mac)
        inFlightTimeouts.remove(mac)?.cancel()

        val request = inFlightRequests.remove(mac)
        if (request == null) {
            Log.w(TAG, "GHOST_DISCOVERY: No active incoming request to accept for $mac")
            return
        }

        inFlightStates[mac] = DiscoveryState.RESPONSE_SENT

        coroutineScope.launch {
            try {
                val seed = IdentityManager.getEd25519Seed()
                val edPub = IdentityManager.getEd25519PubKey()
                val xPub = IdentityManager.getX25519PubKey()
                val myName = IdentityManager.getDisplayName()

                val packet = DiscoveryProtocol.encodeResponse(
                    DiscoveryProtocol.STATUS_ACCEPT,
                    seed, edPub, xPub, myName
                )

                Log.d(TAG, "GHOST_DISCOVERY: Transmitting ACCEPT response to $mac")
                BleManager.sendMessage(mac, packet) { success ->
                    if (!success) {
                        Log.w(TAG, "GHOST_DISCOVERY: Failed to deliver ACCEPT response to $mac")
                    } else {
                        Log.d(TAG, "GHOST_DISCOVERY: ACCEPT response delivered to $mac")
                    }
                }

                inFlightStates[mac] = DiscoveryState.COMPLETED
                finalizeContact(request.name, request.ed25519Pub, request.x25519Pub, mac)
            } catch (e: Exception) {
                Log.e(TAG, "GHOST_DISCOVERY: Error accepting request from $mac: ${e.message}", e)
                inFlightStates.remove(mac)
            }
        }
    }

    /**
     * Responder taps "Decline" on incoming request notification.
     */
    fun declineRequest(mac: String) {
        NotificationHelper.cancelDiscoveryNotification(context, mac)
        inFlightTimeouts.remove(mac)?.cancel()
        inFlightRequests.remove(mac)
        inFlightStates.remove(mac)

        coroutineScope.launch {
            try {
                val seed = IdentityManager.getEd25519Seed()
                val edPub = IdentityManager.getEd25519PubKey()
                val xPub = IdentityManager.getX25519PubKey()
                val myName = IdentityManager.getDisplayName()

                val packet = DiscoveryProtocol.encodeResponse(
                    DiscoveryProtocol.STATUS_REJECT,
                    seed, edPub, xPub, myName
                )
                BleManager.sendMessage(mac, packet) {}
                Log.d(TAG, "GHOST_DISCOVERY: Declined discovery request from $mac")
            } catch (e: Exception) {
                Log.e(TAG, "GHOST_DISCOVERY: Error sending decline response to $mac: ${e.message}")
            }
        }
    }

    /**
     * Initiator receives opcode 0x11 over GATT.
     */
    fun onIncomingResponse(mac: String, data: ByteArray) {
        if (!postureProvider().allowsUnknownPeerNotifications()) {
            Log.d(TAG, "GHOST_DISCOVERY: Ignoring incoming response in STEALTH mode")
            return
        }

        val response = DiscoveryProtocol.decodeResponse(data)
        if (response == null) {
            Log.w(TAG, "GHOST_DISCOVERY: Malformed response packet from $mac")
            return
        }

        if (!DiscoveryProtocol.verifyResponse(response)) {
            Log.e(TAG, "GHOST_DISCOVERY: Invalid signature on response from $mac")
            return
        }

        inFlightTimeouts.remove(mac)?.cancel()

        if (response.status == DiscoveryProtocol.STATUS_ACCEPT) {
            inFlightStates[mac] = DiscoveryState.COMPLETED
            Log.d(TAG, "GHOST_DISCOVERY: Response ACCEPT received from $mac (${response.name})")
            finalizeContact(response.name, response.ed25519Pub, response.x25519Pub, mac)
        } else {
            inFlightStates.remove(mac)
            Log.d(TAG, "GHOST_DISCOVERY: Peer $mac declined or busy (status=${response.status})")
        }
    }

    /**
     * Inserts contact as mutually verified, creates system event message,
     * triggers heartbeat haptics and system notification.
     */
    private fun finalizeContact(
        name: String,
        ed25519Pub: ByteArray,
        x25519Pub: ByteArray,
        mac: String
    ) {
        coroutineScope.launch {
            try {
                val hash = MessageDigest.getInstance("SHA-256").digest(ed25519Pub)
                val contactId = hash.take(8).joinToString("") { "%02x".format(it) }
                val shortHandle = contactId.take(6)

                val contact = Contact(
                    id = contactId,
                    name = name,
                    ed25519PubKey = Base64.encodeToString(ed25519Pub, Base64.NO_WRAP),
                    x25519PubKey = Base64.encodeToString(x25519Pub, Base64.NO_WRAP),
                    bleAddress = mac,
                    isVerified = true
                )
                contactDao.insertOrUpdate(contact)

                val systemMsg = MessageEntity(
                    contactId = contactId,
                    content = "* mutual verification with $name *",
                    isOutgoing = false,
                    isVerified = true,
                    status = MessageEntity.STATUS_SENT,
                    timestamp = System.currentTimeMillis()
                )
                messageDao.insert(systemMsg)

                // Physical dual-pulse heartbeat haptics
                HapticHelper.heartbeatVerify(context)

                // High-priority mutual verification notification
                NotificationHelper.showMutualVerificationNotification(context, name)

                Log.d(TAG, "GHOST_DISCOVERY: Mutual contact established with $name (#$shortHandle) at $mac")
            } catch (e: Exception) {
                Log.e(TAG, "GHOST_DISCOVERY: Error finalizing contact: ${e.message}", e)
            }
        }
    }

    fun cleanup() {
        for ((_, job) in inFlightTimeouts) {
            job.cancel()
        }
        inFlightTimeouts.clear()
        inFlightStates.clear()
        inFlightRequests.clear()
    }

    companion object {
        private const val TAG = "DiscoveryManager"
    }
}
