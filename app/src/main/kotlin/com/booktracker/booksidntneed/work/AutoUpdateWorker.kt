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
import com.booktracker.booksidntneed.model.BookWithStores
import com.booktracker.booksidntneed.repository.BookRepository
import com.booktracker.booksidntneed.utils.AutoUpdatePreferences
import com.booktracker.booksidntneed.utils.PriceChangeEntry
import com.booktracker.booksidntneed.utils.UpdateSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
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
            val allBooks: List<BookWithStores> = withContext(Dispatchers.IO) {
                // Reuse export query which returns all books with stores
                repository.getAllBooksForExport()
            }

            val updateTargets = allBooks.flatMap { book ->
                book.stores
                    .filter { store -> store.storeUrl.isNotBlank() && store.storeUrl != "Manual Entry" }
                    .map { store -> PriceUpdateTarget(book, store) }
            }
            val totalStores = updateTargets.size
            val storesProcessed = AtomicInteger(0)
            val totalChecked = AtomicInteger(0)
            val changes = AtomicInteger(0)
            val drops = AtomicInteger(0)
            val increases = AtomicInteger(0)
            val failedUpdates = AtomicInteger(0)
            val changeEntries = Collections.synchronizedList(mutableListOf<PriceChangeEntry>())
            val requestLimiter = ResponsiblePriceUpdateLimiter()

            // Set initial foreground info with progress
            val initialProgressText = "Starting price updates... (0 of $totalStores)"
            setForeground(createForegroundInfo(initialProgressText, totalProgress = totalStores))
            setProgressData(0, totalStores, initialProgressText)

            coroutineScope {
                updateTargets.map { target ->
                    async {
                        val book = target.book
                        val store = target.store
                        if (isStopped) {
                            Log.d(TAG, "AutoUpdateWorker: Skipping remaining work because worker stopped")
                            return@async
                        }
                        totalChecked.incrementAndGet()

                        try {
                            val before = store.price
                            requestLimiter.run(store.storeUrl) {
                                repository.updateSingleStorePrices(store)
                            }
                            // Fetch updated store snapshot
                            val updated = withContext(Dispatchers.IO) { repository.getStoreByBookIdAndStoreName(store.bookId, store.storeName) }
                            val after = updated?.price
                            if (before != after) {
                                changes.incrementAndGet()
                                if (before != null && after != null) {
                                    if (after < before) drops.incrementAndGet() else increases.incrementAndGet()
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
                            failedUpdates.incrementAndGet()
                            Log.e(TAG, "Failed to update store for book '${book.book.title}' (${store.storeName}): ${e.message}")
                        } finally {
                            val processed = storesProcessed.incrementAndGet()
                            updateProgress(processed, totalStores, book.book.title)
                        }
                    }
                }.awaitAll()
            }

            val summary = UpdateSummary(
                totalChecked = totalChecked.get(),
                changed = changes.get(),
                drops = drops.get(),
                increases = increases.get(),
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

            Log.d(TAG, "AutoUpdateWorker: Completed with ${summary.changed} changes and ${failedUpdates.get()} failures")
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

    private suspend fun updateProgress(storesProcessed: Int, totalStores: Int, bookTitle: String) {
        val progressText = "Updated $storesProcessed of $totalStores stores: $bookTitle"
        setProgressData(storesProcessed, totalStores, progressText)

        if (storesProcessed % PROGRESS_UPDATE_INTERVAL != 0 && storesProcessed != totalStores) {
            return
        }

        if (canPostNotifications()) {
            try {
                notificationManager.notify(
                    AutoUpdateNotifier.NOTIF_ID_FOREGROUND,
                    buildProgressNotification(progressText, storesProcessed, totalStores)
                )
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException when updating notification: ${e.message}")
            }
        } else if (!notificationPermissionLogged) {
            Log.d(TAG, "Skipping progress notification update; POST_NOTIFICATIONS permission not granted.")
            notificationPermissionLogged = true
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

    private data class PriceUpdateTarget(
        val book: BookWithStores,
        val store: com.booktracker.booksidntneed.model.BookStore
    )

    companion object {
        private const val TAG = "AutoUpdateWorker"
        private const val PROGRESS_UPDATE_INTERVAL = 3 // Update progress notification every N stores
        const val KEY_PROGRESS_CURRENT = "progress_current"
        const val KEY_PROGRESS_TOTAL = "progress_total"
        const val KEY_PROGRESS_TEXT = "progress_text"
        const val ACTION_SUMMARY_READY = "com.booktracker.booksidntneed.UPDATE_SUMMARY_READY"
    }
}
