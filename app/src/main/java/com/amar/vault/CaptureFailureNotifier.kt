package com.amar.vault

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Best-effort, user-facing surfacing of a *hard* capture failure so that a save
 * never fails silently. Because Stage 1 shows an optimistic success animation and
 * finishes the activity before the DB transaction commits, a post-finish failure
 * would otherwise be invisible — this posts a lightweight notification instead.
 *
 * Fully guarded: any notification error (permission denied, no channel) is
 * swallowed. Reuses the app's existing notification conventions.
 */
object CaptureFailureNotifier {
    private const val CHANNEL_ID = "amar_share_failures"
    private const val CHANNEL_NAME = "Save failures"

    fun notifyFailure(context: Context, reason: String) {
        try {
            ensureChannel(context)
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Couldn't save to Amar Vault")
                .setContentText(reason)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$reason\nPlease try sharing again."))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()

            NotificationManagerCompat.from(context)
                .notify("share_failure".hashCode(), notification)
        } catch (e: Exception) {
            android.util.Log.w("CaptureFailureNotifier", "Could not post failure notification: ${e.message}")
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Shown when a shared item could not be saved." }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }
}
