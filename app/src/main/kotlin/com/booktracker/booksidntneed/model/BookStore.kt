package com.booktracker.booksidntneed.model

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize
import java.util.Date

@Parcelize
@Entity(
    tableName = "book_stores",
    foreignKeys = [
        ForeignKey(
            entity = Book::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["bookId"]),
        Index(value = ["bookId", "storeName"]),
        Index(value = ["bookId", "price"]),
        Index(value = ["storeName"]),
        Index(value = ["price"])
    ]
)
data class BookStore(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bookId: Long,
    val storeName: String,
    val storeUrl: String,
    val price: Double? = null,
    val currency: String = "USD",
    val availability: String? = null,
    val dateAdded: Date = Date(),
    val lastUpdated: Date = Date()
) : Parcelable
