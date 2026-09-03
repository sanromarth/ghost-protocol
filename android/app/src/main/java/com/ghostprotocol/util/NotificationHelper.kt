package com.ghostprotocol.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.ghostprotocol.GhostService
import com.ghostprotocol.MainActivity
import com.ghostprotocol.R
import com.ghostprotocol.power.PowerMode
import com.ghostprotocol.security.SecurityPosture

object NotificationHelper {
    private const val TAG = "NotificationHelper"
    private const val CHANNEL_VERIFY = "ghost_verify_channel_v2"
    const val CHANNEL_SERVICE = "ghost_mesh_channel"

    const val ACTION_CYCLE_POSTURE = "ACTION_CYCLE_POSTURE"
    const val ACTION_CYCLE_MODE = "ACTION_CYCLE_MODE"
    const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"

    fun buildServiceNotification(
        context: Context,
        mode: PowerMode,
        posture: SecurityPosture,
        peerCount: Int
    ): android.app.Notification {
        val postureIntent = Intent(context, GhostService::class.java).apply {
            action = ACTION_CYCLE_POSTURE
        }
        val posturePendingIntent = PendingIntent.getService(
            context, 2, postureIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val modeIntent = Intent(context, GhostService::class.java).apply {
            action = ACTION_CYCLE_MODE
        }
        val modePendingIntent = PendingIntent.getService(
            context, 1, modeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val quitIntent = Intent(context, GhostService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val quitPendingIntent = PendingIntent.getService(
            context, 0, quitIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val colorInt = when (posture) {
            SecurityPosture.STEALTH -> 0xFF6B7280.toInt()
            SecurityPosture.PROTEST -> 0xFFFFB703.toInt()
            SecurityPosture.EMERGENCY -> 0xFFEF4444.toInt()
        }

        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setContentTitle("GHOST Mesh Active")
            .setContentText("Posture: ${posture.name} | Mode: ${mode.name} | $peerCount peers")
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(colorInt)
            .setColorized(posture != SecurityPosture.STEALTH)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_compass, "Posture: ${posture.name}", posturePendingIntent)
            .addAction(android.R.drawable.ic_menu_preferences, "Mode: ${mode.name}", modePendingIntent)
            .addAction(android.R.drawable.ic_delete, "Quit GHOST", quitPendingIntent)
            .build()
    }

    fun showMutualVerificationNotification(context: Context, contactName: String) {
        try {
            Log.d(TAG, ">>> Triggering mutual verification notification for '$contactName'")
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val defaultSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_VERIFY,
                    "Mutual Verification",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifications for identity and mutual QR verification"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 300, 200, 300)
                    enableLights(true)
                    setShowBadge(true)
                }
                manager.createNotificationChannel(channel)
            }

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                contactName.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_VERIFY)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Mutual verification")
                .setContentText("You and $contactName verified each other")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setSound(defaultSound)
                .setVibrate(longArrayOf(0, 300, 200, 300))
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            manager.notify(contactName.hashCode() + 2000, notification)
            Log.d(TAG, ">>> Notification successfully posted to NotificationManager for '$contactName'")

            // Physical Dual-Pulse Heartbeat Haptic Confirmation
            HapticHelper.triggerHeartbeat(context)

            // In-app visual toast fallback to guarantee the user sees it even if notifications are silenced
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    context.applicationContext,
                    "Mutual verification: You and $contactName verified each other",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, ">>> Error posting mutual verification notification: ${e.message}", e)
        }
    }
}
