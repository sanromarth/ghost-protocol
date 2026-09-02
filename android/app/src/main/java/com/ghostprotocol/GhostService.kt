package com.ghostprotocol

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ghostprotocol.ble.BleManager
import com.ghostprotocol.crypto.GhostCrypto
import com.ghostprotocol.data.GhostDatabase
import com.ghostprotocol.data.MessageEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.security.MessageDigest
import java.util.UUID

import com.ghostprotocol.router.GhostRouter

class GhostService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val TAG = "GHOST_BLE"
    private var ghostRouter: GhostRouter? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Dedup for incoming direct BLE messages (content SHA-256 → timestamp)
    // Prevents duplicate chat bubbles from BLE GATT retries
    private val recentMessageHashes = java.util.concurrent.ConcurrentHashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()

        IdentityManager.init(this)

        createNotificationChannel()

        val quitIntent = Intent(this, GhostService::class.java).apply {
            action = "ACTION_STOP_SERVICE"
        }
        val quitPendingIntent = PendingIntent.getService(
            this, 0, quitIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "ghost_mesh_channel")
            .setContentTitle("GHOST Mesh Active")
            .setContentText("Scanning for peers...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "Quit GHOST", quitPendingIntent)
            .build()

        startForeground(1, notification)

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

                val rawText = String(plaintext, Charsets.UTF_8)

                // Parse username + \0 + message (v0.1.4+), backward-compat with plain text
                val nullIdx = rawText.indexOf('\u0000')
                val senderName: String?
                val text: String
                if (nullIdx > 0) {
                    senderName = rawText.substring(0, nullIdx)
                    text = rawText.substring(nullIdx + 1)
                } else {
                    senderName = null
                    text = rawText
                }
                Log.d(TAG, ">>> ROUTED DECRYPT SUCCESS: from=$senderContactId text=\"$text\" verified=$isVerified")

                val contact = contactDao.getById(senderContactId)
                if (contact == null) {
                    Log.w(TAG, ">>> ROUTED: unknown sender $senderContactId, dropping")
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
                // Window is 5 seconds — enough to catch BLE re-delivery but allows repeated text
                val contentHash = MessageDigest.getInstance("SHA-256")
                    .digest("$senderContactId:$text".toByteArray()).joinToString("") { "%02x".format(it) }
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
                    isVerified = isVerified
                )
                messageDao.insert(message)
                Log.d(TAG, ">>> ROUTED MESSAGE SAVED: from '${senderName ?: contact.name}' text=\"$text\"")

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

                    // Always notify Go router — it handles queued message delivery
                    // Throttle to once per 10s per peer to avoid spam
                    val now = System.currentTimeMillis()
                    val lastCall = lastRouterCall[matchedContactId] ?: 0L
                    if (now - lastCall < 10_000) continue
                    lastRouterCall[matchedContactId] = now

                    if (ghostRouter != null) {
                        try {
                            val contactEd25519Pub = Base64.decode(existingContact.ed25519PubKey, Base64.NO_WRAP)
                            val peerId = MessageDigest.getInstance("SHA-256").digest(contactEd25519Pub)
                            val blobs = ghostRouter!!.onPeerDiscovered(peerId, peer.rssi)
                            // Send ONE blob per discovery cycle to avoid concurrent GATT to same MAC
                            // Next peer-matching cycle will pick up remaining blobs
                            val blob = blobs.firstOrNull()
                            if (blob != null) {
                                Log.d(TAG, ">>> ROUTER SPRAY: sending ${blob.size} bytes to ${peer.address} (${blobs.size} total queued)")
                                BleManager.sendMessage(peer.address, blob) { success ->
                                    Log.d(TAG, ">>> ROUTER SPRAY result: ${if (success) "SUCCESS" else "FAILED"}")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, ">>> ROUTER onPeerDiscovered error: ${e.message}")
                        }
                    }

                    // Auto-retry pending messages — only when router is NOT active
                    if (ghostRouter == null) {
                        try {
                            val pendingMessages = db.messageDao().getPendingMessages()
                            val contactPending = pendingMessages.filter { it.contactId == matchedContactId }
                            for (msg in contactPending) {
                                Log.d(TAG, ">>> AUTO-RETRY: sending pending message '${msg.content.take(20)}' to ${peer.address}")
                                val contact = contactDao.getById(matchedContactId) ?: continue
                                val contactX25519Pub = Base64.decode(contact.x25519PubKey, Base64.NO_WRAP)
                                val myEd25519PubKey = IdentityManager.getEd25519PubKey()
                                // Must match ChatViewModel format: username\0message
                                val myName = IdentityManager.getDisplayName()
                                val plaintextBytes = (myName + "\u0000" + msg.content).toByteArray(Charsets.UTF_8)
                                val payload = myEd25519PubKey + plaintextBytes
                                val signature = GhostCrypto.sign(IdentityManager.getEd25519Seed(), payload)
                                val fullPayload = payload + signature
                                val ciphertext = GhostCrypto.encrypt(contactX25519Pub, fullPayload)
                                BleManager.sendMessage(peer.address, ciphertext) { success ->
                                    serviceScope.launch {
                                        db.messageDao().updateStatus(
                                            msg.id,
                                            if (success) MessageEntity.STATUS_SENT else MessageEntity.STATUS_FAILED
                                        )
                                        Log.d(TAG, ">>> AUTO-RETRY result: ${if (success) "SUCCESS" else "FAILED"} for '${msg.content.take(20)}'")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, ">>> AUTO-RETRY error: ${e.message}")
                        }
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

            val rawText = String(plaintext, Charsets.UTF_8)

            // Parse username + \0 + message (v0.1.4+), backward-compat with plain text
            val nullIdx = rawText.indexOf('\u0000')
            val senderName: String?
            val text: String
            if (nullIdx > 0) {
                senderName = rawText.substring(0, nullIdx)
                text = rawText.substring(nullIdx + 1)
            } else {
                senderName = null
                text = rawText
            }
            Log.d(TAG, ">>> DECRYPT SUCCESS: from contactId=$senderContactId text=\"$text\" verified=$isVerified")

            val contact = contactDao.getById(senderContactId)
            if (contact == null) {
                Log.w(TAG, ">>> UNKNOWN SENDER: contactId=$senderContactId not in database, dropping message")
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
            val contentHash = MessageDigest.getInstance("SHA-256")
                .digest("$senderContactId:$text".toByteArray()).joinToString("") { "%02x".format(it) }
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
                isVerified = isVerified
            )
            messageDao.insert(message)
            Log.d(TAG, ">>> MESSAGE SAVED: from '${senderName ?: contact.name}' text=\"$text\"")

        } catch (e: Exception) {
            Log.e(TAG, ">>> DIRECT DECRYPT FAILED: ${e.message}")
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
        }
        return START_STICKY
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
        BatteryMonitor.stop()
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
            android.os.SystemClock.elapsedRealtime() + 1000,
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
