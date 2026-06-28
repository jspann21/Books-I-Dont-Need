package com.booktracker.booksidntneed.database

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.booktracker.booksidntneed.model.Category

@Dao
interface CategoryDao {
    
    @Query("""
        SELECT * FROM categories 
        ORDER BY isDefault DESC, 
        CASE name 
            WHEN 'Want' THEN 1
            WHEN 'Priority' THEN 2  
            WHEN 'Buy' THEN 3
            WHEN 'Watch' THEN 4
            WHEN 'Gift' THEN 5
            WHEN 'Uncategorized' THEN 6
            ELSE 7
        END,
        name ASC
    """)
    fun getAllCategories(): LiveData<List<Category>>
    
    @Query("""
        SELECT * FROM categories WHERE isDefault = 1 
        ORDER BY 
        CASE name 
            WHEN 'Want' THEN 1
            WHEN 'Priority' THEN 2  
            WHEN 'Buy' THEN 3
            WHEN 'Watch' THEN 4
            WHEN 'Gift' THEN 5
            WHEN 'Uncategorized' THEN 6
            ELSE 7
        END
    """)
    fun getDefaultCategories(): LiveData<List<Category>>
    
    @Query("SELECT * FROM categories WHERE isDefault = 0 ORDER BY name ASC")
    fun getCustomCategories(): LiveData<List<Category>>
    
    @Query("SELECT * FROM categories WHERE name = :name LIMIT 1")
    suspend fun getCategoryByName(name: String): Category?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategory(category: Category)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(categories: List<Category>)
    
    @Update
    suspend fun updateCategory(category: Category)
    
    @Delete
    suspend fun deleteCategory(category: Category)
    
    @Query("DELETE FROM categories WHERE name = :name AND isDefault = 0")
    suspend fun deleteCustomCategory(name: String)
    
    @Query("SELECT COUNT(*) FROM categories WHERE isDefault = 0")
    suspend fun getCustomCategoryCount(): Int
    
    @Query("""
        SELECT name FROM categories 
        ORDER BY isDefault DESC,
        CASE name 
            WHEN 'Want' THEN 1
            WHEN 'Priority' THEN 2  
            WHEN 'Buy' THEN 3
            WHEN 'Watch' THEN 4
            WHEN 'Gift' THEN 5
            WHEN 'Uncategorized' THEN 6
            ELSE 7
        END,
        name ASC
    """)
    fun getCategoryNames(): LiveData<List<String>>
} 