package com.booktracker.booksidntneed.database

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import com.booktracker.booksidntneed.model.Book
import com.booktracker.booksidntneed.model.BookWithSortData
import com.booktracker.booksidntneed.model.BookWithStores

@Dao
interface BookDao {
    
    @Query("SELECT * FROM books ORDER BY dateAdded DESC")
    fun getAllBooks(): LiveData<List<Book>>
    
    @Query("SELECT * FROM books ORDER BY dateAdded DESC")
    suspend fun getAllBooksSync(): List<Book>
    
    @Query("SELECT * FROM books WHERE id = :bookId")
    suspend fun getBookById(bookId: Long): Book?
    
    @Query("SELECT * FROM books WHERE isbn10 = :isbn OR isbn13 = :isbn LIMIT 1")
    suspend fun getBookByISBN(isbn: String): Book?
    
    @Transaction
    @Query("SELECT * FROM books ORDER BY dateAdded DESC")
    fun getAllBooksWithStores(): LiveData<List<BookWithStores>>
    
    @Transaction
    @Query("SELECT * FROM books ORDER BY dateAdded DESC")
    suspend fun getAllBooksForExport(): List<BookWithStores>
    
    @Transaction
    @Query("SELECT * FROM books WHERE id = :bookId")
    suspend fun getBookWithStores(bookId: Long): BookWithStores?
    
    @Transaction
    @Query("SELECT * FROM books WHERE category = :category ORDER BY dateAdded DESC")
    fun getBooksByCategory(category: String): LiveData<List<BookWithStores>>
    

    
    @Query("SELECT * FROM books WHERE title LIKE '%' || :searchQuery || '%' OR author LIKE '%' || :searchQuery || '%'")
    fun searchBooks(searchQuery: String): LiveData<List<Book>>
    
    @Query("SELECT * FROM books ORDER BY title ASC")
    fun getBooksOrderedByTitle(): LiveData<List<Book>>
    
    @Query("SELECT * FROM books ORDER BY author ASC")
    fun getBooksOrderedByAuthor(): LiveData<List<Book>>
    
    @Query("SELECT * FROM books ORDER BY dateAdded ASC")
    fun getBooksOrderedByDateAdded(): LiveData<List<Book>>
    
    @Query("SELECT * FROM books WHERE isbn13 = :isbn13 LIMIT 1")
    suspend fun getBookByISBN13(isbn13: String): Book?
    
    @Query("SELECT * FROM books WHERE isbn10 = :isbn10 LIMIT 1")
    suspend fun getBookByISBN10(isbn10: String): Book?
    
    @Query("SELECT * FROM books WHERE title = :title AND author = :author LIMIT 1")
    suspend fun getBookByTitleAndAuthor(title: String, author: String): Book?
    
    // Bulk query methods for efficient import operations
    @Query("SELECT * FROM books WHERE isbn13 IN (:isbn13List)")
    suspend fun getBooksByISBN13List(isbn13List: List<String>): List<Book>
    
    @Query("SELECT * FROM books WHERE isbn10 IN (:isbn10List)")
    suspend fun getBooksByISBN10List(isbn10List: List<String>): List<Book>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: Book): Long
    
    // Bulk insert operations for efficient import
    @Transaction
    suspend fun insertBooksInTransaction(books: List<Book>): List<Long> {
        val bookIds = mutableListOf<Long>()
        books.forEach { book ->
            bookIds.add(insertBook(book))
        }
        return bookIds
    }
    
    @Update
    suspend fun updateBook(book: Book)
    
    @Delete
    suspend fun deleteBook(book: Book)
    
    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun deleteBookById(bookId: Long)
    
    @Query("SELECT COUNT(*) FROM books")
    suspend fun getBookCount(): Int
    
    @Query("SELECT DISTINCT category FROM books ORDER BY category ASC")
    fun getUsedCategories(): LiveData<List<String>>
    
    @Query("UPDATE books SET category = :newCategoryName WHERE category = :oldCategoryName")
    suspend fun updateBooksCategory(oldCategoryName: String, newCategoryName: String)
    
    // New methods for the DatabaseView approach
    @RawQuery(observedEntities = [BookWithSortDataView::class])
    fun getBooksWithSortData(query: SupportSQLiteQuery): LiveData<List<BookWithSortData>>
    
    // We still need this to fetch the full objects for the adapter
    @Transaction
    @Query("SELECT * FROM books WHERE id IN (:bookIds)")
    fun getBooksWithStoresByIds(bookIds: List<Long>): LiveData<List<BookWithStores>>
} 