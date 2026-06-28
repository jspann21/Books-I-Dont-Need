package com.booktracker.booksidntneed.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.booktracker.booksidntneed.ui.dialog.RecentPriceChangesDialogFragment
import com.booktracker.booksidntneed.utils.AutoUpdatePreferences
import kotlinx.coroutines.flow.first

/**
 * Lightweight receiver to nudge the foreground activity to present the recent changes dialog
 * immediately after a background update completes.
 */
class UpdateBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AutoUpdateWorker.ACTION_SUMMARY_READY) return
        // Best-effort: if MainActivity is in foreground, show dialog
        ContextCompat.getMainExecutor(context)
        // Avoid custom scope in a BroadcastReceiver to prevent leaks
        // We intentionally do not launch coroutines here; MainActivity handles display via its own receiver
    }
}


