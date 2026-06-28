package com.booktracker.booksidntneed.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.booktracker.booksidntneed.R
import com.booktracker.booksidntneed.databinding.ItemStoreInfoBinding
import com.booktracker.booksidntneed.model.BookStore
import java.util.Date
import java.util.concurrent.TimeUnit

class StoresAdapter(
    private val onStoreClick: (BookStore) -> Unit
) : ListAdapter<BookStore, StoresAdapter.StoreViewHolder>(StoreDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StoreViewHolder {
        val binding = ItemStoreInfoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return StoreViewHolder(binding)
    }

    override fun onBindViewHolder(holder: StoreViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class StoreViewHolder(private val binding: ItemStoreInfoBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(store: BookStore) {
            // Store name
            binding.storeNameTextView.text = store.storeName

            // Price
            if (store.price != null) {
                binding.storePriceTextView.text = String.format("$%.2f", store.price)
            } else {
                binding.storePriceTextView.text = binding.root.context.getString(R.string.no_price_available)
            }

            // Last updated
            binding.lastUpdatedTextView.text = getRelativeTimeString(store.lastUpdated)

            // Store icon based on store name
            setStoreIcon()

            // Click listeners
            binding.visitStoreButton.setOnClickListener { onStoreClick(store) }
            binding.root.setOnClickListener { onStoreClick(store) }
        }

        private fun setStoreIcon() {
            // In a real app, you might have specific icons for different stores
            // For now, just use the generic store icon
            binding.storeIconImageView.setImageResource(R.drawable.ic_store)
        }

        private fun getRelativeTimeString(date: Date): String {
            val now = Date()
            val diffInMillis = now.time - date.time
            val diffInDays = TimeUnit.MILLISECONDS.toDays(diffInMillis)
            val diffInHours = TimeUnit.MILLISECONDS.toHours(diffInMillis)
            val diffInMinutes = TimeUnit.MILLISECONDS.toMinutes(diffInMillis)

            return when {
                diffInDays > 0 -> {
                    if (diffInDays == 1L) "Updated 1 day ago" else "Updated $diffInDays days ago"
                }
                diffInHours > 0 -> {
                    if (diffInHours == 1L) "Updated 1 hour ago" else "Updated $diffInHours hours ago"
                }
                diffInMinutes > 0 -> {
                    if (diffInMinutes == 1L) "Updated 1 minute ago" else "Updated $diffInMinutes minutes ago"
                }
                else -> "Updated just now"
            }
        }
    }

    class StoreDiffCallback : DiffUtil.ItemCallback<BookStore>() {
        override fun areItemsTheSame(oldItem: BookStore, newItem: BookStore): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: BookStore, newItem: BookStore): Boolean {
            return oldItem == newItem
        }
    }
} 