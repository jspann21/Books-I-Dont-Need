package com.booktracker.booksidntneed.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.booktracker.booksidntneed.R
import com.booktracker.booksidntneed.ui.MainActivity
import com.booktracker.booksidntneed.utils.UpdateSummary

object AutoUpdateNotifier {
    // Public so worker can reference for ForegroundInfo
    const val CHANNEL_ID_PUBLIC = "price_updates"
    const val CHANNEL_ID_PROGRESS = "price_updates_progress"
    const val ACTION_SHOW_RECENT_CHANGES = "com.booktracker.booksidntneed.SHOW_RECENT_CHANGES"
    const val EXTRA_SHOW_RECENT_CHANGES = "show_recent_changes"
    private const val CHANNEL_ID = CHANNEL_ID_PUBLIC
    private const val CHANNEL_NAME = "Price Updates"
    private const val CHANNEL_DESC = "Notifications about automatic price updates"
    private const val CHANNEL_PROGRESS_NAME = "Price Update Progress"
    private const val CHANNEL_PROGRESS_DESC = "Silent progress notifications for automatic price updates"
    const val NOTIF_ID_FOREGROUND = 1000
    private const val NOTIF_ID_SUMMARY = 1001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // Main channel for summary notifications (default importance)
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
            channel.description = CHANNEL_DESC
            manager.createNotificationChannel(channel)
            
            // Progress channel for foreground service notifications (low importance, silent)
            val progressChannel = NotificationChannel(CHANNEL_ID_PROGRESS, CHANNEL_PROGRESS_NAME, NotificationManager.IMPORTANCE_LOW)
            progressChannel.description = CHANNEL_PROGRESS_DESC
            progressChannel.setSound(null, null)
            progressChannel.enableVibration(false)
            progressChannel.setShowBadge(false)
            manager.createNotificationChannel(progressChannel)
        }
    }

    fun showSummaryNotification(context: Context, summary: UpdateSummary) {
        ensureChannel(context)
        
        // Skip notification entirely if no changes and user preference is set to reduce noise
        // For now, always show the notification but make it silent if no changes
        val title = context.getString(R.string.update_complete_title)
        val dropsText = context.getString(R.string.summary_price_drops, summary.drops)
        val increasesSuffix = if (summary.increases > 0) {
            context.getString(R.string.update_increases_suffix, summary.increases)
        } else ""
        val content = if (summary.changed > 0) "$dropsText$increasesSuffix" else context.getString(R.string.summary_no_changes)

        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_SHOW_RECENT_CHANGES
            putExtra(EXTRA_SHOW_RECENT_CHANGES, true)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val piFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, piFlags)

        val bodyLines: String = if (summary.changed == 0) {
            content
        } else {
            val lines = summary.changes.take(5).map { c ->
                val delta = if (c.oldPrice != null && c.newPrice != null) {
                    val d = c.newPrice - c.oldPrice
                    val sign = if (d >= 0) "+" else "−"
                    "$sign$" + String.format("%.2f", kotlin.math.abs(d))
                } else ""
                "- ${c.bookTitle} - ${c.storeName} $delta"
            }
            val ellipsis = if (summary.changes.size > 5) "\n…" else ""
            (lines.joinToString("\n") + ellipsis)
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_book)
            .setLargeIcon(android.graphics.BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyLines))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            
        // Make notification silent if no changes to reduce noise
        if (summary.changed == 0) {
            builder.setSilent(true)
            builder.setPriority(NotificationCompat.PRIORITY_LOW)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        NotificationManagerCompat.from(context).notify(NOTIF_ID_SUMMARY, builder.build())
    }

    fun cancelProgressNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIF_ID_FOREGROUND)
    }
}

