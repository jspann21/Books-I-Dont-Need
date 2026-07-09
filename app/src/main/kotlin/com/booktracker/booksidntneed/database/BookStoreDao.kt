package com.booktracker.booksidntneed.database

import androidx.lifecycle.LiveData
import androidx.room.*
import com.booktracker.booksidntneed.model.BookStore
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Insert
import androidx.room.Update
import androidx.room.Delete
import androidx.room.OnConflictStrategy

data class AutoUpdateStoreTarget(
    val bookTitle: String,
    @Embedded(prefix = "store_")
    val store: BookStore
)

@Dao
interface BookStoreDao {
    
    @Query("SELECT * FROM book_stores WHERE bookId = :bookId ORDER BY price ASC")
    fun getStoresForBook(bookId: Long): LiveData<List<BookStore>>
    
    @Query("SELECT * FROM book_stores WHERE bookId = :bookId AND storeName = :storeName LIMIT 1")
    suspend fun getStoreForBook(bookId: Long, storeName: String): BookStore?

    @Query("SELECT * FROM book_stores WHERE bookId IN (:bookIds)")
    suspend fun getStoresForBooks(bookIds: List<Long>): List<BookStore>

    @Query("""
        SELECT
            b.title AS bookTitle,
            s.id AS store_id,
            s.bookId AS store_bookId,
            s.storeName AS store_storeName,
            s.storeUrl AS store_storeUrl,
            s.price AS store_price,
            s.currency AS store_currency,
            s.availability AS store_availability,
            s.dateAdded AS store_dateAdded,
            s.lastUpdated AS store_lastUpdated
        FROM book_stores AS s
        INNER JOIN books AS b ON b.id = s.bookId
        WHERE TRIM(s.storeUrl) != ''
            AND s.storeUrl != 'Manual Entry'
        ORDER BY b.title ASC, s.storeName ASC
    """)
    suspend fun getAutoUpdateStoreTargets(): List<AutoUpdateStoreTarget>
    
    @Query("SELECT * FROM book_stores ORDER BY storeName ASC")
    fun getAllStores(): LiveData<List<BookStore>>
    
    @Query("SELECT DISTINCT storeName FROM book_stores ORDER BY storeName ASC")
    fun getDistinctStoreNames(): LiveData<List<String>>
    
    @Query("SELECT * FROM book_stores WHERE price BETWEEN :minPrice AND :maxPrice")
    fun getStoresByPriceRange(minPrice: Double, maxPrice: Double): LiveData<List<BookStore>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStore(store: BookStore): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStores(stores: List<BookStore>)
    
    // Bulk insert operations for efficient import
    @Transaction
    suspend fun insertStoresInTransaction(stores: List<BookStore>) {
        insertStores(stores)
    }
    
    @Update
    suspend fun updateStore(store: BookStore)
    
    @Delete
    suspend fun deleteStore(store: BookStore)
    
    @Query("DELETE FROM book_stores WHERE bookId = :bookId")
    suspend fun deleteStoresForBook(bookId: Long)
    
    @Query("DELETE FROM book_stores WHERE bookId = :bookId AND storeName = :storeName")
    suspend fun deleteStoreForBook(bookId: Long, storeName: String)
    
    @Query("SELECT AVG(price) FROM book_stores WHERE bookId = :bookId AND price IS NOT NULL")
    suspend fun getAveragePriceForBook(bookId: Long): Double?
    
    @Query("SELECT MIN(price) FROM book_stores WHERE bookId = :bookId AND price IS NOT NULL")
    suspend fun getLowestPriceForBook(bookId: Long): Double?
    
    @Query("SELECT MAX(price) FROM book_stores WHERE bookId = :bookId AND price IS NOT NULL")
    suspend fun getHighestPriceForBook(bookId: Long): Double?
} 
