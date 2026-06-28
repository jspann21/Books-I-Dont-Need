package com.booktracker.booksidntneed.database

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context
import com.booktracker.booksidntneed.model.Book
import com.booktracker.booksidntneed.model.BookStore
import com.booktracker.booksidntneed.model.Category
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob

@Database(
    entities = [Book::class, BookStore::class, Category::class],
    views = [BookWithSortDataView::class],
    version = 4,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class BookDatabase : RoomDatabase() {
    
    abstract fun bookDao(): BookDao
    abstract fun bookStoreDao(): BookStoreDao
    abstract fun categoryDao(): CategoryDao
    
    companion object {
        @Volatile
        private var INSTANCE: BookDatabase? = null
        
        fun getDatabase(context: Context): BookDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    BookDatabase::class.java,
                    "book_database"
                )
                .addCallback(DatabaseCallback())
                .fallbackToDestructiveMigration(true)
                .build()
                INSTANCE = instance
                instance
            }
        }
        
        private class DatabaseCallback : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                INSTANCE?.let { database ->
                    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                        populateDatabase(database.categoryDao())
                    }
                }
            }
        }
        
        private suspend fun populateDatabase(categoryDao: CategoryDao) {
            categoryDao.insertCategories(Category.getDefaultCategories())
        }
    }
}
