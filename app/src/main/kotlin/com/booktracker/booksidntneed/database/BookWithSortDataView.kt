package com.booktracker.booksidntneed.database

import androidx.room.DatabaseView
import androidx.room.Embedded
import com.booktracker.booksidntneed.model.Book

// This is the Database View. It's a virtual table built from this complex query.
@DatabaseView("""
    SELECT 
        b.*,  -- Select all columns from the books table
        MIN(s.price) AS lowestPrice, -- Calculate the minimum price for each book
        MIN(s.storeName) AS primaryStoreName -- Get the alphabetically first store name for sorting
    FROM books AS b
    LEFT JOIN book_stores AS s ON b.id = s.bookId -- LEFT JOIN to include books with NO stores
    GROUP BY b.id -- Group results by book to perform aggregations (MIN)
""")
data class BookWithSortDataView(
    @Embedded
    val book: Book,
    val lowestPrice: Double?,
    val primaryStoreName: String?
)
