package com.booktracker.booksidntneed.ui.adapter

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.booktracker.booksidntneed.R
import com.booktracker.booksidntneed.databinding.ItemBookCardBinding
import com.booktracker.booksidntneed.databinding.ItemBookCardMinimalBinding
import com.booktracker.booksidntneed.model.Book
import com.booktracker.booksidntneed.model.BookStore
import com.booktracker.booksidntneed.model.BookWithStores
import com.booktracker.booksidntneed.model.Category
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import java.text.SimpleDateFormat
import java.util.Locale

class BooksAdapter(
    private val onBookClick: (BookWithStores) -> Unit,
    private val onStoreClick: (BookStore) -> Unit,
    private val onBookMenuClick: (BookWithStores) -> Unit,
    private val onCategoryClick: (BookWithStores) -> Unit
) : ListAdapter<BookWithStores, RecyclerView.ViewHolder>(BookDiffCallback()) {
    
    companion object {
        private const val VIEW_TYPE_FULL = 0
        private const val VIEW_TYPE_MINIMAL = 1
        private val DEFAULT_CATEGORY_COLOR = "#64748B".toColorInt()
    }
    
    // Map of category names to their colors
    private var categoryColors: Map<String, String> = emptyMap()
    
    // Method to update category colors
    fun updateCategoryColors(categories: List<Category>) {
        // Normalize category names to lowercase and trimmed for matching
        categoryColors = categories.associate { it.name.trim().lowercase() to (it.color ?: "#64748B") }
        if (itemCount > 0) {
            notifyItemRangeChanged(0, itemCount)
        }
    }
    
    // Helper function to get category colors - now uses dynamic colors
    private fun getCategoryColor(categoryName: String): Int {
        // Normalize input for lookup
        return categoryColors[categoryName.trim().lowercase()]?.toColorInt() ?: DEFAULT_CATEGORY_COLOR
    }
    
    private var isMinimalMode = false
    
    @SuppressLint("NotifyDataSetChanged")
    fun setMinimalMode(isMinimal: Boolean) {
        if (isMinimalMode != isMinimal) {
            isMinimalMode = isMinimal
            notifyDataSetChanged()
        }
    }

    override fun getItemViewType(position: Int): Int {
        return if (isMinimalMode) VIEW_TYPE_MINIMAL else VIEW_TYPE_FULL
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_MINIMAL -> {
                val binding = ItemBookCardMinimalBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                MinimalBookViewHolder(binding)
            }
            else -> {
                val binding = ItemBookCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                BookViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is BookViewHolder -> holder.bind(getItem(position))
            is MinimalBookViewHolder -> holder.bind(getItem(position))
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        when (holder) {
            is BookViewHolder -> {
                // Clear nested adapter to break view references
                holder.itemView.findViewById<RecyclerView?>(R.id.storesRecyclerView)?.adapter = null
                // Clear image to release resources sooner
                holder.itemView.findViewById<ImageView?>(R.id.bookCoverImageView)?.setImageDrawable(null)
            }
            is MinimalBookViewHolder -> {
                holder.itemView.findViewById<ImageView?>(R.id.bookCoverImageView)?.setImageDrawable(null)
            }
        }
    }

    // Helper function to apply category colors to chips
    private fun applyCategoryColor(chip: Chip, categoryName: String) {
        try {
            val categoryColor = getCategoryColor(categoryName)

            // Use the actual category color as background
            chip.chipBackgroundColor = ColorStateList.valueOf(categoryColor)

            // Use white text for good contrast against the colored background
            chip.setTextColor(Color.WHITE)

        } catch (_: Exception) {
            // Fallback to default Material colors if anything goes wrong
            // This will use the existing colorSecondaryContainer styling
        }
    }

    private fun showCopyOptionsSheet(anchorView: View, book: Book) {
        val context = anchorView.context
        val sheetView = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_copy_book_details, null)
        val dialog = BottomSheetDialog(context)

        sheetView.findViewById<TextView>(R.id.copySheetSubtitleTextView).text = book.title

        setupCopyRow(dialog, sheetView, R.id.copyTitleRow, R.id.copyTitleValueTextView, "Book Title", book.title)
        setupCopyRow(dialog, sheetView, R.id.copyAuthorRow, R.id.copyAuthorValueTextView, "Author", book.author)
        setupOptionalCopyRow(
            dialog,
            sheetView,
            R.id.copyIsbn13Row,
            R.id.copyIsbn13ValueTextView,
            R.id.copyIsbn13Divider,
            "ISBN-13",
            book.isbn13
        )
        setupOptionalCopyRow(
            dialog,
            sheetView,
            R.id.copyIsbn10Row,
            R.id.copyIsbn10ValueTextView,
            R.id.copyIsbn10Divider,
            "ISBN-10",
            book.isbn10
        )

        dialog.setContentView(sheetView)
        dialog.show()
    }

    private fun setupOptionalCopyRow(
        dialog: BottomSheetDialog,
        sheetView: View,
        rowId: Int,
        valueTextViewId: Int,
        dividerId: Int,
        label: String,
        value: String?
    ) {
        if (value.isNullOrBlank()) {
            sheetView.findViewById<View>(rowId).visibility = View.GONE
            sheetView.findViewById<View>(dividerId).visibility = View.GONE
        } else {
            setupCopyRow(dialog, sheetView, rowId, valueTextViewId, label, value)
        }
    }

    private fun setupCopyRow(
        dialog: BottomSheetDialog,
        sheetView: View,
        rowId: Int,
        valueTextViewId: Int,
        label: String,
        value: String
    ) {
        sheetView.findViewById<TextView>(valueTextViewId).text = value
        sheetView.findViewById<View>(rowId).setOnClickListener {
            copyToClipboard(it.context, label, value)
            dialog.dismiss()
        }
    }

    private fun copyToClipboard(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "$label copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    inner class BookViewHolder(private val binding: ItemBookCardBinding) : RecyclerView.ViewHolder(binding.root) {
        
        private var storesAdapter: StoresAdapter? = null
        private var isStoresExpanded = false

        fun bind(bookWithStores: BookWithStores) {
            val book = bookWithStores.book

            // Reset expanded state for recycled view holders
            isStoresExpanded = false
            binding.storesRecyclerView.visibility = View.GONE

            // Basic book info
            binding.bookTitleTextView.text = book.title
            binding.bookAuthorTextView.text = book.author
            binding.categoryChip.text = book.category
            
            // Apply category color to the chip
            applyCategoryColor(binding.categoryChip, book.category)
            
            // ISBN information
            setupIsbnInfo(book)

            // Load cover image
            if (!book.coverImageUrl.isNullOrBlank()) {
                Glide.with(binding.bookCoverImageView)
                    .load(book.coverImageUrl)
                    .placeholder(R.drawable.ic_book_placeholder)
                    .error(R.drawable.ic_book_placeholder)
                    .into(binding.bookCoverImageView)
            } else {
                // If no cover image URL, just show the placeholder
                binding.bookCoverImageView.setImageResource(R.drawable.ic_book_placeholder)
            }

            // Price information
            setupPriceInfo(bookWithStores)

            // Store count and expansion
            setupStoreInfo(bookWithStores)

            // Date added
            val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
            binding.dateAddedTextView.text = binding.root.context.getString(
                R.string.book_date_added_label,
                dateFormat.format(book.dateAdded)
            )

            // Click listeners
            binding.root.setOnClickListener { onBookClick(bookWithStores) }
            binding.bookMenuButton.setOnClickListener { onBookMenuClick(bookWithStores) }
            binding.categoryChip.setOnClickListener { onCategoryClick(bookWithStores) }
            
            // Long press listeners for copying text
            setupTextCopyListeners(bookWithStores)
            
            // View stores button
            binding.viewStoresButton.setOnClickListener {
                if (bookWithStores.stores.size == 1) {
                    // For single store, go directly to store
                    onStoreClick(bookWithStores.getStoresSortedByPrice().first())
                } else {
                    // For multiple stores, toggle the expanded view
                    toggleStoresVisibility(bookWithStores)
                }
            }
        }

        @SuppressLint("DefaultLocale")
        private fun setupPriceInfo(bookWithStores: BookWithStores) {
            val lowestPrice = bookWithStores.getLowestPrice()
            val highestPrice = bookWithStores.getHighestPrice()

            when (lowestPrice) {
                null -> {
                    binding.priceTextView.text = binding.root.context.getString(R.string.no_price_available)
                }
                highestPrice -> {
                    binding.priceTextView.text = String.format("$%.2f", lowestPrice)
                }
                else -> {
                    binding.priceTextView.text = String.format("$%.2f - $%.2f", lowestPrice, highestPrice)
                }
            }
        }

        private fun setupStoreInfo(bookWithStores: BookWithStores) {
            when (val storeCount = bookWithStores.stores.size) {
                0 -> {
                    binding.storeCountTextView.text = "No stores available"
                    binding.viewStoresButton.visibility = View.GONE
                }
                1 -> {
                    binding.storeCountTextView.text = "Available at ${bookWithStores.getStoresSortedByPrice().first().storeName}"
                    binding.viewStoresButton.text = "Visit Store"
                    binding.viewStoresButton.visibility = View.VISIBLE
                }
                else -> {
                    binding.storeCountTextView.text = "Available at $storeCount stores"
                    binding.viewStoresButton.text = "View All Stores"
                    binding.viewStoresButton.visibility = View.VISIBLE
                }
            }

            // Setup stores adapter
            if (storesAdapter == null) {
                storesAdapter = StoresAdapter(
                    onStoreClick = onStoreClick
                )
            }
            binding.storesRecyclerView.adapter = storesAdapter
            storesAdapter?.submitList(bookWithStores.getStoresSortedByPrice())
        }

        private fun setupIsbnInfo(book: com.booktracker.booksidntneed.model.Book) {
            val isbn13 = book.isbn13
            val isbn10 = book.isbn10
            
            when {
                !isbn13.isNullOrBlank() && !isbn10.isNullOrBlank() -> {
                    // Show both ISBNs in a compact format
                    binding.bookIsbnTextView.text = "ISBN-13: $isbn13\nISBN-10: $isbn10"
                    binding.bookIsbnTextView.visibility = View.VISIBLE
                }
                !isbn13.isNullOrBlank() -> {
                    binding.bookIsbnTextView.text = "ISBN-13: $isbn13"
                    binding.bookIsbnTextView.visibility = View.VISIBLE
                }
                !isbn10.isNullOrBlank() -> {
                    binding.bookIsbnTextView.text = "ISBN-10: $isbn10"
                    binding.bookIsbnTextView.visibility = View.VISIBLE
                }
                else -> {
                    binding.bookIsbnTextView.visibility = View.GONE
                }
            }
        }

        private fun toggleStoresVisibility(bookWithStores: BookWithStores) {
            isStoresExpanded = !isStoresExpanded
            binding.storesRecyclerView.visibility = if (isStoresExpanded) View.VISIBLE else View.GONE
            
            if (isStoresExpanded) {
                binding.viewStoresButton.text = "Hide Stores"
            } else {
                // When collapsing, restore the correct button text based on store count
                val storeCount = bookWithStores.stores.size
                binding.viewStoresButton.text = if (storeCount == 1) "Visit Store" else "View All Stores"
            }
        }
        
        private fun setupTextCopyListeners(bookWithStores: BookWithStores) {
            val book = bookWithStores.book
            
            // Long press on title
            binding.bookTitleTextView.setOnLongClickListener {
                showCopyOptionsSheet(it, book)
                true
            }
            
            // Long press on author
            binding.bookAuthorTextView.setOnLongClickListener {
                showCopyOptionsSheet(it, book)
                true
            }
            
            // Long press on ISBN
            binding.bookIsbnTextView.setOnLongClickListener {
                showCopyOptionsSheet(it, book)
                true
            }
        }
    }

    inner class MinimalBookViewHolder(private val binding: ItemBookCardMinimalBinding) : RecyclerView.ViewHolder(binding.root) {
        
        private var storesAdapter: StoresAdapter? = null
        private var isExpanded = false

        fun bind(bookWithStores: BookWithStores) {
            val book = bookWithStores.book

            // Reset expanded state for recycled view holders
            isExpanded = false
            binding.expandedLayout.visibility = View.GONE
            binding.minimalLayout.visibility = View.VISIBLE

            // Basic book info in minimal layout
            binding.bookTitleTextView.text = book.title
            binding.bookAuthorTextView.text = book.author
            binding.categoryChip.text = book.category
            
            // Apply category color to the chip
            applyCategoryColor(binding.categoryChip, book.category)

            // Load cover image for minimal layout
            loadCoverImage(book.coverImageUrl, binding.bookCoverImageView)

            // Price information
            setupMinimalPriceInfo(bookWithStores)

            // Store count
            setupMinimalStoreInfo(bookWithStores)

            // Click listeners
            binding.minimalLayout.setOnClickListener { 
                toggleExpandedState(bookWithStores)
            }
            binding.bookMenuButton.setOnClickListener { onBookMenuClick(bookWithStores) }
            binding.categoryChip.setOnClickListener { onCategoryClick(bookWithStores) }
            
            // Long press listeners for copying text
            setupMinimalTextCopyListeners(bookWithStores)
        }

        private fun loadCoverImage(coverImageUrl: String?, imageView: ImageView) {
            if (!coverImageUrl.isNullOrBlank()) {
                Glide.with(imageView)
                    .load(coverImageUrl)
                    .placeholder(R.drawable.ic_book_placeholder)
                    .error(R.drawable.ic_book_placeholder)
                    .into(imageView)
            } else {
                imageView.setImageResource(R.drawable.ic_book_placeholder)
            }
        }

        private fun setupMinimalPriceInfo(bookWithStores: BookWithStores) {
            val lowestPrice = bookWithStores.getLowestPrice()
            val highestPrice = bookWithStores.getHighestPrice()

            when (lowestPrice) {
                null -> {
                    binding.priceTextView.text = "N/A"
                }
                highestPrice -> {
                    binding.priceTextView.text = String.format("$%.2f", lowestPrice)
                }
                else -> {
                    binding.priceTextView.text = String.format("$%.2f-$%.2f", lowestPrice, highestPrice)
                }
            }
        }

        private fun setupMinimalStoreInfo(bookWithStores: BookWithStores) {
            when (val storeCount = bookWithStores.stores.size) {
                0 -> {
                    binding.storeCountTextView.text = "No stores"
                }
                1 -> {
                    binding.storeCountTextView.text = "1 store"
                }
                else -> {
                    binding.storeCountTextView.text = "$storeCount stores"
                }
            }
        }

        private fun toggleExpandedState(bookWithStores: BookWithStores) {
            isExpanded = !isExpanded
            
            if (isExpanded) {
                // Show expanded layout
                expandCard(bookWithStores)
            } else {
                // Show minimal layout
                collapseCard()
            }
        }

        private fun expandCard(bookWithStores: BookWithStores) {
            val book = bookWithStores.book
            
            // Hide minimal layout and show expanded layout
            binding.minimalLayout.visibility = View.GONE
            binding.expandedLayout.visibility = View.VISIBLE
            
            // Setup expanded layout data (like normal card)
            binding.expandedBookTitleTextView.text = book.title
            binding.expandedBookAuthorTextView.text = book.author
            binding.expandedCategoryChip.text = book.category
            
            // Apply category color to the chip
            applyCategoryColor(binding.expandedCategoryChip, book.category)
            
            // Load cover image for expanded layout
            loadCoverImage(book.coverImageUrl, binding.expandedBookCoverImageView)
            
            // Setup ISBN info
            setupExpandedIsbnInfo(book)
            
            // Setup price info for expanded layout
            setupExpandedPriceInfo(bookWithStores)
            
            // Setup store info for expanded layout
            setupExpandedStoreInfo(bookWithStores)
            
            // Date added
            val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
            binding.dateAddedTextView.text = binding.root.context.getString(
                R.string.book_date_added_label,
                dateFormat.format(book.dateAdded)
            )
            
            // Click listeners for expanded layout
            binding.expandedBookMenuButton.setOnClickListener { onBookMenuClick(bookWithStores) }
            binding.expandedCategoryChip.setOnClickListener { onCategoryClick(bookWithStores) }
            binding.expandedLayout.setOnClickListener { 
                toggleExpandedState(bookWithStores)
            }
            
            // Long press listeners for expanded layout
            setupExpandedTextCopyListeners(bookWithStores)

            // Animate expansion
            binding.root.animate()
                .scaleY(1.02f)
                .setDuration(200)
                .withEndAction {
                    binding.root.animate()
                        .scaleY(1.0f)
                        .setDuration(100)
                        .start()
                }
                .start()
        }

        private fun setupExpandedIsbnInfo(book: com.booktracker.booksidntneed.model.Book) {
            val isbn13 = book.isbn13
            val isbn10 = book.isbn10
            
            when {
                !isbn13.isNullOrBlank() && !isbn10.isNullOrBlank() -> {
                    binding.bookIsbnTextView.text = "ISBN-13: $isbn13\nISBN-10: $isbn10"
                    binding.bookIsbnTextView.visibility = View.VISIBLE
                }
                !isbn13.isNullOrBlank() -> {
                    binding.bookIsbnTextView.text = "ISBN-13: $isbn13"
                    binding.bookIsbnTextView.visibility = View.VISIBLE
                }
                !isbn10.isNullOrBlank() -> {
                    binding.bookIsbnTextView.text = "ISBN-10: $isbn10"
                    binding.bookIsbnTextView.visibility = View.VISIBLE
                }
                else -> {
                    binding.bookIsbnTextView.visibility = View.GONE
                }
            }
        }

        private fun setupExpandedPriceInfo(bookWithStores: BookWithStores) {
            val lowestPrice = bookWithStores.getLowestPrice()
            val highestPrice = bookWithStores.getHighestPrice()

            when (lowestPrice) {
                null -> {
                    binding.expandedPriceTextView.text = binding.root.context.getString(R.string.no_price_available)
                }
                highestPrice -> {
                    binding.expandedPriceTextView.text = String.format("$%.2f", lowestPrice)
                }
                else -> {
                    binding.expandedPriceTextView.text = String.format("$%.2f - $%.2f", lowestPrice, highestPrice)
                }
            }
        }

        private fun setupExpandedStoreInfo(bookWithStores: BookWithStores) {
            when (val storeCount = bookWithStores.stores.size) {
                0 -> {
                    binding.expandedStoreCountTextView.text = "No stores available"
                    binding.viewStoresButton.visibility = View.GONE
                }
                1 -> {
                    binding.expandedStoreCountTextView.text = "Available at ${bookWithStores.getStoresSortedByPrice().first().storeName}"
                    binding.viewStoresButton.text = "Visit Store"
                    binding.viewStoresButton.visibility = View.VISIBLE

                    binding.viewStoresButton.setOnClickListener {
                        onStoreClick(bookWithStores.getStoresSortedByPrice().first())
                    }
                }
                else -> {
                    binding.expandedStoreCountTextView.text = "Available at $storeCount stores"
                    binding.viewStoresButton.text = "View All Stores"
                    binding.viewStoresButton.visibility = View.VISIBLE

                    binding.viewStoresButton.setOnClickListener {
                        toggleStoresVisibility(bookWithStores)
                    }
                }
            }

            // Setup stores adapter
            if (storesAdapter == null) {
                storesAdapter = StoresAdapter(
                    onStoreClick = onStoreClick
                )
            }
            binding.storesRecyclerView.adapter = storesAdapter
            storesAdapter?.submitList(bookWithStores.getStoresSortedByPrice())
        }

        private fun toggleStoresVisibility(bookWithStores: BookWithStores) {
            val isStoresExpanded = binding.storesRecyclerView.isVisible
            binding.storesRecyclerView.visibility = if (isStoresExpanded) View.GONE else View.VISIBLE
            
            if (isStoresExpanded) {
                binding.viewStoresButton.text = "View All Stores"
            } else {
                binding.viewStoresButton.text = "Hide Stores"
            }
        }

        private fun collapseCard() {
            // Hide expanded layout and show minimal layout
            binding.expandedLayout.visibility = View.GONE
            binding.minimalLayout.visibility = View.VISIBLE

            // Animate collapse
            binding.root.animate()
                .scaleY(0.98f)
                .setDuration(150)
                .withEndAction {
                    binding.root.animate()
                        .scaleY(1.0f)
                        .setDuration(100)
                        .start()
                }
                .start()
        }
        
        private fun setupMinimalTextCopyListeners(bookWithStores: BookWithStores) {
            val book = bookWithStores.book
            
            // Long press on title (minimal layout)
            binding.bookTitleTextView.setOnLongClickListener {
                showCopyOptionsSheet(it, book)
                true
            }
            
            // Long press on author (minimal layout)
            binding.bookAuthorTextView.setOnLongClickListener {
                showCopyOptionsSheet(it, book)
                true
            }
        }
        
        private fun setupExpandedTextCopyListeners(bookWithStores: BookWithStores) {
            val book = bookWithStores.book
            
            // Long press on title (expanded layout)
            binding.expandedBookTitleTextView.setOnLongClickListener {
                showCopyOptionsSheet(it, book)
                true
            }
            
            // Long press on author (expanded layout)
            binding.expandedBookAuthorTextView.setOnLongClickListener {
                showCopyOptionsSheet(it, book)
                true
            }
            
            // Long press on ISBN (expanded layout)
            binding.bookIsbnTextView.setOnLongClickListener {
                showCopyOptionsSheet(it, book)
                true
            }
        }
    }

    class BookDiffCallback : DiffUtil.ItemCallback<BookWithStores>() {
        override fun areItemsTheSame(oldItem: BookWithStores, newItem: BookWithStores): Boolean {
            return oldItem.book.id == newItem.book.id
        }

        override fun areContentsTheSame(oldItem: BookWithStores, newItem: BookWithStores): Boolean {
            return oldItem == newItem
        }
    }
} 
