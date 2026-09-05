package com.ghostprotocol.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

import com.ghostprotocol.discovery.DiscoveryManager
import com.ghostprotocol.discovery.DiscoveryProtocol
import com.ghostprotocol.discovery.ShortCodeProtocol
import com.ghostprotocol.router.GhostRouter
import com.ghostprotocol.security.SecurityPosture
import com.ghostprotocol.security.ShortCodeManager
import com.ghostprotocol.security.allowsUnknownPeerNotifications

data class DiscoveredPeer(
    val address: String,
    val name: String?,
    val rssi: Int,
    val lastSeen: Long,
    val fingerprint: ByteArray? = null,
    val shortCodeHint: ByteArray? = null
)

data class IncomingBleMessage(
    val senderAddress: String,
    val data: ByteArray
)

object BleManager {
    private const val TAG = "GHOST_BLE"
    val SERVICE_UUID: UUID = UUID.fromString("47484F53-5400-1000-8000-00805F9B34FB")
    val MESSAGE_CHAR_UUID: UUID = UUID.fromString("47484F53-5401-1000-8000-00805F9B34FB")
    private const val MANUFACTURER_ID = 0x00FF
    private const val MANUFACTURER_ID_SHORTCODE = 0x00FE

    // Peer offline detection threshold: 12 seconds without advertising packet = offline
    const val PEER_OFFLINE_TIMEOUT_MS = 12_000L

    var discoveryManager: DiscoveryManager? = null
    var shortCodeManager: ShortCodeManager? = null
    var postureProvider: (() -> SecurityPosture)? = null

    private var context: Context? = null
    private var bluetoothManager: BluetoothManager? = null
    private var adapter: BluetoothAdapter? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var gattServer: BluetoothGattServer? = null
    private var isRunning = false

    private var localFingerprint: ByteArray? = null
    private var ghostRouter: GhostRouter? = null

    private val peersMap = ConcurrentHashMap<String, DiscoveredPeer>()
    private val _peers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    val peers: StateFlow<List<DiscoveredPeer>> = _peers.asStateFlow()
    private var peerPruningJob: Job? = null

    private val _incomingMessages = MutableSharedFlow<IncomingBleMessage>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val incomingMessages: SharedFlow<IncomingBleMessage> = _incomingMessages.asSharedFlow()

    // v0.3.8: Reassembly state for fragmented inbound GATT writes (Opcode 0xFB)
    data class ReassemblySession(
        val transferId: Int,
        val totalFragments: Int,
        val fragments: Array<ByteArray?>,
        var receivedCount: Int,
        var totalBytes: Int,
        val createdAt: Long = System.currentTimeMillis()
    )

    private const val MAX_REASSEMBLY_SESSIONS = 16
    private const val REASSEMBLY_TIMEOUT_MS = 30_000L
    private val reassemblySessions = ConcurrentHashMap<String, ReassemblySession>()

    // Serialized GATT client queue to prevent GATT 133 and resource leaks
    val gattQueue = GattOperationQueue(
        contextProvider = { context },
        adapterProvider = { adapter },
        serviceUuid = SERVICE_UUID,
        characteristicUuid = MESSAGE_CHAR_UUID,
        onBytesTx = { bytes -> gattBytesTx.addAndGet(bytes.toLong()) },
        onConnectionCountInc = { gattConnectionCount.incrementAndGet() }
    )

    // ===== POLICY STATE =====
    // Current policy values (updated by PowerPolicyEngine via GhostService)
    private var currentScanIntervalMs: Long = 2000L
    private var currentScanWindowMs: Long = 100L
    private var currentAdvIntervalMs: Long = 500L
    private var currentTxPowerLevel: Int = AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM

    // ===== TELEMETRY COUNTERS =====
    // Cumulative scan/advertise time tracked via elapsedRealtime
    private var scanStartedAt: Long = 0L // elapsedRealtime when scan started, 0 if not scanning
    private var advStartedAt: Long = 0L  // elapsedRealtime when advertising started, 0 if not advertising
    @Volatile var cumulativeScanTimeMs: Long = 0L
        private set
    @Volatile var cumulativeAdvertiseTimeMs: Long = 0L
        private set
    val gattConnectionCount = AtomicInteger(0)
    val gattBytesTx = AtomicLong(0L)
    val gattBytesRx = AtomicLong(0L)

    fun setLocalFingerprint(fingerprint: ByteArray) {
        localFingerprint = fingerprint
    }

    fun setRouter(router: GhostRouter?) {
        ghostRouter = router
        Log.d(TAG, ">>> Router ${if (router != null) "set" else "cleared"}")
    }

    fun getRouter(): GhostRouter? = ghostRouter

    fun hasPermissions(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun isBleRunning(): Boolean {
        return isRunning && adapter?.isEnabled == true && scanner != null && advertiser != null
    }

    @SuppressLint("MissingPermission")
    fun start(ctx: Context) {
        if (!hasPermissions(ctx)) return
        context = ctx.applicationContext
        bluetoothManager = context?.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        adapter = bluetoothManager?.adapter

        if (adapter == null || !adapter!!.isEnabled) {
            Log.e(TAG, "Bluetooth not available or not enabled")
            isRunning = false
            return
        }

        // Avoid duplicate start if already healthy
        if (isRunning && scanner != null && advertiser != null && gattServer != null) {
            return
        }

        stop() // Reset any previous dead state

        advertiser = adapter?.bluetoothLeAdvertiser
        scanner = adapter?.bluetoothLeScanner

        startGattServer()
        startAdvertising()
        startScanning()
        startPeerPruning()

        isRunning = true
        Log.d(TAG, "BleManager started successfully")
    }

    private fun startPeerPruning() {
        peerPruningJob?.cancel()
        peerPruningJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                delay(2000L)
                val cutoff = System.currentTimeMillis() - PEER_OFFLINE_TIMEOUT_MS
                var changed = false
                val it = peersMap.entries.iterator()
                while (it.hasNext()) {
                    val entry = it.next()
                    if (entry.value.lastSeen < cutoff) {
                        it.remove()
                        changed = true
                        Log.d(TAG, ">>> Pruned offline peer: ${entry.key} (last seen ${(System.currentTimeMillis() - entry.value.lastSeen)/1000}s ago)")
                    }
                }
                if (changed) {
                    _peers.value = peersMap.values.toList()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!isRunning && scanner == null && advertiser == null && gattServer == null) return

        peerPruningJob?.cancel()
        peerPruningJob = null
        peersMap.clear()
        _peers.value = emptyList()

        // Accumulate final scan/advertise time
        stopScanTimeTelemetry()
        stopAdvTimeTelemetry()

        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}

        // Cancel and cleanly close all outbound client GATT operations
        gattQueue.cancelAll()
        reassemblySessions.clear()

        try { gattServer?.close() } catch (_: Exception) {}
        gattServer = null
        scanner = null
        advertiser = null

        isRunning = false
        Log.d(TAG, "BleManager stopped")
    }

    // ===== POLICY METHODS =====

    /**
     * Update scan policy. Restarts the BLE scanner with new settings.
     * Safe to call while scanning — stops then restarts.
     */
    @SuppressLint("MissingPermission")
    fun setScanPolicy(intervalMs: Long, windowMs: Long) {
        if (intervalMs == currentScanIntervalMs && windowMs == currentScanWindowMs) return
        currentScanIntervalMs = intervalMs
        currentScanWindowMs = windowMs
        if (isRunning) {
            // Accumulate scan time before restart
            stopScanTimeTelemetry()
            try {
                scanner?.stopScan(scanCallback)
            } catch (e: Exception) {
                Log.e(TAG, ">>> stopScan error during policy update: ${e.message}")
            }
            startScanning()
            Log.d(TAG, ">>> Scan policy updated: interval=${intervalMs}ms, window=${windowMs}ms")
        }
    }

    /**
     * Update advertise policy. Restarts advertising with new settings.
     * Safe to call while advertising — stops then restarts.
     */
    @SuppressLint("MissingPermission")
    fun setAdvertisePolicy(intervalMs: Long, txPowerLevel: Int) {
        if (intervalMs == currentAdvIntervalMs && txPowerLevel == currentTxPowerLevel) return
        currentAdvIntervalMs = intervalMs
        currentTxPowerLevel = txPowerLevel
        if (isRunning) {
            // Accumulate advertise time before restart
            stopAdvTimeTelemetry()
            try {
                advertiser?.stopAdvertising(advertiseCallback)
            } catch (e: Exception) {
                Log.e(TAG, ">>> stopAdvertising error during policy update: ${e.message}")
            }
            startAdvertising()
            Log.d(TAG, ">>> Advertise policy updated: interval=${intervalMs}ms, txPower=$txPowerLevel")
        }
    }

    @SuppressLint("MissingPermission")
    fun updateAdvertiseData() {
        if (isRunning) {
            stopAdvTimeTelemetry()
            try {
                advertiser?.stopAdvertising(advertiseCallback)
            } catch (e: Exception) {
                Log.e(TAG, ">>> stopAdvertising error: ${e.message}")
            }
            startAdvertising()
            Log.d(TAG, ">>> Advertising restarted with updated payload data")
        }
    }

    // ===== TELEMETRY HELPERS =====

    private fun startScanTimeTelemetry() {
        scanStartedAt = SystemClock.elapsedRealtime()
    }

    private fun stopScanTimeTelemetry() {
        if (scanStartedAt > 0) {
            cumulativeScanTimeMs += SystemClock.elapsedRealtime() - scanStartedAt
            scanStartedAt = 0L
        }
    }

    private fun startAdvTimeTelemetry() {
        advStartedAt = SystemClock.elapsedRealtime()
    }

    private fun stopAdvTimeTelemetry() {
        if (advStartedAt > 0) {
            cumulativeAdvertiseTimeMs += SystemClock.elapsedRealtime() - advStartedAt
            advStartedAt = 0L
        }
    }

    // ===== ADVERTISING =====

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        val mode = when {
            currentAdvIntervalMs <= 100 -> AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
            currentAdvIntervalMs <= 500 -> AdvertiseSettings.ADVERTISE_MODE_BALANCED
            else -> AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(mode)
            .setTxPowerLevel(currentTxPowerLevel)
            .setConnectable(true)
            .build()

        // Main advertising data: service UUID + fingerprint (29 bytes <= 31 byte limit)
        val advDataBuilder = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)

        localFingerprint?.let { fp ->
            advDataBuilder.addManufacturerData(MANUFACTURER_ID, fp)
        }
        val advData = advDataBuilder.build()

        // Scan response: fingerprint + TX power (separate 31-byte packet)
        val scanResponseBuilder = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(true)

        localFingerprint?.let { fp ->
            scanResponseBuilder.addManufacturerData(MANUFACTURER_ID, fp)
        }

        val posture = postureProvider?.invoke() ?: SecurityPosture.STEALTH
        if (posture.allowsUnknownPeerNotifications()) {
            shortCodeManager?.getCurrentFingerprintHint()?.let { hint ->
                scanResponseBuilder.addManufacturerData(MANUFACTURER_ID_SHORTCODE, hint)
            }
        }

        advertiser?.startAdvertising(settings, advData, scanResponseBuilder.build(), advertiseCallback)
        startAdvTimeTelemetry()
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, ">>> Advertising started successfully (connectable=true)")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, ">>> Advertising FAILED: errorCode=$errorCode")
        }
    }

    // ===== SCANNING =====

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        )

        val scanMode = when {
            currentScanIntervalMs <= 600 || currentScanWindowMs >= 300 -> ScanSettings.SCAN_MODE_LOW_LATENCY
            currentScanIntervalMs <= 2000 -> ScanSettings.SCAN_MODE_BALANCED
            else -> ScanSettings.SCAN_MODE_LOW_POWER
        }

        val settings = ScanSettings.Builder()
            .setScanMode(scanMode)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(0L)
            .build()

        scanner?.startScan(filters, settings, scanCallback)
        startScanTimeTelemetry()
        Log.d(TAG, ">>> Scanning started for GHOST peers (mode=$scanMode)")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let {
                val address = it.device.address
                val name = try { it.device.name } catch (e: SecurityException) { null }
                val rssi = it.rssi
                val fingerprint = it.scanRecord?.getManufacturerSpecificData(MANUFACTURER_ID)
                val shortCodeHint = it.scanRecord?.getManufacturerSpecificData(MANUFACTURER_ID_SHORTCODE)

                val isNew = !peersMap.containsKey(address)
                // Preserve existing fingerprint if this scan result lacks scan response data
                val existingFp = peersMap[address]?.fingerprint
                val existingHint = peersMap[address]?.shortCodeHint
                peersMap[address] = DiscoveredPeer(
                    address = address,
                    name = name,
                    rssi = rssi,
                    lastSeen = System.currentTimeMillis(),
                    fingerprint = fingerprint ?: existingFp,
                    shortCodeHint = shortCodeHint ?: existingHint
                )
                _peers.value = peersMap.values.toList()

                if (isNew) {
                    val fpHex = fingerprint?.joinToString("") { "%02x".format(it) } ?: "none"
                    Log.d(TAG, ">>> Discovered NEW peer MAC=$address RSSI=$rssi fingerprint=$fpHex")
                }

                if (fingerprint != null) {
                    val posture = postureProvider?.invoke() ?: SecurityPosture.STEALTH
                    if (posture.allowsUnknownPeerNotifications()) {
                        discoveryManager?.onUnknownPeerDetected(address, fingerprint, rssi, posture)
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, ">>> Scan FAILED: errorCode=$errorCode")
        }
    }

    // ===== GATT SERVER (receive messages) =====

    @SuppressLint("MissingPermission")
    private fun startGattServer() {
        gattServer = bluetoothManager?.openGattServer(context, gattServerCallback)
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val messageChar = BluetoothGattCharacteristic(
            MESSAGE_CHAR_UUID,
            // PROPERTY_WRITE requires response (matches WRITE_TYPE_DEFAULT on client)
            // PROPERTY_NOTIFY for future use
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(messageChar)
        gattServer?.addService(service)
        Log.d(TAG, ">>> GATT server started with service=$SERVICE_UUID char=$MESSAGE_CHAR_UUID")
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val stateStr = if (newState == BluetoothProfile.STATE_CONNECTED) "CONNECTED" else "DISCONNECTED"
            Log.d(TAG, ">>> GATT SERVER: device ${device.address} $stateStr (status=$status)")
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                reassemblySessions.remove(device.address)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray?
        ) {
            if (characteristic.uuid == MESSAGE_CHAR_UUID && value != null) {
                Log.d(TAG, ">>> GATT SERVER: Write request from ${device.address}, ${value.size} bytes, responseNeeded=$responseNeeded")
                gattBytesRx.addAndGet(value.size.toLong())

                // Check for transport fragmentation (0xFB)
                if (value.isNotEmpty() && value[0] == GattOperationQueue.OPCODE_BLE_FRAGMENT) {
                    handleFragmentWrite(device, requestId, responseNeeded, value)
                    return
                }

                // Unfragmented payload: 100% backward compatible path
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
                handleIncomingPayload(device.address, value)
            } else {
                Log.e(TAG, ">>> GATT SERVER: Write request with wrong UUID or null value from ${device.address}")
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                }
            }
        }

        @SuppressLint("MissingPermission")
        private fun handleFragmentWrite(
            device: BluetoothDevice,
            requestId: Int,
            responseNeeded: Boolean,
            value: ByteArray
        ) {
            // Validate minimum header size
            if (value.size < GattOperationQueue.FRAGMENT_HEADER_SIZE) {
                Log.w(TAG, ">>> GATT SERVER: Dropping truncated 0xFB frame (${value.size} bytes) from ${device.address}")
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                }
                return
            }

            val transferId = ((value[1].toInt() and 0xFF) shl 8) or (value[2].toInt() and 0xFF)
            val fragIndex = ((value[3].toInt() and 0xFF) shl 8) or (value[4].toInt() and 0xFF)
            val totalFrags = ((value[5].toInt() and 0xFF) shl 8) or (value[6].toInt() and 0xFF)
            val slice = value.copyOfRange(GattOperationQueue.FRAGMENT_HEADER_SIZE, value.size)

            // Parameter bounds checking
            if (totalFrags !in 2..GattOperationQueue.MAX_TOTAL_FRAGMENTS ||
                fragIndex !in 0 until totalFrags ||
                slice.isEmpty()) {
                Log.w(TAG, ">>> GATT SERVER: Malformed fragment from ${device.address}: tid=$transferId, idx=$fragIndex, total=$totalFrags, sliceLen=${slice.size}")
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                }
                return
            }

            // Clean expired sessions
            val now = System.currentTimeMillis()
            reassemblySessions.entries.removeIf { now - it.value.createdAt > REASSEMBLY_TIMEOUT_MS }

            // Retrieve or create reassembly session
            var session = reassemblySessions[device.address]
            if (session != null && session.transferId != transferId) {
                Log.w(TAG, ">>> GATT SERVER: New transferId $transferId from ${device.address}, discarding incomplete transfer ${session.transferId}")
                reassemblySessions.remove(device.address)
                session = null
            }

            if (session == null) {
                if (reassemblySessions.size >= MAX_REASSEMBLY_SESSIONS) {
                    val oldestKey = reassemblySessions.minByOrNull { it.value.createdAt }?.key
                    if (oldestKey != null) reassemblySessions.remove(oldestKey)
                }
                session = ReassemblySession(
                    transferId = transferId,
                    totalFragments = totalFrags,
                    fragments = arrayOfNulls(totalFrags),
                    receivedCount = 0,
                    totalBytes = 0
                )
                reassemblySessions[device.address] = session
            }

            // Idempotent fragment insertion
            if (session.fragments[fragIndex] == null) {
                session.fragments[fragIndex] = slice
                session.receivedCount++
                session.totalBytes += slice.size
            }

            // Check bounded reconstructed size
            if (session.totalBytes > GattOperationQueue.MAX_RECONSTRUCTED_PAYLOAD_BYTES) {
                Log.e(TAG, ">>> GATT SERVER: Reassembled payload exceeded ${GattOperationQueue.MAX_RECONSTRUCTED_PAYLOAD_BYTES} bytes from ${device.address}; aborting session")
                reassemblySessions.remove(device.address)
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                }
                return
            }

            // Immediately acknowledge write request so client can proceed with next chunk
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }

            // Check if transfer is complete
            if (session.receivedCount == session.totalFragments) {
                reassemblySessions.remove(device.address)
                val fullData = ByteArray(session.totalBytes)
                var offset = 0
                for (i in 0 until session.totalFragments) {
                    val frag = session.fragments[i]
                    if (frag == null) {
                        Log.e(TAG, ">>> GATT SERVER: Reassembly slot $i missing for transferId $transferId from ${device.address}")
                        return
                    }
                    System.arraycopy(frag, 0, fullData, offset, frag.size)
                    offset += frag.size
                }

                // Nesting prohibition
                if (fullData.isNotEmpty() && fullData[0] == GattOperationQueue.OPCODE_BLE_FRAGMENT) {
                    Log.e(TAG, ">>> GATT SERVER: Nested 0xFB fragment rejected from ${device.address}")
                    return
                }

                Log.d(TAG, ">>> GATT SERVER: Successfully reassembled transfer $transferId (${fullData.size} bytes in ${session.totalFragments} frags) from ${device.address}")
                handleIncomingPayload(device.address, fullData)
            }
        }

        private fun handleIncomingPayload(address: String, payload: ByteArray) {
            if (payload.isEmpty()) return
            when (payload[0]) {
                DiscoveryProtocol.OPCODE_REQUEST -> {
                    discoveryManager?.onIncomingRequest(address, payload)
                }
                DiscoveryProtocol.OPCODE_RESPONSE -> {
                    discoveryManager?.onIncomingResponse(address, payload)
                }
                ShortCodeProtocol.OPCODE_QUERY -> {
                    discoveryManager?.onIncomingShortCodeQuery(address, payload)
                }
                ShortCodeProtocol.OPCODE_RESPONSE -> {
                    discoveryManager?.onIncomingShortCodeResponse(address, payload)
                }
                else -> {
                    val emitted = _incomingMessages.tryEmit(IncomingBleMessage(address, payload))
                    if (!emitted) {
                        Log.e(TAG, ">>> GATT SERVER: SharedFlow buffer full! Dropped message from $address")
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            Log.d(TAG, ">>> GATT SERVER: MTU changed to $mtu for ${device.address}")
        }
    }

    // ===== GATT CLIENT (serialized queue) =====

    @SuppressLint("MissingPermission")
    fun sendMessage(macAddress: String, data: ByteArray, onResult: (Boolean) -> Unit) {
        gattQueue.enqueue(macAddress, listOf(data), 10_000L, onResult)
    }

    @SuppressLint("MissingPermission")
    fun sendBatch(macAddress: String, batchData: ByteArray, onResult: (Boolean) -> Unit) {
        val chunks = GattOperationQueue.parseBatchChunks(batchData)
        if (chunks == null || chunks.isEmpty()) {
            Log.e(TAG, ">>> BATCH CLIENT: Invalid or empty batch data")
            onResult(false)
            return
        }
        val timeoutMs = minOf(45_000L, 10_000L + (chunks.size - 1) * 5_000L)
        gattQueue.enqueue(macAddress, chunks, timeoutMs, onResult)
    }
}
