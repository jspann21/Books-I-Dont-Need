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
import com.booktracker.booksidntneed.BookTrackerApplication
import com.booktracker.booksidntneed.model.BookWithStores
import com.booktracker.booksidntneed.repository.BookRepository
import com.booktracker.booksidntneed.utils.AutoUpdatePreferences
import com.booktracker.booksidntneed.utils.PriceChangeEntry
import com.booktracker.booksidntneed.utils.UpdateSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

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
            val allBooks: List<BookWithStores> = withContext(Dispatchers.IO) {
                // Reuse export query which returns all books with stores
                repository.getAllBooksForExport()
            }

            val totalBooks = allBooks.size
            var booksProcessed = 0
            var totalChecked = 0
            var changes = 0
            var drops = 0
            var increases = 0
            val changeEntries = mutableListOf<PriceChangeEntry>()
            var failedUpdates = 0

            // Set initial foreground info with progress
            val initialProgressText = "Starting price updates... (0 of $totalBooks)"
            setForeground(createForegroundInfo(initialProgressText, totalProgress = totalBooks))

            for (book in allBooks) {
                if (isStopped) {
                    Log.d(TAG, "AutoUpdateWorker: Stopped, exiting early to save resources")
                    return Result.success()
                }

                booksProcessed++
                
                // Throttle notification updates - only update every 3 books or for the last book
                if (booksProcessed % PROGRESS_UPDATE_INTERVAL == 0 || booksProcessed == totalBooks) {
                    val progressText = "Updating book $booksProcessed of $totalBooks: ${book.book.title}"
                    if (canPostNotifications()) {
                        try {
                            notificationManager.notify(
                                AutoUpdateNotifier.NOTIF_ID_FOREGROUND,
                                buildProgressNotification(progressText, booksProcessed, totalBooks)
                            )
                        } catch (e: SecurityException) {
                            Log.e(TAG, "SecurityException when updating notification: ${e.message}")
                        }
                    } else if (!notificationPermissionLogged) {
                        Log.d(TAG, "Skipping progress notification update; POST_NOTIFICATIONS permission not granted.")
                        notificationPermissionLogged = true
                    }
                }

                // Be kind to servers: small delay between books
                delay(PER_BOOK_DELAY_MS)

                val updatableStores = book.stores.filter { store ->
                    store.storeUrl.isNotBlank() && store.storeUrl != "Manual Entry"
                }
                if (updatableStores.isEmpty()) continue

                for (store in updatableStores) {
                    if (isStopped) {
                        Log.d(TAG, "AutoUpdateWorker: Stopped during stores loop, exiting early")
                        return Result.success()
                    }
                    totalChecked++

                    try {
                        val before = store.price
                        // Be kind to servers: delay between stores
                        delay(PER_STORE_DELAY_MS)
                        repository.updateSingleStorePrices(store)
                        // Fetch updated store snapshot
                        val updated = withContext(Dispatchers.IO) { repository.getStoreByBookIdAndStoreName(store.bookId, store.storeName) }
                        val after = updated?.price
                        if (before != after) {
                            changes++
                            if (before != null && after != null) {
                                if (after < before) drops++ else increases++
                            }
                            changeEntries.add(
                                PriceChangeEntry(
                                    bookId = book.book.id,
                                    bookTitle = book.book.title,
                                    storeName = store.storeName,
                                    oldPrice = before,
                                    newPrice = after,
                                    timestamp = System.currentTimeMillis()
                                )
                            )
                        }
                    } catch (e: Exception) {
                        failedUpdates++
                        Log.e(TAG, "Failed to update store for book '${book.book.title}' (${store.storeName}): ${e.message}")
                    }
                }
            }

            val summary = UpdateSummary(
                totalChecked = totalChecked,
                changed = changes,
                drops = drops,
                increases = increases,
                changes = changeEntries,
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

            Log.d(TAG, "AutoUpdateWorker: Completed with ${summary.changed} changes and $failedUpdates failures")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "AutoUpdateWorker: Failed", e)
            Result.retry()
        }
    }

    private fun summaryToJson(summary: UpdateSummary): String {
        val obj = JSONObject()
        obj.put("totalChecked", summary.totalChecked)
        obj.put("changed", summary.changed)
        obj.put("drops", summary.drops)
        obj.put("increases", summary.increases)
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
        return obj.toString()
    }

    companion object {
        private const val TAG = "AutoUpdateWorker"
        private const val PER_BOOK_DELAY_MS = 800L  // ~0.8s between books
        private const val PER_STORE_DELAY_MS = 1200L // ~1.2s between stores
        private const val PROGRESS_UPDATE_INTERVAL = 3 // Update progress notification every N books
        const val ACTION_SUMMARY_READY = "com.booktracker.booksidntneed.UPDATE_SUMMARY_READY"
    }
}
