package com.ghostprotocol.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.ghostprotocol.MainActivity
import com.ghostprotocol.R

object NotificationHelper {
    private const val CHANNEL_VERIFY = "ghost_verify_channel"

    fun showMutualVerificationNotification(context: Context, contactName: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_VERIFY,
                "Mutual Verification",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for identity and mutual QR verification"
                enableVibration(true)
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
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        manager.notify(contactName.hashCode() + 2000, notification)
    }
}
