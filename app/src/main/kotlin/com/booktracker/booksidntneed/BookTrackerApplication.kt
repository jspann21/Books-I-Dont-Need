package com.booktracker.booksidntneed

import android.app.Application
import com.booktracker.booksidntneed.database.BookDatabase
import com.booktracker.booksidntneed.network.WebScrapingService
import com.booktracker.booksidntneed.repository.BookRepository
import com.booktracker.booksidntneed.utils.AutoUpdatePreferences
import com.booktracker.booksidntneed.work.AutoUpdateNotifier
import com.booktracker.booksidntneed.work.AutoUpdateScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BookTrackerApplication : Application() {
    
    // Lazy initialization of database and repository
    val database by lazy { BookDatabase.getDatabase(this) }
    
    val repository by lazy {
        BookRepository(
            database,
            WebScrapingService()
        )
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Ensure notification channel exists
        AutoUpdateNotifier.ensureChannel(this)
        // Re-schedule daily worker if enabled
        appScope.launch {
            val enabled = AutoUpdatePreferences.isEnabled(this@BookTrackerApplication).first()
            if (enabled) {
                val minutes = AutoUpdatePreferences.timeMinutes(this@BookTrackerApplication).first()
                AutoUpdateScheduler.scheduleDaily(this@BookTrackerApplication, minutes)
            }
        }
    }
} 