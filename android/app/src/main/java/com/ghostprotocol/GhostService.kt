package com.ghostprotocol

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import kotlin.coroutines.resume
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ghostprotocol.ble.BleManager
import com.ghostprotocol.crypto.GhostCrypto
import com.ghostprotocol.data.GhostDatabase
import com.ghostprotocol.data.MessageEntity
import com.ghostprotocol.power.BatteryTelemetry
import com.ghostprotocol.power.PowerMode
import com.ghostprotocol.power.PowerPolicy
import com.ghostprotocol.power.PowerPolicyEngine
import com.ghostprotocol.power.TelemetrySnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import java.security.MessageDigest
import java.util.UUID

import com.ghostprotocol.discovery.DiscoveryManager
import com.ghostprotocol.router.GhostRouter
import com.ghostprotocol.security.SecurityPosture
import com.ghostprotocol.security.SecurityPostureManager
import com.ghostprotocol.util.NotificationHelper

class GhostService : Service() {

    companion object {
        // TODO(v0.3): Use bound service + Messenger instead of static StateFlow
        private val _currentPowerPolicy = MutableStateFlow(PowerPolicyEngine.DEFAULT_ECO_POLICY)
        val currentPowerPolicy: StateFlow<PowerPolicy> = _currentPowerPolicy.asStateFlow()

        // In-memory cache for verification packets received before contact QR is scanned
        val pendingVerifications = java.util.concurrent.ConcurrentHashMap<String, Long>()
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val TAG = "GHOST_BLE"
    private var ghostRouter: GhostRouter? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // v0.2/v0.3: Power policy engine, security posture manager, discovery manager, and telemetry
    private lateinit var powerPolicyEngine: PowerPolicyEngine
    private lateinit var securityPostureManager: SecurityPostureManager
    private lateinit var discoveryManager: DiscoveryManager
    private lateinit var batteryTelemetry: BatteryTelemetry
    private var lastEncounterTimeMs: Long = System.currentTimeMillis()
    private var cpuWakeupCount: Int = 0
    private var messagesForwardedCount: Int = 0
    private var messagesDeliveredCount: Int = 0

    // Dedup for incoming direct BLE messages (content SHA-256 → timestamp)
    // Prevents duplicate chat bubbles from BLE GATT retries
    private val recentMessageHashes = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // Listen for Bluetooth toggles to restart scanning & advertising automatically
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_OFF -> {
                        Log.d(TAG, ">>> Bluetooth turned OFF — stopping BleManager")
                        BleManager.stop()
                    }
                    BluetoothAdapter.STATE_ON -> {
                        Log.d(TAG, ">>> Bluetooth turned ON — restarting BleManager")
                        serviceScope.launch {
                            delay(1000L) // Wait for BT stack stabilization
                            BleManager.setLocalFingerprint(IdentityManager.getFingerprint())
                            BleManager.start(applicationContext)
                        }
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        IdentityManager.init(this)

        // Register for Bluetooth state changes
        try {
            registerReceiver(
                bluetoothStateReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register bluetoothStateReceiver: ${e.message}")
        }

        // v0.3: Initialize security posture manager with startup auto-revert check
        securityPostureManager = SecurityPostureManager.getInstance(applicationContext)
        val initialBattery = getBatteryLevel()
        securityPostureManager.checkBatteryRevert(initialBattery)

        // v0.2: Initialize power policy engine and telemetry
        powerPolicyEngine = PowerPolicyEngine(applicationContext)
        batteryTelemetry = BatteryTelemetry(applicationContext)

        createNotificationChannel()

        val notification = NotificationHelper.buildServiceNotification(
            this,
            PowerMode.ECO,
            securityPostureManager.getPosture(),
            0
        )
        startForeground(1, notification)

        // v0.3: Initialize DiscoveryManager for Nearby Peer Discovery
        val db = GhostDatabase.getInstance(applicationContext)
        discoveryManager = DiscoveryManager(
            context = applicationContext,
            contactDao = db.contactDao(),
            messageDao = db.messageDao(),
            coroutineScope = serviceScope,
            postureProvider = { securityPostureManager.getPosture() }
        )
        BleManager.discoveryManager = discoveryManager
        BleManager.postureProvider = { securityPostureManager.getPosture() }

        // React to posture changes immediately across the service lifecycle
        serviceScope.launch {
            securityPostureManager.postureFlow.collect { posture ->
                Log.d(TAG, ">>> PostureFlow triggered: ${posture.name} — evaluating policy")
                evaluateAndApplyPolicy()
            }
        }

        // Acquire partial wake lock to keep CPU alive for BLE scanning
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ghost:mesh_wakelock"
        ).apply {
            acquire(4 * 60 * 60 * 1000L) // 4 hours max — re-acquired on service restart
        }
        Log.d(TAG, ">>> WakeLock acquired")

        // Initialize Go mesh router
        initRouter()

        BleManager.setLocalFingerprint(IdentityManager.getFingerprint())
        BleManager.start(this)

        // Start background tasks
        startPeerMatching()
        startMessageProcessing()
        BatteryMonitor.start(this)

        // v0.2: Start policy update loop and telemetry recording
        startPolicyUpdateLoop()
        startTelemetryLoop()
    }

    private fun initRouter() {
        try {
            val pubKey = IdentityManager.getEd25519PubKey()
            val md = MessageDigest.getInstance("SHA-256")
            val localId = md.digest(pubKey)
            val dbPath = getDatabasePath("router.db").absolutePath

            // Ensure parent directory exists
            getDatabasePath("router.db").parentFile?.mkdirs()

            ghostRouter = GhostRouter(
                localId = localId,
                dbPath = dbPath,
                scope = serviceScope,
                onMessageForMe = { payload ->
                    // Routed message arrived for us — decrypt and save
                    messagesDeliveredCount++
                    processRoutedPayload(payload)
                }
            )
            ghostRouter?.start()
            BleManager.setRouter(ghostRouter)
            Log.d(TAG, ">>> ROUTER: Initialized, localId=${localId.take(4).joinToString("") { "%02x".format(it) }}")
        } catch (e: Exception) {
            Log.e(TAG, ">>> ROUTER: Init failed: ${e.message}")
        }
    }

    // ===== v0.2: POWER POLICY UPDATE LOOP =====

    /**
     * Every 30 seconds: collect device state, compute policy, apply to BLE and router.
     */
    private var lastAppliedPolicy: PowerPolicy? = null
    private var lastMode: PowerMode? = null
    private var lastPosture: SecurityPosture? = null

    private fun evaluateAndApplyPolicy(): PowerPolicy {
        val batteryPercent = getBatteryLevel()
        securityPostureManager.checkBatteryRevert(batteryPercent)
        val currentPosture = securityPostureManager.getPosture()

        val isCharging = getChargingStatus()
        val screenOn = isScreenOn()
        val peerCount = BleManager.peers.value.size
        val queueSize = getRouterQueueSize()
        val timeSinceLastEncounter = System.currentTimeMillis() - lastEncounterTimeMs

        val policy = powerPolicyEngine.updateInputs(
            batteryPercent = batteryPercent,
            isCharging = isCharging,
            screenOn = screenOn,
            peerCount = peerCount,
            queueSize = queueSize,
            timeSinceLastEncounterMs = timeSinceLastEncounter,
            isMoving = null, // Motion sensor not implemented yet
            securityPosture = currentPosture
        )

        // Self-healing: ensure BLE radio is actively running if permissions & BT are enabled
        if (!BleManager.isBleRunning()) {
            BleManager.setLocalFingerprint(IdentityManager.getFingerprint())
            BleManager.start(applicationContext)
        }

        applyPowerPolicy(policy)
        return policy
    }

    private fun applyPowerPolicy(policy: PowerPolicy) {
        _currentPowerPolicy.value = policy

        // Only update BLE and router when parameters actually change
        val prev = lastAppliedPolicy
        if (prev == null ||
            prev.scanIntervalMs != policy.scanIntervalMs ||
            prev.scanWindowMs != policy.scanWindowMs) {
            BleManager.setScanPolicy(policy.scanIntervalMs, policy.scanWindowMs)
        }

        if (prev == null ||
            prev.advertiseIntervalMs != policy.advertiseIntervalMs ||
            prev.txPowerLevel != policy.txPowerLevel) {
            BleManager.setAdvertisePolicy(policy.advertiseIntervalMs, policy.txPowerLevel)
        }

        if (prev == null || prev.relayWillingness != policy.relayWillingness) {
            ghostRouter?.setRelayWillingness(policy.relayWillingness)
        }

        lastAppliedPolicy = policy

        // Manage WakeLock: PROTEST / EMERGENCY always hold wakelock; otherwise follow policy.wakeLockRequired
        val wl = wakeLock
        val shouldHoldWl = policy.securityPosture != SecurityPosture.STEALTH || policy.wakeLockRequired
        if (wl != null) {
            if (!shouldHoldWl && wl.isHeld) {
                wl.release()
                Log.d(TAG, ">>> WakeLock released (mode=${policy.mode}, posture=${policy.securityPosture})")
            } else if (shouldHoldWl && !wl.isHeld) {
                wl.acquire(4 * 60 * 60 * 1000L)
                Log.d(TAG, ">>> WakeLock acquired (mode=${policy.mode}, posture=${policy.securityPosture})")
            }
        }

        // Log mode/posture transitions and update notification
        if (lastMode != policy.mode || lastPosture != policy.securityPosture) {
            Log.d(TAG, ">>> Posture: ${policy.securityPosture}, Power mode: ${policy.mode}, scan: ${policy.scanWindowMs}/${policy.scanIntervalMs}ms, relay: ${policy.relayWillingness}")
            lastMode = policy.mode
            lastPosture = policy.securityPosture
            updateNotification(policy.mode, policy.securityPosture)
        }
    }

    /**
     * Every 30 seconds: collect device state, compute policy, apply to BLE and router.
     */
    private fun startPolicyUpdateLoop() {
        serviceScope.launch {
            while (isActive) {
                try {
                    cpuWakeupCount++
                    evaluateAndApplyPolicy()
                } catch (e: Exception) {
                    Log.e(TAG, ">>> Policy update error: ${e.message}")
                }
                delay(30_000L)
            }
        }
    }

    /**
     * Every 60 seconds: record a telemetry snapshot to Room DB.
     */
    private fun startTelemetryLoop() {
        serviceScope.launch {
            while (isActive) {
                delay(60_000L)
                try {
                    val snapshot = TelemetrySnapshot(
                        timestamp = System.currentTimeMillis(),
                        batteryPercent = getBatteryLevel(),
                        batteryTemperature = getBatteryTemperature(),
                        isCharging = getChargingStatus(),
                        bleScanTimeMs = BleManager.cumulativeScanTimeMs,
                        bleAdvertiseTimeMs = BleManager.cumulativeAdvertiseTimeMs,
                        gattConnections = BleManager.gattConnectionCount.get(),
                        gattBytesTx = BleManager.gattBytesTx.get(),
                        gattBytesRx = BleManager.gattBytesRx.get(),
                        cpuWakeups = cpuWakeupCount,
                        messagesForwarded = messagesForwardedCount,
                        messagesDelivered = messagesDeliveredCount,
                        avgDeliveryLatencyMs = 0L, // Requires per-message tracking, deferred
                        currentMode = powerPolicyEngine.currentPolicy.value.mode,
                        peerCount = BleManager.peers.value.size
                    )
                    batteryTelemetry.recordSnapshot(snapshot)
                } catch (e: Exception) {
                    Log.e(TAG, ">>> Telemetry error: ${e.message}")
                }
            }
        }
    }

    // ===== BATTERY HELPERS =====

    private fun getBatteryLevel(): Int {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        return if (scale > 0) (level * 100) / scale else -1
    }

    private fun getChargingStatus(): Boolean {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun getBatteryTemperature(): Float {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        return temp / 10.0f // BatteryManager reports in tenths of °C
    }

    private fun isScreenOn(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isInteractive
    }

    private fun getRouterQueueSize(): Int {
        return try {
            val stats = ghostRouter?.getStats() ?: return 0
            // Parse JSON stats for messagesPending
            val regex = """"messagesPending":(\d+)""".toRegex()
            regex.find(stats)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            0
        }
    }

    // ===== NOTIFICATION UPDATES =====

    private fun updateNotification(mode: PowerMode, posture: SecurityPosture = securityPostureManager.getPosture()) {
        val notification = NotificationHelper.buildServiceNotification(
            this,
            mode,
            posture,
            BleManager.peers.value.size
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1, notification)
    }

    /**
     * Process a payload delivered via the mesh router (multi-hop).
     * Same decryption logic as direct messages.
     */
    private fun processRoutedPayload(ciphertext: ByteArray) {
        val db = GhostDatabase.getInstance(applicationContext)
        val contactDao = db.contactDao()
        val messageDao = db.messageDao()

        serviceScope.launch {
            try {
                val myX25519Secret = IdentityManager.getX25519Secret()
                val decrypted = GhostCrypto.decrypt(myX25519Secret, ciphertext)

                if (decrypted.size < 96) {
                    Log.e(TAG, ">>> ROUTED: payload too small (${decrypted.size} < 96)")
                    return@launch
                }

                val senderPubKey = decrypted.sliceArray(0 until 32)
                val plaintext = decrypted.sliceArray(32 until decrypted.size - 64)
                val signature = decrypted.sliceArray(decrypted.size - 64 until decrypted.size)

                val verifyData = decrypted.sliceArray(0 until decrypted.size - 64)
                val isVerified = GhostCrypto.verify(senderPubKey, verifyData, signature)

                val hash = MessageDigest.getInstance("SHA-256").digest(senderPubKey)
                val senderContactId = hash.sliceArray(0 until 8).joinToString("") { "%02x".format(it) }

                // Reply wire format: senderName\u0000REPLY\u0000quotedSender\u0000quotedText\u0000message
                // Backward-compatible: non-reply messages use senderName\u0000message (no REPLY token)
                val rawText = String(plaintext, Charsets.UTF_8)
                val parts = rawText.split('\u0000')
                val senderName: String?
                val replySender: String?
                val replyText: String?
                val text: String

                if (parts.size >= 5 && parts[1] == "REPLY") {
                    senderName = parts[0].ifEmpty { null }
                    replySender = parts[2].ifEmpty { null }
                    replyText = parts[3].ifEmpty { null }
                    text = parts.drop(4).joinToString("\u0000")
                } else if (parts.size >= 2) {
                    senderName = parts[0].ifEmpty { null }
                    replySender = null
                    replyText = null
                    text = parts.drop(1).joinToString("\u0000")
                } else {
                    senderName = null
                    replySender = null
                    replyText = null
                    text = rawText
                }
                Log.d(TAG, ">>> ROUTED DECRYPT SUCCESS: from=$senderContactId text=\"$text\" verified=$isVerified")

                val contact = contactDao.getById(senderContactId)
                if (contact == null) {
                    if (text.startsWith("* verified ") || text.startsWith("* mutual verification with ")) {
                        Log.d(TAG, ">>> PRE-SCAN ROUTED VERIFICATION CACHED from $senderContactId: $text")
                        pendingVerifications[senderContactId] = System.currentTimeMillis()
                    } else {
                        Log.w(TAG, ">>> ROUTED: unknown sender $senderContactId, dropping")
                    }
                    return@launch
                }

                // Sync username if sender included it and it changed
                if (senderName != null && senderName != contact.name) {
                    Log.d(TAG, ">>> NAME SYNC: '${contact.name}' → '$senderName'")
                    contactDao.updateName(senderContactId, senderName)
                }

                // Empty text = silent name-update packet, don't save as a message
                if (text.isEmpty()) {
                    Log.d(TAG, ">>> NAME UPDATE received from '${senderName}', no message to save")
                    return@launch
                }
                // Content-based dedup: prevent duplicate spray deliveries
                // Hashing ciphertext (which contains unique ephemeral keys and AES nonces)
                // catches exact packet retransmissions while allowing distinct messages with the same text ("hi" + "hi").
                val contentHash = MessageDigest.getInstance("SHA-256")
                    .digest(ciphertext).joinToString("") { "%02x".format(it) }
                val now = System.currentTimeMillis()
                val lastSeen = recentMessageHashes.put(contentHash, now)
                if (lastSeen != null && now - lastSeen < 5_000) {
                    Log.d(TAG, ">>> DEDUP: dropping duplicate routed message from $senderContactId")
                    return@launch
                }
                recentMessageHashes.entries.removeAll { now - it.value > 60_000 }

                val message = MessageEntity(
                    id = UUID.randomUUID().toString(),
                    contactId = senderContactId,
                    content = text,
                    isOutgoing = false,
                    timestamp = System.currentTimeMillis(),
                    isVerified = isVerified,
                    replyToSender = replySender,
                    replyToText = replyText
                )
                messageDao.insert(message)
                Log.d(TAG, ">>> ROUTED MESSAGE SAVED: from '${senderName ?: contact.name}' text=\"$text\"")
                checkAndHandleVerificationEvent(senderContactId, senderName ?: contact.name, text, messageDao, contactDao)

            } catch (e: Exception) {
                Log.e(TAG, ">>> ROUTED MESSAGE FAILED: ${e.message}")
            }
        }
    }

    /**
     * Observes BLE-discovered peers and matches their 4-byte fingerprints
     * to contacts in the Room database. When a match is found, updates
     * the contact's bleAddress so messages can be sent via GATT.
     */
    private fun startPeerMatching() {
        val db = GhostDatabase.getInstance(applicationContext)
        val contactDao = db.contactDao()

        serviceScope.launch {
            // Throttle: don't spam router for the same peer more than once per 10s
            // MUST be outside collectLatest so it persists across emissions
            val lastRouterCall = mutableMapOf<String, Long>()

            BleManager.peers.collectLatest { peers ->
                if (peers.isEmpty()) return@collectLatest

                val contacts = contactDao.getAllOnce()
                if (contacts.isEmpty()) return@collectLatest

                val contactFingerprints = mutableMapOf<String, String>()
                for (contact in contacts) {
                    try {
                        val pubKeyBytes = Base64.decode(contact.ed25519PubKey, Base64.NO_WRAP)
                        val digest = MessageDigest.getInstance("SHA-256")
                        val hash = digest.digest(pubKeyBytes)
                        val fp = hash.copyOfRange(0, 4)
                        val fpHex = fp.joinToString("") { "%02x".format(it) }
                        contactFingerprints[fpHex] = contact.id
                    } catch (e: Exception) {
                        // Skip contacts with invalid keys
                    }
                }

                for (peer in peers) {
                    val peerFp = peer.fingerprint ?: continue
                    val peerFpHex = peerFp.joinToString("") { "%02x".format(it) }

                    val matchedContactId = contactFingerprints[peerFpHex] ?: continue

                    val existingContact = contacts.find { it.id == matchedContactId } ?: continue

                    // Always update BLE address (may have rotated)
                    if (existingContact.bleAddress != peer.address) {
                        Log.d(TAG, ">>> MATCH: peer ${peer.address} fingerprint=$peerFpHex → contact '${existingContact.name}' (${existingContact.id})")
                        contactDao.updateBleAddress(matchedContactId, peer.address)
                    }

                    // Update last encounter time for policy engine
                    lastEncounterTimeMs = System.currentTimeMillis()

                    val now = System.currentTimeMillis()
                    val lastCall = lastRouterCall[matchedContactId] ?: 0L
                    val hasPendingRoom = db.messageDao().getSprayedOrPendingForContact(matchedContactId).isNotEmpty()

                    // Verification Handshake Check:
                    // If we verified this contact but mutual verification is not recorded yet,
                    // send our verification ping now that peer is in BLE range!
                    val contactMsgs = db.messageDao().getMessagesForContactOnce(matchedContactId)
                    val hasVerified = contactMsgs.any { it.content.startsWith("* verified ") }
                    val hasMutual = contactMsgs.any { it.content.startsWith("* mutual verification with ") }
                    if (hasVerified && !hasMutual) {
                        val myName = IdentityManager.getDisplayName()
                        sendVerificationPayload(existingContact.copy(bleAddress = peer.address), "$myName\u0000* verified $myName *")
                    }

                    // Throttle only if there are no pending messages to deliver
                    if (!hasPendingRoom && (now - lastCall < 10_000)) continue
                    lastRouterCall[matchedContactId] = now

                    // 1. Deliver router multihop/sprayed blobs if available
                    if (ghostRouter != null) {
                        try {
                            val contactEd25519Pub = Base64.decode(existingContact.ed25519PubKey, Base64.NO_WRAP)
                            val peerId = MessageDigest.getInstance("SHA-256").digest(contactEd25519Pub)
                            val blobs = ghostRouter!!.onPeerDiscovered(peerId, peer.rssi)

                            if (blobs.isNotEmpty()) {
                                val blob = blobs.first()
                                if (blobs.size == 1 && blob.size > 5 && (blob[0].toInt() and 0xFF) > 1) {
                                    val count = blob[0].toInt() and 0xFF
                                    Log.d(TAG, ">>> ROUTER SPRAY: sending batch of $count messages (${blob.size} bytes) to ${peer.address}")
                                    BleManager.sendBatch(peer.address, blob) { success ->
                                        if (success) {
                                            messagesForwardedCount += count
                                        }
                                        Log.d(TAG, ">>> ROUTER BATCH result: ${if (success) "SUCCESS ($count messages)" else "FAILED"}")
                                    }
                                } else {
                                    Log.d(TAG, ">>> ROUTER SPRAY: sending ${blob.size} bytes to ${peer.address} (${blobs.size} total queued)")
                                    BleManager.sendMessage(peer.address, blob) { success ->
                                        if (success) {
                                            messagesForwardedCount++
                                        }
                                        Log.d(TAG, ">>> ROUTER SPRAY result: ${if (success) "SUCCESS" else "FAILED"}")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, ">>> ROUTER onPeerDiscovered error: ${e.message}")
                        }
                    }

                    // 2. Deliver any pending or sprayed messages in Room DB for this contact
                    try {
                        val pendingRoom = db.messageDao().getSprayedOrPendingForContact(matchedContactId)
                        if (pendingRoom.isNotEmpty()) {
                            Log.d(TAG, ">>> RE-ENCOUNTER: Delivering ${pendingRoom.size} delayed/sprayed messages for '${existingContact.name}'")
                            val contactX25519Pub = Base64.decode(existingContact.x25519PubKey, Base64.NO_WRAP)
                            val myEd25519PubKey = IdentityManager.getEd25519PubKey()
                            val myName = IdentityManager.getDisplayName()

                            for (msg in pendingRoom) {
                                val wireText = if (msg.replyToText != null) {
                                    "$myName\u0000REPLY\u0000${msg.replyToSender ?: ""}\u0000${msg.replyToText}\u0000${msg.content}"
                                } else {
                                    "$myName\u0000${msg.content}"
                                }
                                val plaintextBytes = wireText.toByteArray(Charsets.UTF_8)
                                val payload = myEd25519PubKey + plaintextBytes
                                val signature = GhostCrypto.sign(IdentityManager.getEd25519Seed(), payload)
                                val fullPayload = payload + signature
                                val ciphertext = GhostCrypto.encrypt(contactX25519Pub, fullPayload)

                                val success = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                                    BleManager.sendMessage(peer.address, ciphertext) { ok ->
                                        if (cont.isActive) cont.resume(ok)
                                    }
                                }

                                if (success) {
                                    messagesForwardedCount++
                                    db.messageDao().updateStatus(msg.id, MessageEntity.STATUS_SENT)
                                    Log.d(TAG, ">>> DELIVERED delayed message ${msg.id} to '${existingContact.name}'")
                                } else {
                                    Log.e(TAG, ">>> FAILED to deliver delayed message ${msg.id} to ${peer.address}")
                                    break // Stop loop on failure to allow retry on next encounter
                                }
                                delay(300L) // Yield between successive GATT writes
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, ">>> Error delivering Room messages to '${existingContact.name}': ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * Processes ALL incoming BLE messages in the background.
     * Routes through Go router first:
     *   - "delivered" → router callback fires processRoutedPayload()
     *   - "forwarded" → message stored in Go BoltDB for spraying
     *   - "dropped" → ignored (duplicate, expired, hop limit)
     * Falls back to direct decrypt if no router is available.
     */
    private fun startMessageProcessing() {
        val db = GhostDatabase.getInstance(applicationContext)
        val contactDao = db.contactDao()
        val messageDao = db.messageDao()

        serviceScope.launch {
            BleManager.incomingMessages.collect { incoming ->
                try {
                    Log.d(TAG, ">>> PROCESSING incoming message: ${incoming.data.size} bytes from ${incoming.senderAddress}")

                    if (ghostRouter != null) {
                        // Route through Go router — it will call DeliverHandler.onDeliver()
                        // if the message is for us, which triggers processRoutedPayload()
                        val result = ghostRouter!!.onMessageReceived(incoming.data)
                        Log.d(TAG, ">>> ROUTER RESULT: $result")

                        if (result == "forwarded") {
                            messagesForwardedCount++
                        }

                        if (result.startsWith("error") || result.startsWith("router not")) {
                            // Router couldn't decode routing header OR router never started
                            Log.d(TAG, ">>> ROUTER fallback: trying direct decrypt for '$result'")
                            directDecryptAndSave(incoming.data, contactDao, messageDao)
                        }
                        // "delivered" → DeliverHandler callback already fired processRoutedPayload()
                        // "forwarded" → stored in BoltDB for spraying, no action needed
                        // "dropped: ..." → TTL/hop/duplicate, ignore
                    } else {
                        // Fallback: no router, decrypt directly (legacy path)
                        directDecryptAndSave(incoming.data, contactDao, messageDao)
                    }

                } catch (e: Exception) {
                    Log.e(TAG, ">>> MESSAGE PROCESSING FAILED: ${e.message}")
                    e.printStackTrace()
                }
            }
        }
    }

    /**
     * Direct decrypt path — handles raw ciphertext (no routing envelope).
     * Used as fallback when router can't decode, or when router is null.
     */
    private suspend fun directDecryptAndSave(
        data: ByteArray,
        contactDao: com.ghostprotocol.data.ContactDao,
        messageDao: com.ghostprotocol.data.MessageDao
    ) {
        try {
            val myX25519Secret = IdentityManager.getX25519Secret()
            val decrypted = GhostCrypto.decrypt(myX25519Secret, data)

            if (decrypted.size < 96) {
                Log.e(TAG, ">>> DECRYPT: payload too small (${decrypted.size} < 96)")
                return
            }

            val senderPubKey = decrypted.sliceArray(0 until 32)
            val plaintext = decrypted.sliceArray(32 until decrypted.size - 64)
            val signature = decrypted.sliceArray(decrypted.size - 64 until decrypted.size)

            val verifyData = decrypted.sliceArray(0 until decrypted.size - 64)
            val isVerified = GhostCrypto.verify(senderPubKey, verifyData, signature)

            val md = MessageDigest.getInstance("SHA-256")
            val hash = md.digest(senderPubKey)
            val senderContactId = hash.sliceArray(0 until 8).joinToString("") { "%02x".format(it) }

            // Reply wire format: senderName\u0000REPLY\u0000quotedSender\u0000quotedText\u0000message
            // Backward-compatible: non-reply messages use senderName\u0000message (no REPLY token)
            val rawText = String(plaintext, Charsets.UTF_8)
            val parts = rawText.split('\u0000')
            val senderName: String?
            val replySender: String?
            val replyText: String?
            val text: String

            if (parts.size >= 5 && parts[1] == "REPLY") {
                senderName = parts[0].ifEmpty { null }
                replySender = parts[2].ifEmpty { null }
                replyText = parts[3].ifEmpty { null }
                text = parts.drop(4).joinToString("\u0000")
            } else if (parts.size >= 2) {
                senderName = parts[0].ifEmpty { null }
                replySender = null
                replyText = null
                text = parts.drop(1).joinToString("\u0000")
            } else {
                senderName = null
                replySender = null
                replyText = null
                text = rawText
            }
            Log.d(TAG, ">>> DECRYPT SUCCESS: from contactId=$senderContactId text=\"$text\" verified=$isVerified")

            val contact = contactDao.getById(senderContactId)
            if (contact == null) {
                if (text.startsWith("* verified ") || text.startsWith("* mutual verification with ")) {
                    Log.d(TAG, ">>> PRE-SCAN DIRECT VERIFICATION CACHED from $senderContactId: $text")
                    pendingVerifications[senderContactId] = System.currentTimeMillis()
                } else {
                    Log.w(TAG, ">>> UNKNOWN SENDER: contactId=$senderContactId not in database, dropping message")
                }
                return
            }

            // Sync username if sender included it and it changed
            if (senderName != null && senderName != contact.name) {
                Log.d(TAG, ">>> NAME SYNC: '${contact.name}' → '$senderName'")
                contactDao.updateName(senderContactId, senderName)
            }

            // Empty text = silent name-update packet, don't save as a message
            if (text.isEmpty()) {
                Log.d(TAG, ">>> NAME UPDATE received from '${senderName}', no message to save")
                return
            }
            // Content-based dedup: prevent duplicate messages from BLE GATT retries
            // Hashing the received encrypted data ensures GATT packet retries are dropped
            // without dropping separate user messages with the same text.
            val contentHash = MessageDigest.getInstance("SHA-256")
                .digest(data).joinToString("") { "%02x".format(it) }
            val now = System.currentTimeMillis()
            val lastSeen = recentMessageHashes.put(contentHash, now)
            if (lastSeen != null && now - lastSeen < 5_000) {
                Log.d(TAG, ">>> DEDUP: dropping duplicate direct message from $senderContactId")
                return
            }
            // Evict old entries (older than 60s) to prevent unbounded growth
            recentMessageHashes.entries.removeAll { now - it.value > 60_000 }

            val message = MessageEntity(
                id = UUID.randomUUID().toString(),
                contactId = senderContactId,
                content = text,
                isOutgoing = false,
                timestamp = System.currentTimeMillis(),
                isVerified = isVerified,
                replyToSender = replySender,
                replyToText = replyText
            )
            messageDao.insert(message)
            messagesDeliveredCount++
            Log.d(TAG, ">>> MESSAGE SAVED: from '${senderName ?: contact.name}' text=\"$text\"")
            checkAndHandleVerificationEvent(senderContactId, senderName ?: contact.name, text, messageDao, contactDao)

        } catch (e: Exception) {
            Log.e(TAG, ">>> DIRECT DECRYPT FAILED: ${e.message}")
        }
    }

    private fun sendVerificationPayload(contact: com.ghostprotocol.data.Contact, wireText: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val contactX25519Pub = Base64.decode(contact.x25519PubKey, Base64.NO_WRAP)
                val myEd25519PubKey = IdentityManager.getEd25519PubKey()
                val plaintextBytes = wireText.toByteArray(Charsets.UTF_8)
                val payload = myEd25519PubKey + plaintextBytes
                val signature = GhostCrypto.sign(IdentityManager.getEd25519Seed(), payload)
                val fullPayload = payload + signature
                val ciphertext = GhostCrypto.encrypt(contactX25519Pub, fullPayload)

                val targetAddress = contact.bleAddress ?: run {
                    val contactPub = Base64.decode(contact.ed25519PubKey, Base64.NO_WRAP)
                    val fp = MessageDigest.getInstance("SHA-256").digest(contactPub).copyOfRange(0, 4)
                    BleManager.peers.value.find { it.fingerprint?.contentEquals(fp) == true }?.address
                }

                if (targetAddress != null) {
                    BleManager.sendMessage(targetAddress, ciphertext) { success ->
                        Log.d(TAG, ">>> VERIFICATION PAYLOAD to ${contact.name} ($targetAddress): $success")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, ">>> Error sending verification payload: ${e.message}")
            }
        }
    }

    private suspend fun checkAndHandleVerificationEvent(
        senderContactId: String,
        contactName: String,
        text: String,
        messageDao: com.ghostprotocol.data.MessageDao,
        contactDao: com.ghostprotocol.data.ContactDao
    ) {
        if (text.startsWith("* verified ")) {
            val allMessages = messageDao.getMessagesForContactOnce(senderContactId)
            val alreadyMutuallyVerified = allMessages.any { it.content.startsWith("* mutual verification with ") }

            if (!alreadyMutuallyVerified) {
                contactDao.updateVerified(senderContactId, true)
                val mutualMsg = MessageEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    contactId = senderContactId,
                    content = "* mutual verification with $contactName *",
                    isOutgoing = false,
                    isVerified = true,
                    status = MessageEntity.STATUS_DELIVERED,
                    timestamp = System.currentTimeMillis()
                )
                messageDao.insert(mutualMsg)
                com.ghostprotocol.util.NotificationHelper.showMutualVerificationNotification(applicationContext, contactName)

                // Send mutual ack back to peer
                val contact = contactDao.getById(senderContactId)
                if (contact != null) {
                    val myName = IdentityManager.getDisplayName()
                    sendVerificationPayload(contact, "$myName\u0000* mutual verification with $myName *")
                }
            }
        } else if (text.startsWith("* mutual verification with ")) {
            val allMessages = messageDao.getMessagesForContactOnce(senderContactId)
            val alreadyMutuallyVerified = allMessages.any { it.content.startsWith("* mutual verification with ") }
            if (!alreadyMutuallyVerified) {
                contactDao.updateVerified(senderContactId, true)
                val mutualMsg = MessageEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    contactId = senderContactId,
                    content = "* mutual verification with $contactName *",
                    isOutgoing = false,
                    isVerified = true,
                    status = MessageEntity.STATUS_DELIVERED,
                    timestamp = System.currentTimeMillis()
                )
                messageDao.insert(mutualMsg)
                com.ghostprotocol.util.NotificationHelper.showMutualVerificationNotification(applicationContext, contactName)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_STOP_SERVICE" -> {
                Log.d(TAG, ">>> User tapped Quit GHOST — stopping service")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            "ACTION_BROADCAST_NAME" -> {
                Log.d(TAG, ">>> Broadcasting name update to all contacts")
                broadcastNameUpdate()
            }
            NotificationHelper.ACTION_CYCLE_MODE -> {
                Log.d(TAG, ">>> User tapped cycle mode")
                cycleMode()
            }
            NotificationHelper.ACTION_CYCLE_POSTURE -> {
                val next = securityPostureManager.cyclePosture()
                val bat = getBatteryLevel()
                securityPostureManager.checkBatteryRevert(bat)
                Log.d(TAG, ">>> User tapped cycle posture -> ${next.name}")
                evaluateAndApplyPolicy()
            }
            "ACTION_SET_POSTURE" -> {
                val postureStr = intent.getStringExtra("EXTRA_POSTURE")
                Log.d(TAG, ">>> User requested posture: $postureStr")
                if (postureStr != null) {
                    try {
                        val target = SecurityPosture.valueOf(postureStr)
                        securityPostureManager.setPosture(target)
                        val bat = getBatteryLevel()
                        securityPostureManager.checkBatteryRevert(bat)
                        evaluateAndApplyPolicy()
                    } catch (e: Exception) {
                        Log.e(TAG, ">>> Invalid posture requested: $postureStr")
                    }
                }
            }
            NotificationHelper.ACTION_INITIATE_DISCOVERY -> {
                val mac = intent.getStringExtra(NotificationHelper.EXTRA_MAC) ?: intent.getStringExtra("EXTRA_MAC")
                Log.d(TAG, "GHOST_DISCOVERY: Received ACTION_INITIATE_DISCOVERY for $mac")
                if (mac != null) {
                    discoveryManager.initiateDiscovery(mac)
                }
            }
            NotificationHelper.ACTION_ACCEPT_DISCOVERY -> {
                val mac = intent.getStringExtra(NotificationHelper.EXTRA_MAC) ?: intent.getStringExtra("EXTRA_MAC")
                Log.d(TAG, "GHOST_DISCOVERY: Received ACTION_ACCEPT_DISCOVERY for $mac")
                if (mac != null) {
                    discoveryManager.acceptRequest(mac)
                }
            }
            NotificationHelper.ACTION_DECLINE_DISCOVERY -> {
                val mac = intent.getStringExtra(NotificationHelper.EXTRA_MAC) ?: intent.getStringExtra("EXTRA_MAC")
                Log.d(TAG, "GHOST_DISCOVERY: Received ACTION_DECLINE_DISCOVERY for $mac")
                if (mac != null) {
                    discoveryManager.declineRequest(mac)
                }
            }
            "ACTION_SET_POWER_MODE" -> {
                val modeStr = intent.getStringExtra("EXTRA_MODE")
                Log.d(TAG, ">>> User requested power mode: $modeStr")
                if (modeStr != null) {
                    if (modeStr == "AUTO") {
                        powerPolicyEngine.clearOverride()
                        Log.d(TAG, ">>> Power mode: AUTO (override cleared)")
                        evaluateAndApplyPolicy()
                    } else {
                        try {
                            val mode = PowerMode.valueOf(modeStr)
                            powerPolicyEngine.forceMode(mode, 3_600_000L) // 1 hour override
                            evaluateAndApplyPolicy()
                        } catch (e: Exception) {
                            Log.e(TAG, ">>> Invalid power mode requested: $modeStr")
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    /**
     * Cycle through power modes: ACTIVE → ECO → CRITICAL → DEEP_SLEEP → (auto).
     * Each forced mode lasts 1 hour, then reverts to automatic policy.
     */
    private fun cycleMode() {
        val currentOverride = powerPolicyEngine.getOverrideMode()
        val nextMode = when (currentOverride) {
            null -> PowerMode.ACTIVE
            PowerMode.ACTIVE -> PowerMode.ECO
            PowerMode.ECO -> PowerMode.CRITICAL
            PowerMode.CRITICAL -> PowerMode.DEEP_SLEEP
            PowerMode.DEEP_SLEEP -> {
                powerPolicyEngine.clearOverride()
                Log.d(TAG, ">>> Power mode: AUTO (override cleared)")
                evaluateAndApplyPolicy()
                return
            }
        }
        powerPolicyEngine.forceMode(nextMode, 3_600_000L) // 1 hour
        Log.d(TAG, ">>> Power mode forced: $nextMode (1 hour override)")
        evaluateAndApplyPolicy()
    }

    /**
     * Sends a silent name-update packet to all contacts.
     * Payload: "newName\0" (empty message text) — receiver updates contact name
     * but does NOT create a visible chat message.
     */
    private fun broadcastNameUpdate() {
        serviceScope.launch(Dispatchers.IO) {
            val db = GhostDatabase.getInstance(applicationContext)
            val contacts = db.contactDao().getAllOnce()
            val myName = IdentityManager.getDisplayName()
            val myEd25519PubKey = IdentityManager.getEd25519PubKey()
            val myEd25519Seed = IdentityManager.getEd25519Seed()

            // Pre-compute the signed payload once (same for all contacts)
            val plaintextBytes = (myName + "\u0000").toByteArray(Charsets.UTF_8)
            val payload = myEd25519PubKey + plaintextBytes
            val signature = GhostCrypto.sign(myEd25519Seed, payload)
            val fullPayload = payload + signature

            for (contact in contacts) {
                try {
                    val contactX25519Pub = Base64.decode(contact.x25519PubKey, Base64.NO_WRAP)

                    // Encrypt per-contact (each has a different X25519 pub key)
                    val ciphertext = GhostCrypto.encrypt(contactX25519Pub, fullPayload)

                    if (ghostRouter != null) {
                        val contactEd25519Pub = Base64.decode(contact.ed25519PubKey, Base64.NO_WRAP)
                        val dstId = MessageDigest.getInstance("SHA-256").digest(contactEd25519Pub)
                        val (isDirect, blob) = ghostRouter!!.sendMessage(dstId, ciphertext)
                        if (isDirect && blob != null && contact.bleAddress != null) {
                            BleManager.sendMessage(contact.bleAddress, blob) { success ->
                                Log.d(TAG, ">>> NAME BROADCAST to '${contact.name}': ${if (success) "sent" else "failed"}")
                            }
                        } else if (!isDirect) {
                            Log.d(TAG, ">>> NAME BROADCAST to '${contact.name}': queued via router")
                        } else {
                            Log.d(TAG, ">>> NAME BROADCAST to '${contact.name}': skipped (no BLE address)")
                        }
                    } else if (contact.bleAddress != null) {
                        BleManager.sendMessage(contact.bleAddress, ciphertext) { success ->
                            Log.d(TAG, ">>> NAME BROADCAST to '${contact.name}': ${if (success) "sent" else "failed"}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, ">>> NAME BROADCAST to '${contact.name}' FAILED: ${e.message}")
                }
            }
        }
    }

    override fun onDestroy() {
        Log.d(TAG, ">>> GhostService onDestroy")
        try {
            unregisterReceiver(bluetoothStateReceiver)
        } catch (_: Exception) {}
        BatteryMonitor.stop()
        try {
            discoveryManager.cleanup()
            BleManager.discoveryManager = null
            BleManager.postureProvider = null
        } catch (_: Exception) {}
        ghostRouter?.stop()
        serviceScope.cancel()
        BleManager.stop()
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, ">>> WakeLock released")
            }
        }
        super.onDestroy()
    }

    // Restart service if user swipes app away from recents
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, ">>> onTaskRemoved — scheduling restart")
        val restartIntent = Intent(applicationContext, GhostService::class.java)
        val pendingIntent = PendingIntent.getForegroundService(
            applicationContext, 1, restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        alarmManager.set(
            android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 1000,
            pendingIntent
        )
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "ghost_mesh_channel",
                "GHOST Mesh Service",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                setSound(null, null)  // No sound for ongoing notification
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
