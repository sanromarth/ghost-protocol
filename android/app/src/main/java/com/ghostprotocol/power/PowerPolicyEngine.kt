package com.ghostprotocol.power

import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import com.ghostprotocol.security.SecurityPosture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PowerMode {
    ACTIVE,      // Full scan/advertise, normal relay — in crowd, charging
    ECO,         // Reduced scan, normal relay — default walking around
    CRITICAL,    // Minimal scan, own messages only — battery < 20%
    DEEP_SLEEP   // Very occasional discovery — hiding, overnight
}

data class PowerPolicy(
    val scanIntervalMs: Long,        // Time between scan bursts
    val scanWindowMs: Long,          // Duration of each scan burst
    val advertiseIntervalMs: Long,   // BLE advertising interval
    val txPowerLevel: Int,           // AdvertiseSettings tx power
    val relayWillingness: Float,     // 0.0..1.0 — forwarded to Go router
    val maxBatchSize: Int,           // Max messages per GATT batch
    val wakeLockRequired: Boolean,   // Whether to hold PARTIAL_WAKE_LOCK
    val mode: PowerMode,
    val securityPosture: SecurityPosture = SecurityPosture.STEALTH
)

data class PosturePolicy(
    val powerPolicy: PowerPolicy,
    val securityPosture: SecurityPosture
)

class PowerPolicyEngine(private val context: Context) {

    private val _currentPolicy = MutableStateFlow(DEFAULT_ECO_POLICY)
    val currentPolicy: StateFlow<PowerPolicy> = _currentPolicy.asStateFlow()

    // Manual override state
    private var overrideMode: PowerMode? = null
    private var overrideExpiresAt: Long = 0L

    /**
     * Force a specific power mode for the given duration.
     * After the duration expires, reverts to automatic policy.
     */
    fun forceMode(mode: PowerMode, durationMs: Long = 3_600_000L) {
        overrideMode = mode
        overrideExpiresAt = System.currentTimeMillis() + durationMs
    }

    /**
     * Cancel any manual override and return to automatic policy.
     */
    fun clearOverride() {
        overrideMode = null
        overrideExpiresAt = 0L
    }

    /**
     * Returns the override mode if active, null otherwise.
     */
    fun getOverrideMode(): PowerMode? {
        if (overrideMode != null && System.currentTimeMillis() >= overrideExpiresAt) {
            overrideMode = null
            overrideExpiresAt = 0L
        }
        return overrideMode
    }

    /**
     * Returns remaining override time in milliseconds, or 0 if no override.
     */
    fun getOverrideRemainingMs(): Long {
        if (overrideMode == null) return 0L
        val remaining = overrideExpiresAt - System.currentTimeMillis()
        if (remaining <= 0) {
            overrideMode = null
            overrideExpiresAt = 0L
            return 0L
        }
        return remaining
    }

    /**
     * Collect all inputs and compute the current policy.
     * Call this every 30 seconds from GhostService.
     */
    fun updateInputs(
        batteryPercent: Int,
        isCharging: Boolean,
        screenOn: Boolean,
        peerCount: Int,
        queueSize: Int,
        timeSinceLastEncounterMs: Long,
        isMoving: Boolean?,
        securityPosture: SecurityPosture = SecurityPosture.STEALTH
    ): PowerPolicy {
        val policy = when (securityPosture) {
            SecurityPosture.PROTEST -> PowerPolicy(
                scanIntervalMs = 600L,
                scanWindowMs = 300L,
                advertiseIntervalMs = 100L,
                txPowerLevel = AdvertiseSettings.ADVERTISE_TX_POWER_HIGH,
                relayWillingness = 1.0f,
                maxBatchSize = 10,
                wakeLockRequired = true,
                mode = PowerMode.ACTIVE,
                securityPosture = SecurityPosture.PROTEST
            )
            SecurityPosture.EMERGENCY -> PowerPolicy(
                scanIntervalMs = 300L,
                scanWindowMs = 300L,
                advertiseIntervalMs = 100L,
                txPowerLevel = AdvertiseSettings.ADVERTISE_TX_POWER_HIGH,
                relayWillingness = 1.0f,
                maxBatchSize = 10,
                wakeLockRequired = true,
                mode = PowerMode.ACTIVE,
                securityPosture = SecurityPosture.EMERGENCY
            )
            SecurityPosture.STEALTH -> {
                val override = getOverrideMode()
                if (override != null) {
                    policyForMode(override, batteryPercent)
                } else {
                    computePolicy(
                        batteryPercent, isCharging, screenOn,
                        peerCount, queueSize, timeSinceLastEncounterMs, isMoving
                    )
                }.copy(securityPosture = SecurityPosture.STEALTH)
            }
        }
        _currentPolicy.value = policy
        return policy
    }

    /**
     * Evaluate inputs and return both PowerPolicy and SecurityPosture via PosturePolicy.
     */
    fun evaluate(
        batteryPercent: Int,
        isCharging: Boolean,
        screenOn: Boolean,
        peerCount: Int,
        queueSize: Int,
        timeSinceLastEncounterMs: Long,
        isMoving: Boolean?,
        securityPosture: SecurityPosture = SecurityPosture.STEALTH
    ): PosturePolicy {
        val policy = updateInputs(
            batteryPercent, isCharging, screenOn,
            peerCount, queueSize, timeSinceLastEncounterMs, isMoving, securityPosture
        )
        return PosturePolicy(policy, securityPosture)
    }

    private fun computePolicy(
        batteryPercent: Int,
        isCharging: Boolean,
        screenOn: Boolean,
        peerCount: Int,
        queueSize: Int,
        timeSinceLastEncounterMs: Long,
        isMoving: Boolean?
    ): PowerPolicy {
        return when {
            // DEEP_SLEEP: no motion, no recent peers, screen off, not charging
            batteryPercent > 20
                && !isCharging
                && !screenOn
                && peerCount == 0
                && timeSinceLastEncounterMs > 30 * 60 * 1000L
                && isMoving == false ->
                PowerPolicy(
                    scanIntervalMs = 300_000L,
                    scanWindowMs = 500L,
                    advertiseIntervalMs = 2000L,
                    txPowerLevel = AdvertiseSettings.ADVERTISE_TX_POWER_LOW,
                    relayWillingness = 0.0f,
                    maxBatchSize = 1,
                    wakeLockRequired = false,
                    mode = PowerMode.DEEP_SLEEP
                )

            // CRITICAL: battery dying
            batteryPercent < 20 && !isCharging ->
                PowerPolicy(
                    scanIntervalMs = 60_000L,
                    scanWindowMs = 200L,
                    advertiseIntervalMs = 1000L,
                    txPowerLevel = AdvertiseSettings.ADVERTISE_TX_POWER_LOW,
                    relayWillingness = 0.0f,
                    maxBatchSize = 1,
                    wakeLockRequired = false,
                    mode = PowerMode.CRITICAL
                )

            // ACTIVE: charging OR in dense crowd with recent traffic
            isCharging || (peerCount > 10 && queueSize > 0) ->
                PowerPolicy(
                    scanIntervalMs = 500L,
                    scanWindowMs = 100L,
                    advertiseIntervalMs = 100L,
                    txPowerLevel = AdvertiseSettings.ADVERTISE_TX_POWER_HIGH,
                    relayWillingness = 1.0f,
                    maxBatchSize = 10,
                    wakeLockRequired = true,
                    mode = PowerMode.ACTIVE
                )

            // ECO: default
            else ->
                PowerPolicy(
                    scanIntervalMs = 2000L,
                    scanWindowMs = 100L,
                    advertiseIntervalMs = 500L,
                    txPowerLevel = AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM,
                    relayWillingness = when {
                        batteryPercent > 60 -> 1.0f
                        batteryPercent > 30 -> 0.6f
                        else -> 0.3f
                    },
                    maxBatchSize = 5,
                    wakeLockRequired = true,
                    mode = PowerMode.ECO
                )
        }
    }

    /**
     * Generate a policy for a manually selected mode.
     */
    private fun policyForMode(mode: PowerMode, batteryPercent: Int): PowerPolicy {
        return when (mode) {
            PowerMode.DEEP_SLEEP -> PowerPolicy(
                scanIntervalMs = 300_000L, scanWindowMs = 500L,
                advertiseIntervalMs = 2000L,
                txPowerLevel = AdvertiseSettings.ADVERTISE_TX_POWER_LOW,
                relayWillingness = 0.0f, maxBatchSize = 1,
                wakeLockRequired = false, mode = PowerMode.DEEP_SLEEP
            )
            PowerMode.CRITICAL -> PowerPolicy(
                scanIntervalMs = 60_000L, scanWindowMs = 200L,
                advertiseIntervalMs = 1000L,
                txPowerLevel = AdvertiseSettings.ADVERTISE_TX_POWER_LOW,
                relayWillingness = 0.0f, maxBatchSize = 1,
                wakeLockRequired = false, mode = PowerMode.CRITICAL
            )
            PowerMode.ACTIVE -> PowerPolicy(
                scanIntervalMs = 500L, scanWindowMs = 100L,
                advertiseIntervalMs = 100L,
                txPowerLevel = AdvertiseSettings.ADVERTISE_TX_POWER_HIGH,
                relayWillingness = 1.0f, maxBatchSize = 10,
                wakeLockRequired = true, mode = PowerMode.ACTIVE
            )
            PowerMode.ECO -> PowerPolicy(
                scanIntervalMs = 2000L, scanWindowMs = 100L,
                advertiseIntervalMs = 500L,
                txPowerLevel = AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM,
                relayWillingness = when {
                    batteryPercent > 60 -> 1.0f
                    batteryPercent > 30 -> 0.6f
                    else -> 0.3f
                },
                maxBatchSize = 5, wakeLockRequired = true, mode = PowerMode.ECO
            )
        }
    }

    companion object {
        val DEFAULT_ECO_POLICY = PowerPolicy(
            scanIntervalMs = 2000L,
            scanWindowMs = 100L,
            advertiseIntervalMs = 500L,
            txPowerLevel = AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM,
            relayWillingness = 1.0f,
            maxBatchSize = 5,
            wakeLockRequired = true,
            mode = PowerMode.ECO
        )
    }
}
