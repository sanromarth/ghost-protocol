package com.ghostprotocol.router

import android.util.Log
import ghostrouter.DeliverHandler
import ghostrouter.Router
import kotlinx.coroutines.*

/**
 * Kotlin bridge for the Go spray-and-wait mesh router.
 * Wraps gomobile-generated ghostrouter.Router API.
 */
class GhostRouter(
    private val localId: ByteArray,
    private val dbPath: String,
    private val scope: CoroutineScope,
    private val onMessageForMe: (payload: ByteArray) -> Unit
) {
    private var router: Router? = null
    private val TAG = "GHOST_ROUTE"

    fun start() {
        try {
            router = Router(localId, dbPath)
            router?.setHandler(object : DeliverHandler {
                override fun onDeliver(senderId: ByteArray?, payload: ByteArray?) {
                    Log.d(TAG, ">>> ROUTER: Message delivered to us from ${senderId?.take(4)?.joinToString("") { "%02x".format(it) }}, ${payload?.size ?: 0} bytes")
                    payload?.let {
                        scope.launch(Dispatchers.IO) {
                            onMessageForMe(it)
                        }
                    }
                }
            })
            router?.start()
            Log.d(TAG, ">>> ROUTER: Started with localId=${localId.take(4).joinToString("") { "%02x".format(it) }}")
        } catch (e: Exception) {
            Log.e(TAG, ">>> ROUTER: Failed to start: ${e.message}")
        }
    }

    fun stop() {
        try {
            router?.stop()
            router = null
            Log.d(TAG, ">>> ROUTER: Stopped")
        } catch (e: Exception) {
            Log.e(TAG, ">>> ROUTER: Error stopping: ${e.message}")
        }
    }

    /**
     * Called when user sends a message.
     * Returns: Pair(isDirect, blobToSend)
     */
    fun sendMessage(dst: ByteArray, payload: ByteArray): Pair<Boolean, ByteArray?> {
        return try {
            val result = router?.sendMessage(dst, payload)
                ?: throw IllegalStateException("Router not started")
            if (result.status == "error") {
                throw IllegalStateException("Router error: failed to queue message")
            }
            if (result.blob != null && result.blob.isNotEmpty()) {
                Log.d(TAG, ">>> ROUTER: sendMessage → direct (${result.blob.size} bytes)")
                true to result.blob
            } else {
                Log.d(TAG, ">>> ROUTER: sendMessage → queued (status=${result.status})")
                false to null
            }
        } catch (e: Exception) {
            Log.e(TAG, ">>> ROUTER: sendMessage error: ${e.message}")
            throw e  // Let callers handle — they'll mark STATUS_FAILED
        }
    }

    /**
     * Called by BleManager when a peer is discovered.
     * Returns list of blobs to send to this peer.
     */
    fun onPeerDiscovered(peerId: ByteArray, rssi: Int): List<ByteArray> {
        return try {
            val blobList = router?.onPeerDiscovered(peerId, rssi.toLong())
            if (blobList != null && blobList.size() > 0) {
                val blobs = mutableListOf<ByteArray>()
                for (i in 0 until blobList.size().toInt()) {
                    blobList.get(i.toLong())?.let { blobs.add(it) }
                }
                Log.d(TAG, ">>> ROUTER: onPeerDiscovered → ${blobs.size} blobs to send")
                blobs
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, ">>> ROUTER: onPeerDiscovered error: ${e.message}")
            emptyList()
        }
    }

    /**
     * Called by BleManager when ANY data arrives over BLE.
     */
    fun onMessageReceived(payload: ByteArray): String {
        return try {
            val result = router?.onMessageReceived(payload) ?: "router not started"
            Log.d(TAG, ">>> ROUTER: onMessageReceived → $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, ">>> ROUTER: onMessageReceived error: ${e.message}")
            "error: ${e.message}"
        }
    }

    fun getStats(): String {
        return try {
            router?.stats ?: "{}"
        } catch (e: Exception) {
            "{\"error\": \"${e.message}\"}"
        }
    }
}
