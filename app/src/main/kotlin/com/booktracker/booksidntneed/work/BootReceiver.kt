package com.booktracker.booksidntneed.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.booktracker.booksidntneed.utils.AutoUpdatePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.d("BootReceiver", "Rescheduling daily update after event: $action")
                scope.launch {
                    val enabled = AutoUpdatePreferences.isEnabled(context).first()
                    if (enabled) {
                        val minutes = AutoUpdatePreferences.timeMinutes(context).first()
                        AutoUpdateScheduler.scheduleDaily(context, minutes, androidx.work.ExistingPeriodicWorkPolicy.REPLACE)
                    } else {
                        AutoUpdateScheduler.cancel(context)
                    }
                }
            }
        }
    }
}


