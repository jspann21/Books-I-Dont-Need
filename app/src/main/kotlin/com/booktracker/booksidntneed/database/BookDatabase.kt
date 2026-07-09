package com.booktracker.booksidntneed.database

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
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
    version = 5,
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
                .addMigrations(MIGRATION_4_5)
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

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_books_isbn13` ON `books` (`isbn13`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_books_isbn10` ON `books` (`isbn10`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_books_category` ON `books` (`category`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_books_title_author` ON `books` (`title`, `author`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_books_title` ON `books` (`title`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_books_author` ON `books` (`author`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_books_dateAdded` ON `books` (`dateAdded`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_stores_bookId_storeName` ON `book_stores` (`bookId`, `storeName`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_stores_bookId_price` ON `book_stores` (`bookId`, `price`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_stores_storeName` ON `book_stores` (`storeName`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_stores_price` ON `book_stores` (`price`)")
            }
        }
    }
}
