package com.ghostprotocol.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

object HapticHelper {
    private const val TAG = "HapticHelper"

    /**
     * Dual-Pulse "Heartbeat" Haptic Verification:
     * Generates a visceral bump... thump-thump pattern that physically confirms
     * cryptographic mutual verification directly into the palm of the user's hand.
     */
    fun triggerHeartbeat(context: Context) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator ?: (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (vibrator == null || !vibrator.hasVibrator()) {
                Log.w(TAG, "No vibrator available on device")
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // bump (35ms, medium), pause (90ms), THUMP (75ms, max power)
                val timings = longArrayOf(0, 35, 90, 75)
                val amplitudes = intArrayOf(0, 160, 0, 255)
                val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 40, 90, 80), -1)
            }
            Log.d(TAG, ">>> Heartbeat haptic pulse triggered")
        } catch (e: Exception) {
            Log.e(TAG, "Error triggering heartbeat haptic: ${e.message}")
        }
    }
}
