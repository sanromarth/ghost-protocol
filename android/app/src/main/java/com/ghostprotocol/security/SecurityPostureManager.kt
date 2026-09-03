package com.ghostprotocol.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Security Posture Engine:
 * 3-state configuration layer that governs BLE discovery aggressiveness,
 * notification policy, relay willingness, and wakelock behavior.
 */
enum class SecurityPosture {
    STEALTH,    // Default. QR-only, passive BLE, no unknown-peer notifications, short codes disabled.
    PROTEST,    // Aggressive discovery. 300ms scan window / 600ms interval, unknown peer notifications ON, short codes active, emergency channel unmuted, full relay willingness.
    EMERGENCY   // Maximum radio duty cycle, auto-forward broadcast alerts (placeholder for v0.4), zero screen-lock timeout suggestion, relay willingness 1.0.
}

class SecurityPostureManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _postureFlow = MutableStateFlow(loadPersistedPosture())
    val postureFlow: StateFlow<SecurityPosture> = _postureFlow.asStateFlow()

    private fun loadPersistedPosture(): SecurityPosture {
        val name = prefs.getString(KEY_POSTURE, SecurityPosture.STEALTH.name) ?: SecurityPosture.STEALTH.name
        return try {
            SecurityPosture.valueOf(name)
        } catch (_: Exception) {
            SecurityPosture.STEALTH
        }
    }

    fun getPosture(): SecurityPosture = _postureFlow.value

    fun setPosture(posture: SecurityPosture) {
        prefs.edit().putString(KEY_POSTURE, posture.name).apply()
        _postureFlow.value = posture
        Log.d(TAG, ">>> Security posture updated: ${posture.name}")
    }

    fun cyclePosture(): SecurityPosture {
        val next = when (getPosture()) {
            SecurityPosture.STEALTH -> SecurityPosture.PROTEST
            SecurityPosture.PROTEST -> SecurityPosture.EMERGENCY
            SecurityPosture.EMERGENCY -> SecurityPosture.STEALTH
        }
        setPosture(next)
        return next
    }

    /**
     * Non-negotiable auto-revert guard:
     * If battery level drops below 20% and posture is not STEALTH,
     * force posture back to STEALTH to prevent battery exhaustion.
     */
    fun checkBatteryRevert(batteryPercent: Int): Boolean {
        if (batteryPercent in 0..19 && getPosture() != SecurityPosture.STEALTH) {
            Log.w(TAG, ">>> Battery critically low ($batteryPercent% < 20%): Auto-reverting posture ${getPosture().name} -> STEALTH")
            setPosture(SecurityPosture.STEALTH)
            return true
        }
        return false
    }

    fun postureColor(): Color = postureColor(getPosture())
    fun postureLabel(): String = postureLabel(getPosture())

    companion object {
        private const val TAG = "SecurityPosture"
        private const val PREFS_NAME = "ghost_secure_prefs"
        private const val KEY_POSTURE = "security_posture"

        // Cyberpunk colors: STEALTH = gray #6B7280, PROTEST = amber #FFB703, EMERGENCY = red #EF4444
        val ColorStealth = Color(0xFF6B7280)
        val ColorProtest = Color(0xFFFFB703)
        val ColorEmergency = Color(0xFFEF4444)

        fun postureColor(posture: SecurityPosture): Color = when (posture) {
            SecurityPosture.STEALTH -> ColorStealth
            SecurityPosture.PROTEST -> ColorProtest
            SecurityPosture.EMERGENCY -> ColorEmergency
        }

        fun postureLabel(posture: SecurityPosture): String = when (posture) {
            SecurityPosture.STEALTH -> "STEALTH"
            SecurityPosture.PROTEST -> "PROTEST"
            SecurityPosture.EMERGENCY -> "EMERGENCY"
        }

        @Volatile
        private var instance: SecurityPostureManager? = null

        fun getInstance(context: Context): SecurityPostureManager {
            return instance ?: synchronized(this) {
                instance ?: SecurityPostureManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
