package com.ghostprotocol.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

import com.ghostprotocol.router.GhostRouter

data class DiscoveredPeer(
    val address: String,
    val name: String?,
    val rssi: Int,
    val lastSeen: Long,
    val fingerprint: ByteArray? = null
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

    private val _incomingMessages = MutableSharedFlow<IncomingBleMessage>(replay = 0, extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<IncomingBleMessage> = _incomingMessages.asSharedFlow()

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

    @SuppressLint("MissingPermission")
    fun start(ctx: Context) {
        if (isRunning || !hasPermissions(ctx)) return
        context = ctx.applicationContext
        bluetoothManager = context?.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        adapter = bluetoothManager?.adapter

        if (adapter == null || !adapter!!.isEnabled) {
            Log.e(TAG, "Bluetooth not available or not enabled")
            return
        }

        advertiser = adapter?.bluetoothLeAdvertiser
        scanner = adapter?.bluetoothLeScanner

        startGattServer()
        startAdvertising()
        startScanning()

        isRunning = true
        Log.d(TAG, "BleManager started")
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!isRunning) return

        advertiser?.stopAdvertising(advertiseCallback)
        scanner?.stopScan(scanCallback)

        gattServer?.close()
        gattServer = null

        isRunning = false
        Log.d(TAG, "BleManager stopped")
    }

    // ===== ADVERTISING =====

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()

        // Main advertising data: service UUID only (21 bytes with flags)
        val advData = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()

        // Scan response: fingerprint + TX power (separate 31-byte packet)
        val scanResponseBuilder = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(true)

        localFingerprint?.let { fp ->
            scanResponseBuilder.addManufacturerData(MANUFACTURER_ID, fp)
        }

        advertiser?.startAdvertising(settings, advData, scanResponseBuilder.build(), advertiseCallback)
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
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner?.startScan(filters, settings, scanCallback)
        Log.d(TAG, ">>> Scanning started for GHOST peers")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let {
                val address = it.device.address
                val name = try { it.device.name } catch (e: SecurityException) { null }
                val rssi = it.rssi
                val fingerprint = it.scanRecord?.getManufacturerSpecificData(MANUFACTURER_ID)

                val isNew = !peersMap.containsKey(address)
                // Preserve existing fingerprint if this scan result lacks scan response data
                val existingFp = peersMap[address]?.fingerprint
                peersMap[address] = DiscoveredPeer(
                    address = address,
                    name = name,
                    rssi = rssi,
                    lastSeen = System.currentTimeMillis(),
                    fingerprint = fingerprint ?: existingFp
                )
                _peers.value = peersMap.values.toList()

                if (isNew) {
                    val fpHex = fingerprint?.joinToString("") { "%02x".format(it) } ?: "none"
                    Log.d(TAG, ">>> Discovered NEW peer MAC=$address RSSI=$rssi fingerprint=$fpHex")
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
                val emitted = _incomingMessages.tryEmit(IncomingBleMessage(device.address, value))
                if (responseNeeded) {
                    val status = if (emitted) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
                    gattServer?.sendResponse(device, requestId, status, 0, null)
                }
                if (!emitted) {
                    Log.e(TAG, ">>> GATT SERVER: SharedFlow buffer full! Dropped message from ${device.address}")
                }
            } else {
                Log.e(TAG, ">>> GATT SERVER: Write request with wrong UUID or null value from ${device.address}")
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            Log.d(TAG, ">>> GATT SERVER: MTU changed to $mtu for ${device.address}")
        }
    }

    // ===== GATT CLIENT (send messages) =====

    @SuppressLint("MissingPermission")
    fun sendMessage(macAddress: String, data: ByteArray, onResult: (Boolean) -> Unit) {
        val ctx = context ?: run {
            Log.e(TAG, ">>> GATT CLIENT: No context, cannot send")
            onResult(false)
            return
        }
        val device = try {
            adapter?.getRemoteDevice(macAddress)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, ">>> GATT CLIENT: Invalid MAC address '$macAddress': ${e.message}")
            null
        } ?: run {
            Log.e(TAG, ">>> GATT CLIENT: Cannot get remote device $macAddress")
            onResult(false)
            return
        }

        Log.d(TAG, ">>> GATT CLIENT: Connecting to $macAddress to send ${data.size} bytes")

        val resultDelivered = java.util.concurrent.atomic.AtomicBoolean(false)
        fun deliverResult(success: Boolean) {
            if (resultDelivered.compareAndSet(false, true)) {
                onResult(success)
            }
        }

        // Timeout handler — if GATT doesn't respond in 10 seconds, abort
        val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var gattRef: BluetoothGatt? = null

        val gatt = device.connectGatt(ctx, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.d(TAG, ">>> GATT CLIENT: Connected to $macAddress, requesting MTU 512")
                        gatt.requestMtu(512)
                    } else {
                        Log.e(TAG, ">>> GATT CLIENT: Connection to $macAddress failed, status=$status")
                        gatt.close()
                        deliverResult(false)
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d(TAG, ">>> GATT CLIENT: Disconnected from $macAddress (status=$status)")
                    gatt.close()
                    // If we disconnected before write completed, deliver failure
                    deliverResult(false)
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, ">>> GATT CLIENT: MTU negotiated: $mtu bytes, discovering services...")
                } else {
                    Log.e(TAG, ">>> GATT CLIENT: MTU request failed (status=$status), proceeding with default MTU")
                }
                // Discover services regardless — even with small MTU, short messages may work
                gatt.discoverServices()
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, ">>> GATT CLIENT: Service discovery FAILED (status=$status)")
                    gatt.disconnect()
                    deliverResult(false)
                    return
                }
                val service = gatt.getService(SERVICE_UUID)
                if (service == null) {
                    Log.e(TAG, ">>> GATT CLIENT: Service $SERVICE_UUID NOT FOUND on $macAddress")
                    gatt.disconnect()
                    deliverResult(false)
                    return
                }
                val char = service.getCharacteristic(MESSAGE_CHAR_UUID)
                if (char == null) {
                    Log.e(TAG, ">>> GATT CLIENT: Characteristic $MESSAGE_CHAR_UUID NOT FOUND")
                    gatt.disconnect()
                    deliverResult(false)
                    return
                }
                // Set value and write type BEFORE calling writeCharacteristic
                char.value = data
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                val writeInitiated = gatt.writeCharacteristic(char)
                Log.d(TAG, ">>> GATT CLIENT: Write initiated=${writeInitiated}, ${data.size} bytes to $macAddress")
                if (!writeInitiated) {
                    Log.e(TAG, ">>> GATT CLIENT: writeCharacteristic() returned false!")
                    gatt.disconnect()
                    deliverResult(false)
                }
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                val success = status == BluetoothGatt.GATT_SUCCESS
                if (success) {
                    Log.d(TAG, ">>> GATT CLIENT: Write SUCCESS to ${gatt.device.address}")
                } else {
                    Log.e(TAG, ">>> GATT CLIENT: Write FAILED to ${gatt.device.address}, status=$status")
                }
                gatt.disconnect()
                deliverResult(success)
            }
        })
        gattRef = gatt

        // Schedule 10-second timeout — if GATT never responds, clean up
        timeoutHandler.postDelayed({
            if (resultDelivered.get()) return@postDelayed
            Log.e(TAG, ">>> GATT CLIENT: Connection timeout after 10s to $macAddress")
            try { gattRef?.close() } catch (_: Exception) {}
            deliverResult(false)
        }, 10_000)
    }
}
