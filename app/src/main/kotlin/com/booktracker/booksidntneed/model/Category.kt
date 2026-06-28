package com.booktracker.booksidntneed.model

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

@Parcelize
@Entity(tableName = "categories")
data class Category(
    @PrimaryKey
    val name: String,
    val isDefault: Boolean = false,
    val color: String? = null
) : Parcelable {
    companion object {
        fun getDefaultCategories(): List<Category> {
            return listOf(
                Category("Want", true, "#EF4444"),      // Red
                Category("Priority", true, "#F59E0B"),  // Orange
                Category("Buy", true, "#10B981"),       // Green
                Category("Watch", true, "#3B82F6"),     // Blue
                Category("Gift", true, "#8B5CF6"),      // Violet
                Category("Uncategorized", true, "#64748B") // Gray - at bottom
            )
        }
    }
} 