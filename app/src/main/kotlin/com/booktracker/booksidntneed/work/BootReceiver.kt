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
                val pendingResult = goAsync()
                val appContext = context.applicationContext
                scope.launch {
                    try {
                        val enabled = AutoUpdatePreferences.isEnabled(appContext).first()
                        if (enabled) {
                            val minutes = AutoUpdatePreferences.timeMinutes(appContext).first()
                            AutoUpdateScheduler.scheduleDaily(appContext, minutes, androidx.work.ExistingPeriodicWorkPolicy.REPLACE)
                        } else {
                            AutoUpdateScheduler.cancel(appContext)
                        }
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}


