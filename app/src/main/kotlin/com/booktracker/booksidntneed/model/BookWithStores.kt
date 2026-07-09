package com.booktracker.booksidntneed.model

import androidx.room.Embedded
import androidx.room.Ignore
import androidx.room.Relation
import android.os.Parcelable
import kotlinx.parcelize.IgnoredOnParcel
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

    data class PriceSummary(
        val lowestPrice: Double?,
        val highestPrice: Double?
    )

    @Ignore
    @IgnoredOnParcel
    private var priceSummaryCache: PriceSummary? = null

    @Ignore
    @IgnoredOnParcel
    private var storesSortedByPriceCache: List<BookStore>? = null
    
    fun getLowestPrice(): Double? {
        return getPriceSummary().lowestPrice
    }
    
    fun getHighestPrice(): Double? {
        return getPriceSummary().highestPrice
    }

    fun getPriceSummary(): PriceSummary {
        priceSummaryCache?.let { return it }

        var lowestPrice: Double? = null
        var highestPrice: Double? = null

        stores.forEach { store ->
            val price = store.price ?: return@forEach
            lowestPrice = lowestPrice?.let { minOf(it, price) } ?: price
            highestPrice = highestPrice?.let { maxOf(it, price) } ?: price
        }

        return PriceSummary(lowestPrice, highestPrice).also {
            priceSummaryCache = it
        }
    }

    /**
     * Returns stores sorted by price from cheapest to most expensive.
     * Stores without prices are placed at the end.
     */
    fun getStoresSortedByPrice(): List<BookStore> {
        storesSortedByPriceCache?.let { return it }

        return stores.sortedWith(compareBy<BookStore> { it.price == null }.thenBy { it.price })
            .also { storesSortedByPriceCache = it }
    }
} 
