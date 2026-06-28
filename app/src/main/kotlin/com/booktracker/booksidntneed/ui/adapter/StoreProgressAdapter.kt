package com.booktracker.booksidntneed.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.booktracker.booksidntneed.R
import com.booktracker.booksidntneed.databinding.ItemStoreProgressBinding
import com.booktracker.booksidntneed.ui.MainViewModel
import com.google.android.material.color.MaterialColors
import androidx.appcompat.R as AppCompatR

class StoreProgressAdapter : ListAdapter<MainViewModel.StoreUpdateProgress, StoreProgressAdapter.ProgressViewHolder>(ProgressDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProgressViewHolder {
        val binding = ItemStoreProgressBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ProgressViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ProgressViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ProgressViewHolder(private val binding: ItemStoreProgressBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(progress: MainViewModel.StoreUpdateProgress) {
            // Set store name
            binding.storeName.text = progress.storeName

            // Update status and icon based on current state
            when (progress.status) {
                MainViewModel.StoreUpdateStatus.PENDING -> {
                    binding.statusIcon.setImageResource(R.drawable.ic_schedule)
                    val pendingColor = MaterialColors.getColor(binding.root.context, com.google.android.material.R.attr.colorOnSurfaceVariant, ContextCompat.getColor(binding.root.context, R.color.date_text_color))
                    binding.statusIcon.setColorFilter(pendingColor)
                    binding.statusText.text = "Pending"
                    binding.statusText.setTextColor(pendingColor)
                    binding.progressIndicator.visibility = View.GONE
                    binding.priceLayout.visibility = View.GONE
                    binding.errorMessage.visibility = View.GONE
                }
                MainViewModel.StoreUpdateStatus.UPDATING -> {
                    binding.statusIcon.setImageResource(R.drawable.ic_schedule)
                    val updatingColor = MaterialColors.getColor(binding.root.context, AppCompatR.attr.colorPrimary, ContextCompat.getColor(binding.root.context, R.color.link_color))
                    binding.statusIcon.setColorFilter(updatingColor)
                    binding.statusText.text = "Updating..."
                    binding.statusText.setTextColor(updatingColor)
                    binding.progressIndicator.visibility = View.VISIBLE
                    binding.priceLayout.visibility = View.GONE
                    binding.errorMessage.visibility = View.GONE
                }
                MainViewModel.StoreUpdateStatus.SUCCESS -> {
                    binding.statusIcon.setImageResource(R.drawable.ic_check_circle)
                    val successColor = MaterialColors.getColor(binding.root.context, com.google.android.material.R.attr.colorSecondary, ContextCompat.getColor(binding.root.context, R.color.colorSuccess))
                    binding.statusIcon.setColorFilter(successColor)
                    binding.statusText.text = "Success"
                    binding.statusText.setTextColor(successColor)
                    binding.progressIndicator.visibility = View.GONE
                    binding.errorMessage.visibility = View.GONE
                    
                    // Show price information if available
                    if (progress.oldPrice != null || progress.newPrice != null) {
                        binding.priceLayout.visibility = View.VISIBLE
                        binding.oldPriceText.text = if (progress.oldPrice != null) "$${String.format("%.2f", progress.oldPrice)}" else "N/A"
                        binding.newPriceText.text = if (progress.newPrice != null) "$${String.format("%.2f", progress.newPrice)}" else "N/A"
                        
                        // Change text color based on price change
                        when {
                            progress.oldPrice != null && progress.newPrice != null -> {
                                when {
                                    progress.newPrice < progress.oldPrice -> {
                                        binding.newPriceText.setTextColor(ContextCompat.getColor(binding.root.context, R.color.colorSuccess))
                                    }
                                    progress.newPrice > progress.oldPrice -> {
                                        binding.newPriceText.setTextColor(ContextCompat.getColor(binding.root.context, R.color.md_theme_light_tertiary))
                                    }
                                    else -> {
                                        binding.newPriceText.setTextColor(ContextCompat.getColor(binding.root.context, R.color.date_text_color))
                                    }
                                }
                            }
                            else -> {
                                binding.newPriceText.setTextColor(ContextCompat.getColor(binding.root.context, R.color.link_color))
                            }
                        }
                    } else {
                        binding.priceLayout.visibility = View.GONE
                    }
                }
                MainViewModel.StoreUpdateStatus.FAILED -> {
                    binding.statusIcon.setImageResource(R.drawable.ic_error)
                    val errorColor = MaterialColors.getColor(binding.root.context, AppCompatR.attr.colorError, ContextCompat.getColor(binding.root.context, R.color.md_theme_light_tertiary))
                    binding.statusIcon.setColorFilter(errorColor)
                    binding.statusText.text = "Failed"
                    binding.statusText.setTextColor(errorColor)
                    binding.progressIndicator.visibility = View.GONE
                    binding.priceLayout.visibility = View.GONE
                    
                    // Show error message if available
                    if (!progress.errorMessage.isNullOrBlank()) {
                        binding.errorMessage.visibility = View.VISIBLE
                        binding.errorMessage.text = progress.errorMessage
                    } else {
                        binding.errorMessage.visibility = View.GONE
                    }
                }
                MainViewModel.StoreUpdateStatus.SKIPPED -> {
                    binding.statusIcon.setImageResource(R.drawable.ic_skip_next)
                    val skippedColor = MaterialColors.getColor(binding.root.context, com.google.android.material.R.attr.colorOnSurfaceVariant, ContextCompat.getColor(binding.root.context, R.color.date_text_color))
                    binding.statusIcon.setColorFilter(skippedColor)
                    binding.statusText.text = "Skipped"
                    binding.statusText.setTextColor(skippedColor)
                    binding.progressIndicator.visibility = View.GONE
                    binding.priceLayout.visibility = View.GONE
                    
                    // Show skip reason if available
                    if (!progress.errorMessage.isNullOrBlank()) {
                        binding.errorMessage.visibility = View.VISIBLE
                        binding.errorMessage.text = progress.errorMessage
                        binding.errorMessage.setTextColor(skippedColor)
                    } else {
                        binding.errorMessage.visibility = View.GONE
                    }
                }
            }
        }
    }

    class ProgressDiffCallback : DiffUtil.ItemCallback<MainViewModel.StoreUpdateProgress>() {
        override fun areItemsTheSame(oldItem: MainViewModel.StoreUpdateProgress, newItem: MainViewModel.StoreUpdateProgress): Boolean {
            return oldItem.storeId == newItem.storeId
        }

        override fun areContentsTheSame(oldItem: MainViewModel.StoreUpdateProgress, newItem: MainViewModel.StoreUpdateProgress): Boolean {
            return oldItem == newItem
        }
    }
} 
