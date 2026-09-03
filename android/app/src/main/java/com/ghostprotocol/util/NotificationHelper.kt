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
import com.ghostprotocol.MainActivity
import com.ghostprotocol.R

object NotificationHelper {
    private const val TAG = "NotificationHelper"
    private const val CHANNEL_VERIFY = "ghost_verify_channel_v2"

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
