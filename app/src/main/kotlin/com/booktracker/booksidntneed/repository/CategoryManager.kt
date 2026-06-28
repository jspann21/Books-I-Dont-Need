package com.booktracker.booksidntneed.repository

import android.content.Context
import com.booktracker.booksidntneed.R
import com.booktracker.booksidntneed.model.Category
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class CategoryManager(private val repository: BookRepository, private val scope: CoroutineScope) {

    fun addCustomCategory(context: Context, categoryName: String, color: String? = null, onResult: (Boolean, String?) -> Unit) {
        scope.launch {
            try {
                val category = Category(
                    name = categoryName.trim(),
                    isDefault = false,
                    color = color
                )
                repository.addCategory(category)
                onResult(true, context.getString(R.string.category_created_successfully))
            } catch (e: Exception) {
                onResult(false, context.getString(R.string.failed_to_add_category) + ": " + (e.message ?: ""))
            }
        }
    }

    fun deleteCustomCategory(context: Context, categoryName: String, onResult: (Boolean, String?) -> Unit) {
        scope.launch {
            try {
                repository.deleteCategory(categoryName)
                onResult(true, context.getString(R.string.category_deleted_successfully))
            } catch (e: Exception) {
                onResult(false, context.getString(R.string.failed_to_delete_category) + ": " + (e.message ?: ""))
            }
        }
    }

    fun updateCustomCategory(context: Context, oldName: String, newName: String, newColor: String? = null, onResult: (Boolean, String?) -> Unit) {
        scope.launch {
            try {
                val existingCategory = repository.getCategoryByName(oldName)
                if (existingCategory != null && !existingCategory.isDefault) {
                    val updatedCategory = existingCategory.copy(
                        name = newName.trim(),
                        color = newColor ?: existingCategory.color
                    )
                    repository.updateCategory(oldName, updatedCategory)
                    onResult(true, context.getString(R.string.category_renamed_successfully))
                } else {
                    onResult(false, context.getString(R.string.category_not_found_or_cannot_edit))
                }
            } catch (e: Exception) {
                onResult(false, context.getString(R.string.failed_to_update_category) + ": " + (e.message ?: ""))
            }
        }
    }

    fun validateCategoryName(context: Context, name: String): String? {
        val trimmedName = name.trim()
        return when {
            trimmedName.isBlank() -> context.getString(R.string.category_name_cannot_be_empty)
            trimmedName.length < 2 -> context.getString(R.string.category_name_too_short)
            trimmedName.length > 50 -> context.getString(R.string.category_name_too_long)
            else -> null
        }
    }

}