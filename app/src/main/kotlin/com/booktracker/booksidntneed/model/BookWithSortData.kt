package com.booktracker.booksidntneed.model

import androidx.room.Embedded

// This data class will hold the result of our complex query.
// It's similar to BookWithStores but flattened and with calculated fields.
data class BookWithSortData(
    @Embedded
    val book: Book,
    val lowestPrice: Double?,
    val primaryStoreName: String? // A single, deterministic store name for sorting
) 