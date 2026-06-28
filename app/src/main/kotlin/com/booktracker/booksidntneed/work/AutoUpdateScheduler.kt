package com.booktracker.booksidntneed.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit
import androidx.work.NetworkType

object AutoUpdateScheduler {
    private const val UNIQUE_WORK_NAME = "daily_price_update"
    private const val UNIQUE_ONE_TIME_NAME = "daily_price_update_one_time"
    const val UNIQUE_MANUAL_WORK_NAME = "manual_price_update"
    private const val IMMEDIATE_THRESHOLD_MINUTES = 20L

    /**
     * Schedule a daily worker at the specified local time (minutes since midnight).
     */
    fun scheduleDaily(
        context: Context,
        minutesSinceMidnight: Int,
        policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP
    ) {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minutesSinceMidnight / 60)
            set(Calendar.MINUTE, minutesSinceMidnight % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.before(now)) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        val initialDelayMs = target.timeInMillis - now.timeInMillis

        // Debug logs to verify scheduling
        Log.d(
            "AutoUpdateScheduler",
            "Scheduling daily update: minutes=$minutesSinceMidnight (HH=${minutesSinceMidnight/60}, mm=${minutesSinceMidnight%60}), now=${now.time}, nextRun=${target.time}, initialDelayMs=$initialDelayMs"
        )

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val wm = WorkManager.getInstance(context)

        // If the next run is soon (e.g., within 20 minutes), enqueue a one-time request for today
        // and start the periodic schedule from tomorrow to avoid double-runs.
        val thresholdMs = TimeUnit.MINUTES.toMillis(IMMEDIATE_THRESHOLD_MINUTES)
        val enqueueImmediate = initialDelayMs in 1..thresholdMs
        if (enqueueImmediate) {
            Log.d("AutoUpdateScheduler", "Enqueuing one-time update in ${initialDelayMs}ms for today's run")
            val oneTime = OneTimeWorkRequestBuilder<AutoUpdateWorker>()
                .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .build()
            wm.enqueueUniqueWork(
                UNIQUE_ONE_TIME_NAME,
                ExistingWorkPolicy.REPLACE,
                oneTime
            )
        }

        // Use a small flex so execution happens close to the chosen clock time
        val flexMinutes = 15L
        val periodicInitialDelayMs = if (enqueueImmediate) initialDelayMs + TimeUnit.DAYS.toMillis(1) else initialDelayMs
        val request = PeriodicWorkRequestBuilder<AutoUpdateWorker>(24, TimeUnit.HOURS, flexMinutes, TimeUnit.MINUTES)
            .setInitialDelay(periodicInitialDelayMs, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .build()

        Log.d(
            "AutoUpdateScheduler",
            "Enqueuing periodic update with initialDelayMs=$periodicInitialDelayMs, flexMinutes=$flexMinutes, policy=$policy"
        )
        wm.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            policy,
            request
        )
    }

    fun cancel(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(UNIQUE_WORK_NAME)
        wm.cancelUniqueWork(UNIQUE_ONE_TIME_NAME)
    }

    fun cancelManual(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(UNIQUE_MANUAL_WORK_NAME)
    }
}


