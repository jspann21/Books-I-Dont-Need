package com.booktracker.booksidntneed.work

import android.Manifest
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.booktracker.booksidntneed.BookTrackerApplication
import com.booktracker.booksidntneed.database.AutoUpdateStoreTarget
import com.booktracker.booksidntneed.repository.BookRepository
import com.booktracker.booksidntneed.utils.AutoUpdatePreferences
import com.booktracker.booksidntneed.utils.ErrorReporter
import com.booktracker.booksidntneed.utils.FailedUpdateEntry
import com.booktracker.booksidntneed.utils.PriceChangeEntry
import com.booktracker.booksidntneed.utils.UpdateSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class AutoUpdateWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    private val repository: BookRepository by lazy {
        (applicationContext as BookTrackerApplication).repository
    }

    private val notificationManager = NotificationManagerCompat.from(applicationContext)
    private var notificationPermissionLogged = false

    private fun canPostNotifications(): Boolean {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            true
        } else {
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun buildProgressNotification(progressText: String, currentProgress: Int = 0, totalProgress: Int = 0): Notification {
        AutoUpdateNotifier.ensureChannel(applicationContext)
        return NotificationCompat.Builder(applicationContext, AutoUpdateNotifier.CHANNEL_ID_PROGRESS)
            .setSmallIcon(com.booktracker.booksidntneed.R.drawable.ic_notification_book)
            .setContentTitle("Price update in progress")
            .setContentText(progressText)
            .setProgress(totalProgress, currentProgress, totalProgress == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createForegroundInfo(progressText: String, currentProgress: Int = 0, totalProgress: Int = 0): ForegroundInfo {
        val notification = buildProgressNotification(progressText, currentProgress, totalProgress)
        // Pass the service type on Android 14+, else use the 2-arg constructor
        return if (Build.VERSION.SDK_INT >= 34) {
            ForegroundInfo(
                AutoUpdateNotifier.NOTIF_ID_FOREGROUND,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(AutoUpdateNotifier.NOTIF_ID_FOREGROUND, notification)
        }
    }

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "AutoUpdateWorker: Starting background update")
            val updateTargets = repository.getAutoUpdateStoreTargets()
            val totalStores = updateTargets.size
            val storesProcessed = AtomicInteger(0)
            val totalChecked = AtomicInteger(0)
            val changes = AtomicInteger(0)
            val drops = AtomicInteger(0)
            val increases = AtomicInteger(0)
            val failedUpdates = AtomicInteger(0)
            val skippedUpdates = AtomicInteger(0)
            val changeEntries = Collections.synchronizedList(mutableListOf<PriceChangeEntry>())
            val failureEntries = Collections.synchronizedList(mutableListOf<FailedUpdateEntry>())
            val requestLimiter = ResponsiblePriceUpdateLimiter()

            val initialProgressText = "Starting price updates... (0 of $totalStores)"
            // A full-library update can legitimately take longer than WorkManager's
            // normal background execution window. Promote scheduled and manual runs
            // alike so Android does not stop a daily update partway through.
            setForegroundIfAllowed(createForegroundInfo(initialProgressText, totalProgress = totalStores))
            setProgressData(0, totalStores, initialProgressText)

            suspend fun processTarget(target: AutoUpdateStoreTarget) {
                val store = target.store
                val bookTitle = target.bookTitle
                if (isStopped) {
                    Log.d(TAG, "AutoUpdateWorker: Skipping remaining work because worker stopped")
                    return
                }
                totalChecked.incrementAndGet()

                try {
                    val before = store.price
                    val updateResult = requestLimiter.run(store.storeUrl) {
                        repository.updateSingleStorePrices(store)
                    }

                    when (updateResult) {
                        is BookRepository.SingleStoreUpdateResult.Success -> {
                            val after = updateResult.newPrice
                            if (before != after) {
                                changes.incrementAndGet()
                                if (before != null && after != null) {
                                    if (after < before) drops.incrementAndGet() else increases.incrementAndGet()
                                }
                                changeEntries.add(
                                    PriceChangeEntry(
                                        bookId = store.bookId,
                                        bookTitle = bookTitle,
                                        storeName = store.storeName,
                                        oldPrice = before,
                                        newPrice = after,
                                        timestamp = System.currentTimeMillis()
                                    )
                                )
                            }
                        }
                        is BookRepository.SingleStoreUpdateResult.Failed -> {
                            failedUpdates.incrementAndGet()
                            failureEntries.add(
                                FailedUpdateEntry(
                                    bookId = store.bookId,
                                    bookTitle = bookTitle,
                                    storeName = store.storeName,
                                    errorMessage = updateResult.errorMessage
                                )
                            )
                            Log.w(
                                TAG,
                                "Price update failed for '$bookTitle' (${store.storeName}): ${updateResult.errorMessage}"
                            )
                        }
                        is BookRepository.SingleStoreUpdateResult.Skipped -> {
                            skippedUpdates.incrementAndGet()
                            Log.d(
                                TAG,
                                "Price update skipped for '$bookTitle' (${store.storeName}): ${updateResult.reason}"
                            )
                        }
                    }
                } catch (e: CancellationException) {
                    // WorkManager uses coroutine cancellation when a request is
                    // replaced or stopped. Never turn that into a store failure.
                    throw e
                } catch (e: Exception) {
                    failedUpdates.incrementAndGet()
                    failureEntries.add(
                        FailedUpdateEntry(
                            bookId = store.bookId,
                            bookTitle = bookTitle,
                            storeName = store.storeName,
                            errorMessage = e.message ?: "Unknown error occurred"
                        )
                    )
                    Log.e(TAG, "Failed to update store for book '$bookTitle' (${store.storeName}): ${e.message}")
                    ErrorReporter.recordException(
                        e,
                        "Background price update failed for one store",
                        mapOf(
                            "source" to "auto_update_store",
                            "store_host" to hostFrom(store.storeUrl)
                        )
                    )
                } finally {
                    val processed = storesProcessed.incrementAndGet()
                    updateProgress(processed, totalStores, bookTitle)
                }
            }

            coroutineScope {
                val nextTargetIndex = AtomicInteger(0)
                val workerCount = minOf(MAX_BACKGROUND_UPDATE_WORKERS, totalStores)
                List(workerCount) {
                    async {
                        while (!isStopped) {
                            val targetIndex = nextTargetIndex.getAndIncrement()
                            if (targetIndex >= updateTargets.size) {
                                break
                            }
                            processTarget(updateTargets[targetIndex])
                        }
                    }
                }.awaitAll()
            }

            val summary = UpdateSummary(
                totalChecked = totalChecked.get(),
                changed = changes.get(),
                drops = drops.get(),
                increases = increases.get(),
                failed = failedUpdates.get(),
                skipped = skippedUpdates.get(),
                changes = changeEntries,
                failures = failureEntries,
                completedAt = System.currentTimeMillis()
            )

            // Persist recent changes for in-app display
            AutoUpdatePreferences.setRecentChangesJson(applicationContext, summaryToJson(summary))

            // Notify done (notification will be posted by a helper from a broadcast/worker or by MainActivity on next open)
            AutoUpdateNotifier.showSummaryNotification(applicationContext, summary)
            // Also broadcast in-app event so a foreground UI can show immediately
            val intent = android.content.Intent(ACTION_SUMMARY_READY)
                .setPackage(applicationContext.packageName)
            applicationContext.sendBroadcast(intent)

            Log.d(
                TAG,
                "AutoUpdateWorker: Completed with ${summary.changed} changes, ${summary.failed} failures, and ${summary.skipped} skipped"
            )
            Result.success()
        } catch (e: CancellationException) {
            Log.d(TAG, "AutoUpdateWorker: Stopped")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "AutoUpdateWorker: Failed", e)
            ErrorReporter.recordException(
                e,
                "Background price update worker failed",
                mapOf("source" to "auto_update_worker")
            )
            Result.retry()
        } finally {
            AutoUpdateNotifier.cancelProgressNotification(applicationContext)
        }
    }

    private fun summaryToJson(summary: UpdateSummary): String {
        val obj = JSONObject()
        obj.put("totalChecked", summary.totalChecked)
        obj.put("changed", summary.changed)
        obj.put("drops", summary.drops)
        obj.put("increases", summary.increases)
        obj.put("failed", summary.failed)
        obj.put("skipped", summary.skipped)
        obj.put("completedAt", summary.completedAt)
        val arr = JSONArray()
        summary.changes.forEach { c ->
            val item = JSONObject()
            item.put("bookId", c.bookId)
            item.put("bookTitle", c.bookTitle)
            item.put("storeName", c.storeName)
            if (c.oldPrice != null) item.put("oldPrice", c.oldPrice) else item.put("oldPrice", JSONObject.NULL)
            if (c.newPrice != null) item.put("newPrice", c.newPrice) else item.put("newPrice", JSONObject.NULL)
            item.put("timestamp", c.timestamp)
            arr.put(item)
        }
        obj.put("changes", arr)
        val failures = JSONArray()
        summary.failures.forEach { failure ->
            val item = JSONObject()
            item.put("bookId", failure.bookId)
            item.put("bookTitle", failure.bookTitle)
            item.put("storeName", failure.storeName)
            item.put("errorMessage", failure.errorMessage)
            failures.put(item)
        }
        obj.put("failures", failures)
        return obj.toString()
    }

    private suspend fun updateProgress(storesProcessed: Int, totalStores: Int, bookTitle: String) {
        val progressText = "Updated $storesProcessed of $totalStores stores: $bookTitle"
        setProgressData(storesProcessed, totalStores, progressText)

        if (storesProcessed % PROGRESS_UPDATE_INTERVAL != 0 && storesProcessed != totalStores) {
            return
        }

        showProgressNotification(progressText, storesProcessed, totalStores)
    }

    private fun showProgressNotification(progressText: String, currentProgress: Int, totalProgress: Int) {
        if (!canPostNotifications()) {
            if (!notificationPermissionLogged) {
                Log.d(TAG, "Skipping progress notification update; POST_NOTIFICATIONS permission not granted.")
                notificationPermissionLogged = true
            }
            return
        }

        try {
            notificationManager.notify(
                AutoUpdateNotifier.NOTIF_ID_FOREGROUND,
                buildProgressNotification(progressText, currentProgress, totalProgress)
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException when updating notification: ${e.message}")
            ErrorReporter.recordException(
                e,
                "Unable to update background progress notification",
                mapOf("source" to "auto_update_notification")
            )
        }
    }

    private suspend fun setProgressData(storesProcessed: Int, totalStores: Int, progressText: String) {
        setProgress(
            workDataOf(
                KEY_PROGRESS_CURRENT to storesProcessed,
                KEY_PROGRESS_TOTAL to totalStores,
                KEY_PROGRESS_TEXT to progressText
            )
        )
    }

    private suspend fun setForegroundIfAllowed(foregroundInfo: ForegroundInfo) {
        try {
            setForeground(foregroundInfo)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Unable to promote auto update to foreground work; continuing as regular work.", e)
            if (isForegroundServiceStartNotAllowed(e)) {
                // Android 12+ can reject WorkManager's foreground service when
                // scheduled work starts while the app is in the background.
                // Continuing under WorkManager's regular execution window is
                // an expected fallback, not a Crashlytics-worthy app error.
                Log.w(TAG, "Foreground execution is not allowed; continuing as regular work.")
            } else {
                ErrorReporter.recordException(
                    e,
                    "Unable to promote auto update to foreground work",
                    mapOf("source" to "auto_update_foreground")
                )
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing permission for foreground auto update; continuing as regular work.", e)
            ErrorReporter.recordException(
                e,
                "Missing permission for foreground auto update",
                mapOf("source" to "auto_update_foreground")
            )
        }
    }

    private fun isForegroundServiceStartNotAllowed(error: IllegalStateException): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            error.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException"
    }

    private fun hostFrom(url: String): String {
        return runCatching { java.net.URL(url).host }
            .getOrDefault("unknown")
            .ifBlank { "unknown" }
    }

    companion object {
        private const val TAG = "AutoUpdateWorker"
        private const val MAX_BACKGROUND_UPDATE_WORKERS = 3
        private const val PROGRESS_UPDATE_INTERVAL = 3 // Update progress notification every N stores
        const val KEY_PROGRESS_CURRENT = "progress_current"
        const val KEY_PROGRESS_TOTAL = "progress_total"
        const val KEY_PROGRESS_TEXT = "progress_text"
        const val ACTION_SUMMARY_READY = "com.booktracker.booksidntneed.UPDATE_SUMMARY_READY"
    }
}
