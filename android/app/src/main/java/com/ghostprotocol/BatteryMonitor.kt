package com.ghostprotocol

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import kotlinx.coroutines.*

data class BatteryReading(
    val level: Int,
    val elapsedMinutes: Long,
    val drainPercent: Int,
    val timestamp: Long = System.currentTimeMillis()
)

object BatteryMonitor {
    private const val TAG = "GHOST_BATTERY"
    private var job: Job? = null
    private var startLevel: Int = -1
    private var startTime: Long = 0L
    
    val readings = java.util.Collections.synchronizedList(mutableListOf<BatteryReading>())
    
    fun start(context: Context) {
        job?.cancel() // Cancel any existing monitor to prevent leaks
        startLevel = getBatteryLevel(context)
        startTime = System.currentTimeMillis()
        Log.d(TAG, "Battery monitoring started at level=$startLevel%")
        
        job = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(15 * 60 * 1000L) // 15 minutes
                val level = getBatteryLevel(context)
                val elapsedMin = (System.currentTimeMillis() - startTime) / 60_000L
                val drain = startLevel - level
                val reading = BatteryReading(level, elapsedMin, drain)
                readings.add(reading)
                if (readings.size > 20) readings.removeAt(0)
                Log.d(TAG, "GHOST_BATTERY: level=${level}% elapsed_min=$elapsedMin drain=${drain}%")
            }
        }
    }
    
    fun stop() {
        job?.cancel()
        job = null
    }
    
    private fun getBatteryLevel(context: Context): Int {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        return if (scale > 0) (level * 100) / scale else -1
    }
}
