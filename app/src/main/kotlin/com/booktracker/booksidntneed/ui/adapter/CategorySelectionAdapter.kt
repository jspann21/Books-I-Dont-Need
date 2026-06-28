package com.booktracker.booksidntneed.ui.adapter

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.booktracker.booksidntneed.R
import com.booktracker.booksidntneed.databinding.ItemCategorySelectionBinding
import com.booktracker.booksidntneed.model.Category
import com.google.android.material.color.MaterialColors

class CategorySelectionAdapter(
    private val onCategoryClick: (Category) -> Unit,
    private val onCategoryMenuClick: (Category) -> Unit,
    private var selectedCategoryName: String? = null
) : ListAdapter<Category, CategorySelectionAdapter.CategoryViewHolder>(CategoryDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
        val binding = ItemCategorySelectionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return CategoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class CategoryViewHolder(private val binding: ItemCategorySelectionBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(category: Category) {
            // Set category name
            binding.categoryName.text = category.name

            // Show default badge for default categories
            if (category.isDefault) {
                binding.defaultBadge.visibility = View.VISIBLE
            } else {
                binding.defaultBadge.visibility = View.GONE
            }

            // Set category color
            if (!category.color.isNullOrBlank()) {
                try {
                    val color = category.color.toColorInt()
                    binding.categoryColorIndicator.backgroundTintList = 
                        ColorStateList.valueOf(color)
                } catch (_: IllegalArgumentException) {
                    // Fallback to default color if parsing fails
                    binding.categoryColorIndicator.backgroundTintList = 
                        ColorStateList.valueOf(ContextCompat.getColor(binding.root.context, R.color.category_uncategorized))
                }
            } else {
                // Default color for categories without a specified color
                binding.categoryColorIndicator.backgroundTintList = 
                    ColorStateList.valueOf(ContextCompat.getColor(binding.root.context, R.color.category_uncategorized))
            }

            // Show selection indicators if this category is selected            
            val isSelected = category.name == selectedCategoryName
            binding.selectionRing.visibility = if (isSelected) View.VISIBLE else View.GONE
            binding.selectionDot.visibility = if (isSelected) View.VISIBLE else View.GONE

            // Show menu icon for custom categories (not default ones)
            if (!category.isDefault) {
                binding.categoryMenuIcon.visibility = View.VISIBLE
                binding.categoryMenuIcon.setOnClickListener { 
                    onCategoryMenuClick(category) 
                }
            } else {
                binding.categoryMenuIcon.visibility = View.GONE
            }

            // Set click listener for the entire item
            binding.root.setOnClickListener { 
                onCategoryClick(category) 
            }

            // Update background for selected state using proper theme attributes
            if (isSelected) {
                // Use theme-aware colors that automatically adapt to light/dark mode
                val primaryContainer = MaterialColors.getColor(binding.root.context, 
                    com.google.android.material.R.attr.colorPrimaryContainer, 
                    "colorPrimaryContainer")
                
                // Set background color for selected item
                binding.root.setBackgroundColor(primaryContainer)
                
                // Update text colors for better contrast on selected background
                val onPrimaryContainer = MaterialColors.getColor(binding.root.context,
                    com.google.android.material.R.attr.colorOnPrimaryContainer,
                    "colorOnPrimaryContainer")
                
                binding.categoryName.setTextColor(onPrimaryContainer)
            } else {
                // Reset to default selectable background for unselected items
                val typedArray = binding.root.context.theme.obtainStyledAttributes(
                    intArrayOf(android.R.attr.selectableItemBackground)
                )
                val selectableBackground = typedArray.getDrawable(0)
                typedArray.recycle()
                binding.root.background = selectableBackground
                
                // Reset text colors to default theme colors
                val onSurface = MaterialColors.getColor(binding.root.context,
                    com.google.android.material.R.attr.colorOnSurface,
                    "colorOnSurface")
                
                binding.categoryName.setTextColor(onSurface)
            }
        }
    }

    class CategoryDiffCallback : DiffUtil.ItemCallback<Category>() {
        override fun areItemsTheSame(oldItem: Category, newItem: Category): Boolean {
            return oldItem.name == newItem.name
        }

        override fun areContentsTheSame(oldItem: Category, newItem: Category): Boolean {
            return oldItem == newItem
        }
    }

    // Method to update the selected category and refresh the adapter
    fun updateSelectedCategory(selectedCategoryName: String?) {
        val previousSelection = this.selectedCategoryName
        if (previousSelection == selectedCategoryName) return

        this.selectedCategoryName = selectedCategoryName

        val previousIndex = previousSelection?.let { prev ->
            currentList.indexOfFirst { it.name == prev }
        } ?: -1
        val newIndex = selectedCategoryName?.let { selected ->
            currentList.indexOfFirst { it.name == selected }
        } ?: -1

        if (previousIndex != -1) {
            notifyItemChanged(previousIndex)
        }
        if (newIndex != -1) {
            notifyItemChanged(newIndex)
        }
    }
} 
