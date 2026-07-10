package com.booktracker.booksidntneed

import android.app.Application
import com.booktracker.booksidntneed.database.BookDatabase
import com.booktracker.booksidntneed.network.WebScrapingService
import com.booktracker.booksidntneed.repository.BookRepository
import com.booktracker.booksidntneed.utils.ErrorReporter
import com.booktracker.booksidntneed.utils.ThemePreferences
import com.booktracker.booksidntneed.work.AutoUpdateNotifier

class BookTrackerApplication : Application() {
    
    // Lazy initialization of database and repository
    val database by lazy { BookDatabase.getDatabase(this) }
    
    val repository by lazy {
        BookRepository(
            database,
            WebScrapingService()
        )
    }

    override fun onCreate() {
        super.onCreate()
        ErrorReporter.initialize(this)
        ErrorReporter.logEvent("app_opened")
        ThemePreferences.applySavedTheme(this)
        // Ensure notification channel exists
        AutoUpdateNotifier.ensureChannel(this)
    }
} 
