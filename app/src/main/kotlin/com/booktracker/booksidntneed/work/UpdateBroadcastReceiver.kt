package com.booktracker.booksidntneed.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Lightweight receiver to nudge the foreground activity to present the recent changes dialog
 * immediately after a background update completes.
 */
class UpdateBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AutoUpdateWorker.ACTION_SUMMARY_READY) return
        // Avoid custom scope in a BroadcastReceiver to prevent leaks
        // We intentionally do not launch coroutines here; MainActivity handles display via its own receiver
    }
}


