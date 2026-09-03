package com.ghostprotocol.power

import android.content.Context
import androidx.room.*
import java.text.SimpleDateFormat
import java.util.*

@Entity(tableName = "telemetry_snapshots")
data class TelemetryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val batteryPercent: Int,
    val batteryTemperature: Float,
    val isCharging: Boolean,
    val bleScanTimeMs: Long,
    val bleAdvertiseTimeMs: Long,
    val gattConnections: Int,
    val gattBytesTx: Long,
    val gattBytesRx: Long,
    val cpuWakeups: Int,
    val messagesForwarded: Int,
    val messagesDelivered: Int,
    val avgDeliveryLatencyMs: Long,
    val currentMode: String,
    val peerCount: Int
)

@Dao
interface TelemetryDao {
    @Insert
    suspend fun insert(entity: TelemetryEntity)

    @Query("SELECT * FROM telemetry_snapshots WHERE timestamp > :since ORDER BY timestamp ASC")
    suspend fun getSince(since: Long): List<TelemetryEntity>

    @Query("DELETE FROM telemetry_snapshots WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("SELECT COUNT(*) FROM telemetry_snapshots")
    suspend fun count(): Int
}

data class TelemetrySnapshot(
    val timestamp: Long,
    val batteryPercent: Int,
    val batteryTemperature: Float,
    val isCharging: Boolean,
    val bleScanTimeMs: Long,
    val bleAdvertiseTimeMs: Long,
    val gattConnections: Int,
    val gattBytesTx: Long,
    val gattBytesRx: Long,
    val cpuWakeups: Int,
    val messagesForwarded: Int,
    val messagesDelivered: Int,
    val avgDeliveryLatencyMs: Long,
    val currentMode: PowerMode,
    val peerCount: Int
)

class BatteryTelemetry(private val context: Context) {

    private fun getDao(): TelemetryDao {
        return com.ghostprotocol.data.GhostDatabase.getInstance(context).telemetryDao()
    }

    suspend fun recordSnapshot(snapshot: TelemetrySnapshot) {
        val entity = TelemetryEntity(
            timestamp = snapshot.timestamp,
            batteryPercent = snapshot.batteryPercent,
            batteryTemperature = snapshot.batteryTemperature,
            isCharging = snapshot.isCharging,
            bleScanTimeMs = snapshot.bleScanTimeMs,
            bleAdvertiseTimeMs = snapshot.bleAdvertiseTimeMs,
            gattConnections = snapshot.gattConnections,
            gattBytesTx = snapshot.gattBytesTx,
            gattBytesRx = snapshot.gattBytesRx,
            cpuWakeups = snapshot.cpuWakeups,
            messagesForwarded = snapshot.messagesForwarded,
            messagesDelivered = snapshot.messagesDelivered,
            avgDeliveryLatencyMs = snapshot.avgDeliveryLatencyMs,
            currentMode = snapshot.currentMode.name,
            peerCount = snapshot.peerCount
        )
        getDao().insert(entity)

        // Prune entries older than 48 hours to prevent unbounded growth
        val cutoff = System.currentTimeMillis() - 48 * 60 * 60 * 1000L
        getDao().deleteOlderThan(cutoff)
    }

    suspend fun getReport(): List<TelemetrySnapshot> {
        val since = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        return getDao().getSince(since).map { e ->
            TelemetrySnapshot(
                timestamp = e.timestamp,
                batteryPercent = e.batteryPercent,
                batteryTemperature = e.batteryTemperature,
                isCharging = e.isCharging,
                bleScanTimeMs = e.bleScanTimeMs,
                bleAdvertiseTimeMs = e.bleAdvertiseTimeMs,
                gattConnections = e.gattConnections,
                gattBytesTx = e.gattBytesTx,
                gattBytesRx = e.gattBytesRx,
                cpuWakeups = e.cpuWakeups,
                messagesForwarded = e.messagesForwarded,
                messagesDelivered = e.messagesDelivered,
                avgDeliveryLatencyMs = e.avgDeliveryLatencyMs,
                currentMode = try { PowerMode.valueOf(e.currentMode) } catch (_: Exception) { PowerMode.ECO },
                peerCount = e.peerCount
            )
        }
    }

    suspend fun exportCsv(): String {
        val report = getReport()
        val sb = StringBuilder()
        sb.appendLine("timestamp,datetime,battery_pct,temp_c,charging,scan_ms,adv_ms,gatt_conn,tx_bytes,rx_bytes,wakeups,forwarded,delivered,avg_latency_ms,mode,peers")
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        for (s in report) {
            sb.appendLine(
                "${s.timestamp},${dateFormat.format(Date(s.timestamp))},${s.batteryPercent}," +
                "${s.batteryTemperature},${s.isCharging},${s.bleScanTimeMs},${s.bleAdvertiseTimeMs}," +
                "${s.gattConnections},${s.gattBytesTx},${s.gattBytesRx},${s.cpuWakeups}," +
                "${s.messagesForwarded},${s.messagesDelivered},${s.avgDeliveryLatencyMs}," +
                "${s.currentMode.name},${s.peerCount}"
            )
        }
        return sb.toString()
    }
}
