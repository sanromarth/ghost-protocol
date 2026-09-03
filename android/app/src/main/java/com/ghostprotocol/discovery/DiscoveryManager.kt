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

import com.ghostprotocol.crypto.ShortCode
import com.ghostprotocol.crypto.ShortCodeGenerator
import com.ghostprotocol.security.ShortCodeManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DiscoveryState {
    IDLE,
    REQUEST_SENT,
    REQUEST_RECEIVED,
    RESPONSE_SENT,
    COMPLETED
}

enum class ShortCodeSearchStatus {
    IDLE,
    SEARCHING_NEARBY,
    SPRAYED_TO_MESH,
    FOUND,
    TIMED_OUT,
    NOT_FOUND
}

data class ShortCodeSearchResult(
    val status: ShortCodeSearchStatus,
    val name: String? = null,
    val handle: String? = null,
    val mac: String? = null,
    val error: String? = null
)

class DiscoveryManager(
    private val context: Context,
    private val contactDao: ContactDao,
    private val messageDao: MessageDao,
    private val coroutineScope: CoroutineScope,
    private val postureProvider: () -> SecurityPosture
) {
    var shortCodeManager: ShortCodeManager? = null
    var meshSender: ((dstPeerId: ByteArray, payload: ByteArray) -> Unit)? = null

    private val _shortCodeSearchState = MutableStateFlow(ShortCodeSearchResult(ShortCodeSearchStatus.IDLE))
    val shortCodeSearchState: StateFlow<ShortCodeSearchResult> = _shortCodeSearchState.asStateFlow()

    private var activeSearchTimeoutJob: Job? = null
    private var activeTargetCode: ShortCode? = null

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

    /**
     * Initiates search for a target ShortCode.
     * First checks nearby BLE peers. If found locally, queries directly via GATT (0x20).
     * If not found locally, sprays query over mesh network (0x22).
     */
    fun initiateShortCodeSearch(code: ShortCode) {
        val posture = postureProvider()
        if (!posture.allowsUnknownPeerNotifications()) {
            _shortCodeSearchState.value = ShortCodeSearchResult(
                status = ShortCodeSearchStatus.NOT_FOUND,
                error = "Short codes disabled in STEALTH mode"
            )
            return
        }

        activeTargetCode = code
        _shortCodeSearchState.value = ShortCodeSearchResult(ShortCodeSearchStatus.SEARCHING_NEARBY)
        activeSearchTimeoutJob?.cancel()

        coroutineScope.launch {
            try {
                val seed = IdentityManager.getEd25519Seed()
                val edPub = IdentityManager.getEd25519PubKey()
                val queryPacket = ShortCodeProtocol.encodeQuery(seed, edPub, code)
                val targetHint = ShortCodeGenerator.codeToFingerprintHint(code)

                // 1. Check local peers discovered by BleManager
                val peers = BleManager.peers.value
                val localMatch = peers.firstOrNull { peer ->
                    (peer.shortCodeHint != null && peer.shortCodeHint.contentEquals(targetHint)) ||
                    (peer.fingerprint != null && peer.fingerprint.contentEquals(targetHint))
                }

                if (localMatch != null) {
                    Log.d(TAG, "GHOST_SHORTCODE: Found nearby BLE match at ${localMatch.address} for code ${code.toCompactString()}")
                    BleManager.sendMessage(localMatch.address, queryPacket) { success ->
                        Log.d(TAG, "GHOST_SHORTCODE: Local GATT query sent to ${localMatch.address}: $success")
                    }
                } else {
                    // 2. Not found locally — broadcast/spray through Go mesh router (0x22)
                    Log.d(TAG, "GHOST_SHORTCODE: No direct BLE match for ${code.toCompactString()}, spraying to mesh")
                    _shortCodeSearchState.value = ShortCodeSearchResult(ShortCodeSearchStatus.SPRAYED_TO_MESH)

                    val meshPayload = ByteArray(1 + queryPacket.size)
                    meshPayload[0] = ShortCodeProtocol.OPCODE_MESH_QUERY
                    System.arraycopy(queryPacket, 0, meshPayload, 1, queryPacket.size)

                    val specialDst = MessageDigest.getInstance("SHA-256")
                        .digest("GHOST_SHORTCODE:${code.toCompactString()}".toByteArray(Charsets.UTF_8))

                    meshSender?.invoke(specialDst, meshPayload)
                }

                // 3. 30-second search timeout
                activeSearchTimeoutJob = coroutineScope.launch {
                    delay(30_000L)
                    if (_shortCodeSearchState.value.status == ShortCodeSearchStatus.SEARCHING_NEARBY ||
                        _shortCodeSearchState.value.status == ShortCodeSearchStatus.SPRAYED_TO_MESH
                    ) {
                        Log.d(TAG, "GHOST_SHORTCODE: Search timed out for ${code.toCompactString()}")
                        _shortCodeSearchState.value = ShortCodeSearchResult(
                            status = ShortCodeSearchStatus.TIMED_OUT,
                            error = "No response. The user may be out of range or the code may have expired."
                        )
                        activeTargetCode = null
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "GHOST_SHORTCODE: Error initiating search: ${e.message}", e)
                _shortCodeSearchState.value = ShortCodeSearchResult(
                    status = ShortCodeSearchStatus.NOT_FOUND,
                    error = e.message
                )
            }
        }
    }

    /**
     * Direct GATT query received (opcode 0x20).
     */
    fun onIncomingShortCodeQuery(mac: String, data: ByteArray) {
        if (!postureProvider().allowsUnknownPeerNotifications()) return

        val query = ShortCodeProtocol.decodeQuery(data) ?: run {
            Log.w(TAG, "GHOST_SHORTCODE: Failed to decode query from $mac")
            return
        }

        if (!ShortCodeProtocol.verifyQuery(query)) {
            Log.e(TAG, "GHOST_SHORTCODE: Invalid signature on query from $mac")
            return
        }

        val myCode = shortCodeManager?.currentCode?.value
        if (myCode == null ||
            myCode.epochDay != query.epochDay ||
            !myCode.word1.equals(query.word1, ignoreCase = true) ||
            !myCode.word2.equals(query.word2, ignoreCase = true) ||
            !myCode.word3.equals(query.word3, ignoreCase = true) ||
            myCode.number != query.number
        ) {
            // Anti-probing invariant: silent drop!
            Log.d(TAG, "GHOST_SHORTCODE: Query from $mac did not match current code. Silently dropping.")
            return
        }

        Log.d(TAG, "GHOST_SHORTCODE: Query matched our code! Sending FOUND response to $mac")
        coroutineScope.launch {
            try {
                val seed = IdentityManager.getEd25519Seed()
                val edPub = IdentityManager.getEd25519PubKey()
                val xPub = IdentityManager.getX25519PubKey()
                val myName = IdentityManager.getDisplayName()

                val respPacket = ShortCodeProtocol.encodeResponse(
                    status = ShortCodeProtocol.STATUS_FOUND,
                    ed25519Seed = seed,
                    ed25519Pub = edPub,
                    x25519Pub = xPub,
                    name = myName
                )

                BleManager.sendMessage(mac, respPacket) { success ->
                    Log.d(TAG, "GHOST_SHORTCODE: Response delivered to $mac: $success")
                }

                val requesterHash = MessageDigest.getInstance("SHA-256").digest(query.requesterEd25519Pub)
                val requesterHandle = requesterHash.take(8).joinToString("") { "%02x".format(it) }.take(6)
                finalizeContact("Peer #$requesterHandle", query.requesterEd25519Pub, query.requesterEd25519Pub, mac)
            } catch (e: Exception) {
                Log.e(TAG, "GHOST_SHORTCODE: Error responding to query: ${e.message}", e)
            }
        }
    }

    /**
     * Direct GATT response received (opcode 0x21).
     */
    fun onIncomingShortCodeResponse(mac: String, data: ByteArray) {
        if (!postureProvider().allowsUnknownPeerNotifications()) return

        val response = ShortCodeProtocol.decodeResponse(data) ?: run {
            Log.w(TAG, "GHOST_SHORTCODE: Failed to decode response from $mac")
            return
        }

        if (!ShortCodeProtocol.verifyResponse(response)) {
            Log.e(TAG, "GHOST_SHORTCODE: Invalid signature on response from $mac")
            return
        }

        if (response.status == ShortCodeProtocol.STATUS_FOUND) {
            activeSearchTimeoutJob?.cancel()
            val hash = MessageDigest.getInstance("SHA-256").digest(response.responderEd25519Pub)
            val handle = hash.take(8).joinToString("") { "%02x".format(it) }.take(6)

            _shortCodeSearchState.value = ShortCodeSearchResult(
                status = ShortCodeSearchStatus.FOUND,
                name = response.name,
                handle = handle,
                mac = mac
            )

            finalizeContact(response.name, response.responderEd25519Pub, response.responderX25519Pub, mac)
            NotificationHelper.showShortCodeFoundNotification(context, response.name, mac)
        }
    }

    /**
     * Mesh-routed query received (opcode 0x22).
     */
    fun onMeshShortCodeQueryReceived(data: ByteArray) {
        if (!postureProvider().allowsUnknownPeerNotifications()) return
        if (data.size < 2 || data[0] != ShortCodeProtocol.OPCODE_MESH_QUERY) return

        val queryData = data.copyOfRange(1, data.size)
        val query = ShortCodeProtocol.decodeQuery(queryData) ?: return
        if (!ShortCodeProtocol.verifyQuery(query)) return

        val myCode = shortCodeManager?.currentCode?.value
        if (myCode == null ||
            myCode.epochDay != query.epochDay ||
            !myCode.word1.equals(query.word1, ignoreCase = true) ||
            !myCode.word2.equals(query.word2, ignoreCase = true) ||
            !myCode.word3.equals(query.word3, ignoreCase = true) ||
            myCode.number != query.number
        ) {
            // Anti-probing: silent drop
            return
        }

        Log.d(TAG, "GHOST_SHORTCODE: Mesh query matched our code! Sending mesh response back to requester.")
        coroutineScope.launch {
            try {
                val seed = IdentityManager.getEd25519Seed()
                val edPub = IdentityManager.getEd25519PubKey()
                val xPub = IdentityManager.getX25519PubKey()
                val myName = IdentityManager.getDisplayName()

                val respPacket = ShortCodeProtocol.encodeResponse(
                    status = ShortCodeProtocol.STATUS_FOUND,
                    ed25519Seed = seed,
                    ed25519Pub = edPub,
                    x25519Pub = xPub,
                    name = myName
                )

                val meshResp = ByteArray(1 + respPacket.size)
                meshResp[0] = ShortCodeProtocol.OPCODE_MESH_RESPONSE
                System.arraycopy(respPacket, 0, meshResp, 1, respPacket.size)

                // Requester peerId is 32-byte SHA-256(requesterEd25519Pub)
                val requesterPeerId = MessageDigest.getInstance("SHA-256").digest(query.requesterEd25519Pub)
                meshSender?.invoke(requesterPeerId, meshResp)

                val requesterHandle = requesterPeerId.take(8).joinToString("") { "%02x".format(it) }.take(6)
                finalizeContact("Mesh Peer #$requesterHandle", query.requesterEd25519Pub, query.requesterEd25519Pub, "MESH")
            } catch (e: Exception) {
                Log.e(TAG, "GHOST_SHORTCODE: Error responding to mesh query: ${e.message}", e)
            }
        }
    }

    /**
     * Mesh-routed response received (opcode 0x23).
     */
    fun onMeshShortCodeResponseReceived(data: ByteArray) {
        if (!postureProvider().allowsUnknownPeerNotifications()) return
        if (data.size < 2 || data[0] != ShortCodeProtocol.OPCODE_MESH_RESPONSE) return

        val respData = data.copyOfRange(1, data.size)
        val response = ShortCodeProtocol.decodeResponse(respData) ?: return
        if (!ShortCodeProtocol.verifyResponse(response)) return

        if (response.status == ShortCodeProtocol.STATUS_FOUND) {
            activeSearchTimeoutJob?.cancel()
            val hash = MessageDigest.getInstance("SHA-256").digest(response.responderEd25519Pub)
            val handle = hash.take(8).joinToString("") { "%02x".format(it) }.take(6)

            _shortCodeSearchState.value = ShortCodeSearchResult(
                status = ShortCodeSearchStatus.FOUND,
                name = response.name,
                handle = handle,
                mac = "MESH"
            )

            finalizeContact(response.name, response.responderEd25519Pub, response.responderX25519Pub, "MESH")
            NotificationHelper.showShortCodeFoundNotification(context, response.name, "MESH")
        }
    }

    fun clearShortCodeSearch() {
        activeSearchTimeoutJob?.cancel()
        activeSearchTimeoutJob = null
        activeTargetCode = null
        _shortCodeSearchState.value = ShortCodeSearchResult(ShortCodeSearchStatus.IDLE)
    }

    fun cleanup() {
        activeSearchTimeoutJob?.cancel()
        clearShortCodeSearch()
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
