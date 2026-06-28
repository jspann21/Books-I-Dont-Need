package com.booktracker.booksidntneed.model

import androidx.room.Embedded
import androidx.room.Relation
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class BookWithStores(
    @Embedded val book: Book,
    @Relation(
        parentColumn = "id",
        entityColumn = "bookId"
    )
    val stores: List<BookStore>
) : Parcelable {
    
    fun getLowestPrice(): Double? {
        return stores.mapNotNull { it.price }.minOrNull()
    }
    
    fun getHighestPrice(): Double? {
        return stores.mapNotNull { it.price }.maxOrNull()
    }

    /**
     * Returns stores sorted by price from cheapest to most expensive.
     * Stores without prices are placed at the end.
     */
    fun getStoresSortedByPrice(): List<BookStore> {
        return stores.sortedWith(compareBy<BookStore> { it.price == null }.thenBy { it.price })
    }
} 