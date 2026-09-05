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
    const val CHANNEL_DISCOVERY = "ghost_discovery_channel"
    const val CHANNEL_INTRODUCTIONS = "ghost_introductions_channel"

    const val ACTION_CYCLE_POSTURE = "ACTION_CYCLE_POSTURE"
    const val ACTION_CYCLE_MODE = "ACTION_CYCLE_MODE"
    const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"

    const val ACTION_INITIATE_DISCOVERY = "ACTION_INITIATE_DISCOVERY"
    const val ACTION_ACCEPT_DISCOVERY = "ACTION_ACCEPT_DISCOVERY"
    const val ACTION_DECLINE_DISCOVERY = "ACTION_DECLINE_DISCOVERY"
    const val EXTRA_MAC = "EXTRA_MAC"

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

    private fun ensureDiscoveryChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_DISCOVERY,
                "GHOST Discovery",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Nearby peer discovery alerts"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200, 100, 200)
                enableLights(true)
                setShowBadge(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun showDiscoveryNotification(context: Context, mac: String, fingerprintShort: String) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureDiscoveryChannel(manager)

            val connectIntent = Intent(context, GhostService::class.java).apply {
                action = ACTION_INITIATE_DISCOVERY
                putExtra(EXTRA_MAC, mac)
            }
            val connectPendingIntent = PendingIntent.getService(
                context,
                (mac.hashCode() and 0x7FFFFFFF),
                connectIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notificationId = 3000 + (mac.hashCode() and 0xFFFF)
            val notification = NotificationCompat.Builder(context, CHANNEL_DISCOVERY)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("GHOST User Nearby")
                .setContentText("Discovered peer #$fingerprintShort nearby. Tap to connect.")
                .setColor(0xFF9D4EDD.toInt())
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .addAction(android.R.drawable.ic_menu_send, "Connect", connectPendingIntent)
                .build()

            manager.notify(notificationId, notification)
            Log.d(TAG, "GHOST_DISCOVERY: Discovery notification posted for $mac (id=$notificationId)")
        } catch (e: Exception) {
            Log.e(TAG, "GHOST_DISCOVERY: Error showing discovery notification: ${e.message}", e)
        }
    }

    fun showIncomingDiscoveryNotification(context: Context, mac: String, name: String, handle: String) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureDiscoveryChannel(manager)

            val acceptIntent = Intent(context, GhostService::class.java).apply {
                action = ACTION_ACCEPT_DISCOVERY
                putExtra(EXTRA_MAC, mac)
            }
            val acceptPendingIntent = PendingIntent.getService(
                context,
                ((mac.hashCode() * 31 + 1) and 0x7FFFFFFF),
                acceptIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val declineIntent = Intent(context, GhostService::class.java).apply {
                action = ACTION_DECLINE_DISCOVERY
                putExtra(EXTRA_MAC, mac)
            }
            val declinePendingIntent = PendingIntent.getService(
                context,
                ((mac.hashCode() * 31 + 2) and 0x7FFFFFFF),
                declineIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notificationId = 4000 + (mac.hashCode() and 0xFFFF)
            val notification = NotificationCompat.Builder(context, CHANNEL_DISCOVERY)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Incoming Contact Request")
                .setContentText("User '$name' (#$handle) wants to connect.")
                .setColor(0xFF9D4EDD.toInt())
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .addAction(android.R.drawable.ic_input_add, "Accept", acceptPendingIntent)
                .addAction(android.R.drawable.ic_delete, "Decline", declinePendingIntent)
                .build()

            manager.notify(notificationId, notification)
            Log.d(TAG, "GHOST_DISCOVERY: Incoming request notification posted for $mac from $name (id=$notificationId)")
        } catch (e: Exception) {
            Log.e(TAG, "GHOST_DISCOVERY: Error showing incoming discovery notification: ${e.message}", e)
        }
    }

    fun cancelDiscoveryNotification(context: Context, mac: String) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(3000 + (mac.hashCode() and 0xFFFF))
            manager.cancel(4000 + (mac.hashCode() and 0xFFFF))
            manager.cancel(5000 + (mac.hashCode() and 0xFFFF))
        } catch (e: Exception) {
            Log.e(TAG, "GHOST_DISCOVERY: Error cancelling discovery notification: ${e.message}", e)
        }
    }

    fun showShortCodeFoundNotification(context: Context, name: String, mac: String) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureDiscoveryChannel(manager)

            val addIntent = Intent(context, GhostService::class.java).apply {
                action = ACTION_ACCEPT_DISCOVERY
                putExtra(EXTRA_MAC, mac)
            }
            val addPendingIntent = PendingIntent.getService(
                context,
                ((mac.hashCode() * 31 + 3) and 0x7FFFFFFF),
                addIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notificationId = 5000 + (mac.hashCode() and 0xFFFF)
            val notification = NotificationCompat.Builder(context, CHANNEL_DISCOVERY)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Short code match found!")
                .setContentText("User '$name' responded to your code search.")
                .setColor(0xFF9D4EDD.toInt())
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .addAction(android.R.drawable.ic_input_add, "Add Contact", addPendingIntent)
                .build()

            manager.notify(notificationId, notification)
            Log.d(TAG, "GHOST_SHORTCODE: Match notification posted for $mac from $name (id=$notificationId)")
        } catch (e: Exception) {
            Log.e(TAG, "GHOST_SHORTCODE: Error showing short code match notification: ${e.message}", e)
        }
    }

    const val CHANNEL_GROUP_MESSAGES = "ghost_group_messages_channel"

    private fun ensureGroupChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (manager.getNotificationChannel(CHANNEL_GROUP_MESSAGES) == null) {
                val channel = NotificationChannel(
                    CHANNEL_GROUP_MESSAGES,
                    "Cell Group Messages",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifications for incoming group messages"
                    enableVibration(true)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    fun showGroupMessageNotification(
        context: Context,
        groupName: String,
        senderName: String,
        preview: String,
        groupId: String,
        unreadCount: Int = 1
    ) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureGroupChannel(manager)

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("OPEN_GROUP_ID", groupId)
            }
            val openPendingIntent = PendingIntent.getActivity(
                context,
                ((groupId.hashCode() * 31 + 7) and 0x7FFFFFFF),
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val title = if (unreadCount > 1) {
                "$groupName ($unreadCount new messages)"
            } else {
                groupName
            }

            val notificationId = 6000 + (groupId.hashCode() and 0xFFFF)
            val notification = NotificationCompat.Builder(context, CHANNEL_GROUP_MESSAGES)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText("$senderName: $preview")
                .setColor(0xFF9D4EDD.toInt())
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(openPendingIntent)
                .build()

            manager.notify(notificationId, notification)
            Log.d(TAG, "GHOST_GROUP: Group notification posted for group $groupName (id=$notificationId)")
        } catch (e: Exception) {
            Log.e(TAG, "GHOST_GROUP: Error showing group notification: ${e.message}", e)
        }
    }

    fun showGroupInviteNotification(
        context: Context,
        groupName: String,
        creatorName: String,
        groupId: String
    ) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureGroupChannel(manager)

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("OPEN_GROUP_ID", groupId)
            }
            val openPendingIntent = PendingIntent.getActivity(
                context,
                ((groupId.hashCode() * 31 + 11) and 0x7FFFFFFF),
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notificationId = 6500 + (groupId.hashCode() and 0xFFFF)
            val notification = NotificationCompat.Builder(context, CHANNEL_GROUP_MESSAGES)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("New Cell Group: $groupName")
                .setContentText("$creatorName added you to this encrypted cell")
                .setColor(0xFF9D4EDD.toInt())
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(openPendingIntent)
                .build()

            manager.notify(notificationId, notification)
            Log.d(TAG, "GHOST_GROUP: Group invite notification posted for $groupName (id=$notificationId)")
        } catch (e: Exception) {
            Log.e(TAG, "GHOST_GROUP: Error showing group invite notification: ${e.message}", e)
        }
    }

    private fun ensureIntroductionsChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (manager.getNotificationChannel(CHANNEL_INTRODUCTIONS) == null) {
                val channel = NotificationChannel(
                    CHANNEL_INTRODUCTIONS,
                    "Contact Introductions",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Cryptographic contact introductions from verified peers"
                    enableVibration(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    fun showIntroductionNotification(
        context: Context,
        voucherName: String,
        introducedName: String,
        introducedContactId: String
    ) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureIntroductionsChannel(manager)

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                action = "review_introduction"
                putExtra("action", "review_introduction")
                putExtra("introduced_contact_id", introducedContactId)
            }
            val openPendingIntent = PendingIntent.getActivity(
                context,
                ((introducedContactId.hashCode() * 37 + 13) and 0x7FFFFFFF),
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val shortHandle = introducedContactId.take(6)
            val notificationId = 7000 + (introducedContactId.hashCode() and 0xFFFF)
            val notification = NotificationCompat.Builder(context, CHANNEL_INTRODUCTIONS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("$voucherName vouches for $introducedName (#$shortHandle)")
                .setContentText("Tap to review this cryptographic introduction")
                .setColor(0xFF9D4EDD.toInt())
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(openPendingIntent)
                .build()

            manager.notify(notificationId, notification)
            Log.d(TAG, "GHOST_INTRO: Introduction notification posted for $introducedName (id=$notificationId)")
        } catch (e: Exception) {
            Log.e(TAG, "GHOST_INTRO: Error showing introduction notification: ${e.message}", e)
        }
    }
}
