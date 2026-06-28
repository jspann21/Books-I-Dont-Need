package com.booktracker.booksidntneed.model

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize
import java.util.Date

@Parcelize
@Entity(tableName = "books")
data class Book(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val author: String,
    val isbn10: String? = null,
    val isbn13: String? = null,
    val coverImageUrl: String? = null,
    val category: String = "Uncategorized",
    val dateAdded: Date = Date(),
    val language: String? = null,
    val pages: Int? = null,
    val publisher: String? = null,
    val publishedDate: String? = null
) : Parcelable 