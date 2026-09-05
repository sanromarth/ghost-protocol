package com.ghostprotocol.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serialized GATT client queue enforcing the invariant:
 * - Exactly ONE active GATT connection across outbound client transmissions.
 * - Exactly ONE GATT operation at a time.
 * - Strict state transitions: IDLE -> CONNECTING -> CONNECTED -> NEGOTIATING_MTU -> DISCOVERING_SERVICES -> WRITING -> DISCONNECTING -> CLOSED.
 * - Centralized, bounded cool-off before reconnecting to the same physical MAC address.
 * - Guaranteed teardown (gatt.close()) on all terminal paths (success, failure, timeout, disconnect, abort).
 * - Full Android API 26+ compatibility with modern API 33+ writeCharacteristic support.
 */
class GattOperationQueue(
    private val contextProvider: () -> Context?,
    private val adapterProvider: () -> BluetoothAdapter?,
    private val serviceUuid: UUID,
    private val characteristicUuid: UUID,
    private val onBytesTx: (Int) -> Unit = {},
    private val onConnectionCountInc: () -> Unit = {}
) {
    companion object {
        private const val TAG = "GHOST_GATT_QUEUE"
        const val DEFAULT_COOL_OFF_MS = 150L

        // v0.3.8: Transport fragmentation framing constants
        const val OPCODE_BLE_FRAGMENT: Byte = 0xFB.toByte()
        const val FRAGMENT_HEADER_SIZE = 7
        const val MAX_TOTAL_FRAGMENTS = 300
        const val MAX_RECONSTRUCTED_PAYLOAD_BYTES = 65536
        const val DEFAULT_ATT_MTU = 23

        /**
         * Slices a payload into GATT transport frames (opcode 0xFB) if it exceeds MTU payload capacity.
         * If payload fits within negotiated MTU (payload.size <= negotiatedMtu - 3), returns the payload
         * unchanged in a single-element list (100% backward compatible, zero framing overhead).
         */
        fun slicePayload(
            data: ByteArray,
            negotiatedMtu: Int = DEFAULT_ATT_MTU,
            transferId: Int = (java.security.SecureRandom().nextInt(0xFFFF) + 1)
        ): List<ByteArray> {
            val maxWritePayload = maxOf(20, negotiatedMtu - 3)
            if (data.size <= maxWritePayload) {
                return listOf(data)
            }

            require(data.size <= MAX_RECONSTRUCTED_PAYLOAD_BYTES) {
                "Payload size ${data.size} exceeds maximum allowable transport payload ($MAX_RECONSTRUCTED_PAYLOAD_BYTES bytes)"
            }

            val maxSlice = maxWritePayload - FRAGMENT_HEADER_SIZE
            require(maxSlice > 0) { "MTU $negotiatedMtu too small for fragmentation framing" }

            val totalFragments = (data.size + maxSlice - 1) / maxSlice
            require(totalFragments in 2..MAX_TOTAL_FRAGMENTS) {
                "Total fragments $totalFragments out of valid bounds (2..$MAX_TOTAL_FRAGMENTS)"
            }

            val frames = ArrayList<ByteArray>(totalFragments)
            val tid = transferId and 0xFFFF

            for (i in 0 until totalFragments) {
                val offset = i * maxSlice
                val sliceLen = minOf(maxSlice, data.size - offset)
                val frame = ByteArray(FRAGMENT_HEADER_SIZE + sliceLen)
                frame[0] = OPCODE_BLE_FRAGMENT
                frame[1] = ((tid shr 8) and 0xFF).toByte()
                frame[2] = (tid and 0xFF).toByte()
                frame[3] = ((i shr 8) and 0xFF).toByte()
                frame[4] = (i and 0xFF).toByte()
                frame[5] = ((totalFragments shr 8) and 0xFF).toByte()
                frame[6] = (totalFragments and 0xFF).toByte()
                System.arraycopy(data, offset, frame, FRAGMENT_HEADER_SIZE, sliceLen)
                frames.add(frame)
            }
            return frames
        }

        /**
         * Parses a batched payload: [1B count][4B len1][msg1][4B len2][msg2]...
         * Returns null if payload is invalid or truncated.
         */
        fun parseBatchChunks(batchData: ByteArray): List<ByteArray>? {
            if (batchData.isEmpty()) return null
            val count = batchData[0].toInt() and 0xFF
            if (count < 1) return null

            val chunks = ArrayList<ByteArray>(count)
            var offset = 1
            for (i in 0 until count) {
                if (offset + 4 > batchData.size) return null
                val msgLen = ByteBuffer.wrap(batchData, offset, 4).order(ByteOrder.BIG_ENDIAN).int
                offset += 4
                if (msgLen <= 0 || offset + msgLen > batchData.size) return null
                chunks.add(batchData.copyOfRange(offset, offset + msgLen))
                offset += msgLen
            }
            return chunks
        }

        /**
         * Cross-API characteristic write supporting API 26 through API 34+.
         */
        @SuppressLint("MissingPermission")
        fun writeCharacteristicCompat(
            gatt: BluetoothGatt,
            char: BluetoothGattCharacteristic,
            data: ByteArray,
            writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        ): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val status = gatt.writeCharacteristic(char, data, writeType)
                status == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                char.value = data
                @Suppress("DEPRECATION")
                char.writeType = writeType
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(char)
            }
        }
    }

    enum class GattState {
        IDLE,
        CONNECTING,
        CONNECTED,
        NEGOTIATING_MTU,
        DISCOVERING_SERVICES,
        WRITING,
        DISCONNECTING,
        CLOSED
    }

    data class QueueItem(
        val macAddress: String,
        val chunks: List<ByteArray>,
        val timeoutMs: Long,
        val onResult: (Boolean) -> Unit
    )

    private val queue = ConcurrentLinkedQueue<QueueItem>()
    private val isRunning = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    // State tracking
    @Volatile var currentState: GattState = GattState.IDLE
        private set
    @Volatile private var activeGatt: BluetoothGatt? = null
    @Volatile private var currentItem: QueueItem? = null
    private val resultDelivered = AtomicBoolean(false)
    private var timeoutRunnable: Runnable? = null

    // Cool-off tracking per MAC address to eliminate GATT 133 on rapid reconnects
    private val lastDisconnectPerMac = ConcurrentHashMap<String, Long>()
    var coolOffMs: Long = DEFAULT_COOL_OFF_MS

    fun queueSize(): Int = queue.size + (if (currentItem != null) 1 else 0)

    /**
     * Enqueues an outbound transmission. If queue is idle, starts execution immediately.
     */
    fun enqueue(macAddress: String, chunks: List<ByteArray>, timeoutMs: Long, onResult: (Boolean) -> Unit) {
        val adapter = adapterProvider()
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth unavailable or disabled, rejecting send to $macAddress")
            onResult(false)
            return
        }

        if (chunks.isEmpty()) {
            Log.w(TAG, "Empty chunks for $macAddress, rejecting")
            onResult(false)
            return
        }

        queue.offer(QueueItem(macAddress, chunks, timeoutMs, onResult))
        triggerNext()
    }

    /**
     * Cancels all pending operations and closes active connection.
     * Called during BleManager.stop() or Bluetooth disabled events.
     */
    fun cancelAll() {
        synchronized(this) {
            // Drain pending queue
            var item = queue.poll()
            while (item != null) {
                try { item.onResult(false) } catch (_: Exception) {}
                item = queue.poll()
            }

            // Abort active session
            timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            timeoutRunnable = null

            val gatt = activeGatt
            if (gatt != null) {
                try { gatt.disconnect() } catch (_: Exception) {}
                try { gatt.close() } catch (_: Exception) {}
                activeGatt = null
            }

            if (resultDelivered.compareAndSet(false, true)) {
                try { currentItem?.onResult?.invoke(false) } catch (_: Exception) {}
            }
            currentItem = null
            currentState = GattState.IDLE
            isRunning.set(false)
        }
    }

    private fun triggerNext() {
        synchronized(this) {
            if (isRunning.get()) return
            val nextItem = queue.poll() ?: run {
                currentState = GattState.IDLE
                return
            }
            isRunning.set(true)
            currentItem = nextItem
            resultDelivered.set(false)
            executeItem(nextItem)
        }
    }

    @SuppressLint("MissingPermission")
    private fun executeItem(item: QueueItem) {
        val ctx = contextProvider() ?: run {
            Log.e(TAG, "No context available to connect to ${item.macAddress}")
            completeItem(item, false)
            return
        }

        val adapter = adapterProvider()
        val device = try {
            adapter?.getRemoteDevice(item.macAddress)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid MAC address '${item.macAddress}': ${e.message}")
            null
        } ?: run {
            completeItem(item, false)
            return
        }

        // Apply centralized cool-off if this MAC was recently disconnected
        val lastDisconnect = lastDisconnectPerMac[item.macAddress] ?: 0L
        val elapsed = SystemClock.elapsedRealtime() - lastDisconnect
        val waitMs = if (elapsed in 0 until coolOffMs) coolOffMs - elapsed else 0L

        if (waitMs > 0) {
            Log.d(TAG, "Applying ${waitMs}ms GATT cool-off before reconnecting to ${item.macAddress}")
            mainHandler.postDelayed({ proceedConnection(ctx, device, item) }, waitMs)
        } else {
            proceedConnection(ctx, device, item)
        }
    }

    @SuppressLint("MissingPermission")
    private fun proceedConnection(ctx: Context, device: BluetoothDevice, item: QueueItem) {
        synchronized(this) {
            if (currentItem !== item) return // Operation was cancelled while waiting for cool-off
            currentState = GattState.CONNECTING
        }

        Log.d(TAG, "Starting GATT connection to ${item.macAddress} (${item.chunks.size} chunks)")
        onConnectionCountInc()

        // Arm watchdog timeout
        val timeoutTask = Runnable {
            Log.w(TAG, "GATT watchdog timeout (${item.timeoutMs}ms) for ${item.macAddress} in state $currentState")
            teardownAndComplete(item, false)
        }
        timeoutRunnable = timeoutTask
        mainHandler.postDelayed(timeoutTask, item.timeoutMs)

        var negotiatedMtu = DEFAULT_ATT_MTU
        var activeWireChunks: List<ByteArray> = emptyList()
        var chunkIndex = 0
        var targetChar: BluetoothGattCharacteristic? = null

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        currentState = GattState.NEGOTIATING_MTU
                        Log.d(TAG, "Connected to ${item.macAddress}, delaying 100ms before requesting MTU 512")
                        mainHandler.postDelayed({
                            synchronized(this@GattOperationQueue) {
                                if (currentItem !== item || activeGatt !== gatt) return@synchronized
                                val mtuRequested = try {
                                    gatt.requestMtu(512)
                                } catch (e: Exception) {
                                    Log.w(TAG, "requestMtu threw exception: ${e.message}")
                                    false
                                }
                                if (!mtuRequested) {
                                    Log.w(TAG, "requestMtu returned false, proceeding to discover services with MTU $negotiatedMtu")
                                    currentState = GattState.DISCOVERING_SERVICES
                                    gatt.discoverServices()
                                }
                            }
                        }, 100L)
                    } else {
                        Log.e(TAG, "Connection failed to ${item.macAddress}, status=$status")
                        teardownAndComplete(item, false)
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d(TAG, "Disconnected from ${item.macAddress}, status=$status")
                    // Terminal disconnection
                    teardownAndComplete(item, false)
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                synchronized(this@GattOperationQueue) {
                    if (currentItem !== item) return@synchronized
                    if (status == BluetoothGatt.GATT_SUCCESS && mtu >= 23) {
                        negotiatedMtu = mtu
                        Log.d(TAG, "MTU successfully negotiated to $mtu for ${item.macAddress}")
                    } else {
                        Log.w(TAG, "MTU negotiation failed (status=$status), using MTU $negotiatedMtu for ${item.macAddress}")
                    }
                    currentState = GattState.DISCOVERING_SERVICES
                    val discoveryStarted = gatt.discoverServices()
                    if (!discoveryStarted) {
                        Log.e(TAG, "discoverServices returned false for ${item.macAddress}")
                        teardownAndComplete(item, false)
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "Service discovery failed (status=$status) for ${item.macAddress}")
                    teardownAndComplete(item, false)
                    return
                }

                val service = gatt.getService(serviceUuid)
                if (service == null) {
                    Log.e(TAG, "GHOST service $serviceUuid not found on ${item.macAddress}")
                    teardownAndComplete(item, false)
                    return
                }

                val char = service.getCharacteristic(characteristicUuid)
                if (char == null) {
                    Log.e(TAG, "GHOST characteristic $characteristicUuid not found on ${item.macAddress}")
                    teardownAndComplete(item, false)
                    return
                }

                targetChar = char
                currentState = GattState.WRITING

                // Slice chunks dynamically based on negotiatedMtu
                val wireChunks = ArrayList<ByteArray>()
                for (chunk in item.chunks) {
                    if (chunk.size <= negotiatedMtu - 3) {
                        wireChunks.add(chunk)
                    } else {
                        val frags = slicePayload(chunk, negotiatedMtu)
                        wireChunks.addAll(frags)
                    }
                }
                activeWireChunks = wireChunks
                chunkIndex = 0
                writeNextChunk(gatt, char)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "Write failed on chunk $chunkIndex/${activeWireChunks.size} (status=$status) to ${item.macAddress}")
                    teardownAndComplete(item, false)
                    return
                }

                val prevChunk = activeWireChunks[chunkIndex - 1]
                onBytesTx(prevChunk.size)
                Log.d(TAG, "Write SUCCESS chunk $chunkIndex/${activeWireChunks.size} to ${item.macAddress}")

                if (chunkIndex >= activeWireChunks.size) {
                    Log.d(TAG, "All ${activeWireChunks.size} chunks written successfully to ${item.macAddress}")
                    teardownAndComplete(item, true)
                } else {
                    targetChar?.let { writeNextChunk(gatt, it) } ?: teardownAndComplete(item, false)
                }
            }

            private fun writeNextChunk(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
                if (chunkIndex >= activeWireChunks.size) {
                    teardownAndComplete(item, true)
                    return
                }
                val chunk = activeWireChunks[chunkIndex]
                chunkIndex++
                val success = writeCharacteristicCompat(gatt, char, chunk)
                if (!success) {
                    Log.e(TAG, "writeCharacteristicCompat returned false for chunk $chunkIndex/${activeWireChunks.size} to ${item.macAddress}")
                    teardownAndComplete(item, false)
                }
            }
        }

        try {
            activeGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(ctx, false, gattCallback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "connectGatt threw exception for ${item.macAddress}: ${e.message}", e)
            teardownAndComplete(item, false)
        }
    }

    private fun teardownAndComplete(item: QueueItem, success: Boolean) {
        mainHandler.post {
            synchronized(this) {
                if (currentItem !== item) return@synchronized

                timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                timeoutRunnable = null

                currentState = GattState.DISCONNECTING
                val gatt = activeGatt
                if (gatt != null) {
                    try { gatt.disconnect() } catch (_: Exception) {}
                    try { gatt.close() } catch (_: Exception) {}
                    activeGatt = null
                }

                lastDisconnectPerMac[item.macAddress] = SystemClock.elapsedRealtime()
                currentState = GattState.CLOSED

                completeItem(item, success)
            }
        }
    }

    private fun completeItem(item: QueueItem, success: Boolean) {
        if (resultDelivered.compareAndSet(false, true)) {
            try {
                item.onResult(success)
            } catch (e: Exception) {
                Log.e(TAG, "Error in onResult callback: ${e.message}", e)
            }
        }
        synchronized(this) {
            currentItem = null
            isRunning.set(false)
            triggerNext()
        }
    }
}
