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
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
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
        private const val DESKTOP_FIREFOX_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:122.0) Gecko/20100101 Firefox/122.0"
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
                holder.itemView.findViewById<ImageView?>(R.id.bookCoverImageView)?.let { imageView ->
                    Glide.with(imageView).clear(imageView)
                }
            }
            is MinimalBookViewHolder -> {
                holder.itemView.findViewById<ImageView?>(R.id.bookCoverImageView)?.let { imageView ->
                    Glide.with(imageView).clear(imageView)
                }
                holder.itemView.findViewById<ImageView?>(R.id.expandedBookCoverImageView)?.let { imageView ->
                    Glide.with(imageView).clear(imageView)
                }
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
        val parent = anchorView.rootView as ViewGroup
        val sheetView = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_copy_book_details, parent, false)
        val dialog = BottomSheetDialog(context)

        sheetView.findViewById<TextView>(R.id.copySheetSubtitleTextView).text = book.title

        setupCopyRow(
            dialog,
            sheetView,
            R.id.copyTitleRow,
            R.id.copyTitleValueTextView,
            context.getString(R.string.copy_book_title_label),
            book.title
        )
        setupCopyRow(
            dialog,
            sheetView,
            R.id.copyAuthorRow,
            R.id.copyAuthorValueTextView,
            context.getString(R.string.copy_author_label),
            book.author
        )
        setupOptionalCopyRow(
            dialog,
            sheetView,
            R.id.copyIsbn13Row,
            R.id.copyIsbn13ValueTextView,
            R.id.copyIsbn13Divider,
            context.getString(R.string.copy_isbn_13_label),
            book.isbn13
        )
        setupOptionalCopyRow(
            dialog,
            sheetView,
            R.id.copyIsbn10Row,
            R.id.copyIsbn10ValueTextView,
            R.id.copyIsbn10Divider,
            context.getString(R.string.copy_isbn_10_label),
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
        Toast.makeText(context, context.getString(R.string.copied_to_clipboard, label), Toast.LENGTH_SHORT).show()
    }

    private fun loadCoverImage(coverImageUrl: String?, imageView: ImageView) {
        if (!coverImageUrl.isNullOrBlank()) {
            Glide.with(imageView)
                .load(buildCoverImageModel(coverImageUrl))
                .placeholder(R.drawable.ic_book_placeholder)
                .error(R.drawable.ic_book_placeholder)
                .into(imageView)
        } else {
            imageView.setImageResource(R.drawable.ic_book_placeholder)
        }
    }

    private fun buildCoverImageModel(coverImageUrl: String): Any {
        if (!isBooksAMillionCoverUrl(coverImageUrl)) {
            return coverImageUrl
        }

        return GlideUrl(
            coverImageUrl,
            LazyHeaders.Builder()
                .addHeader("User-Agent", DESKTOP_FIREFOX_USER_AGENT)
                .addHeader("Accept", "image/avif,image/webp,*/*")
                .addHeader("Accept-Language", "en-US,en;q=0.5")
                .addHeader("Referer", "https://www.booksamillion.com/")
                .build()
        )
    }

    private fun isBooksAMillionCoverUrl(url: String): Boolean {
        return url.contains("booksamillion.com", ignoreCase = true) &&
            url.contains("/covers/", ignoreCase = true)
    }

    private fun Context.getPriceText(lowestPrice: Double?, highestPrice: Double?, compact: Boolean = false): String {
        return when (lowestPrice) {
            null -> getString(if (compact) R.string.no_price_available_short else R.string.no_price_available)
            highestPrice -> getString(R.string.book_price, lowestPrice)
            else -> getString(
                if (compact) R.string.book_price_range_compact else R.string.book_price_range,
                lowestPrice,
                highestPrice
            )
        }
    }

    private fun TextView.setIsbnText(isbn13: String?, isbn10: String?) {
        text = when {
            !isbn13.isNullOrBlank() && !isbn10.isNullOrBlank() -> {
                context.getString(R.string.isbn_both_label, isbn13, isbn10)
            }
            !isbn13.isNullOrBlank() -> {
                context.getString(R.string.isbn_13_label, isbn13)
            }
            !isbn10.isNullOrBlank() -> {
                context.getString(R.string.isbn_10_label, isbn10)
            }
            else -> {
                visibility = View.GONE
                return
            }
        }
        visibility = View.VISIBLE
    }

    private fun BookWithStores.getStoresForDisplay(): List<BookStore> {
        return if (stores.size <= 1) stores else getStoresSortedByPrice()
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
            loadCoverImage(book.coverImageUrl, binding.bookCoverImageView)

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
                    onStoreClick(bookWithStores.stores.first())
                } else {
                    // For multiple stores, toggle the expanded view
                    toggleStoresVisibility(bookWithStores)
                }
            }
        }

        private fun setupPriceInfo(bookWithStores: BookWithStores) {
            val priceSummary = bookWithStores.getPriceSummary()

            binding.priceTextView.text = binding.root.context.getPriceText(priceSummary.lowestPrice, priceSummary.highestPrice)
        }

        private fun setupStoreInfo(bookWithStores: BookWithStores) {
            when (val storeCount = bookWithStores.stores.size) {
                0 -> {
                    binding.storeCountTextView.setText(R.string.no_stores_available)
                    binding.viewStoresButton.visibility = View.GONE
                }
                1 -> {
                    binding.storeCountTextView.text = binding.root.context.getString(
                        R.string.available_at_store,
                        bookWithStores.stores.first().storeName
                    )
                    binding.viewStoresButton.setText(R.string.visit_store)
                    binding.viewStoresButton.visibility = View.VISIBLE
                }
                else -> {
                    binding.storeCountTextView.text = binding.root.context.getString(R.string.available_at_store_count, storeCount)
                    binding.viewStoresButton.setText(R.string.view_stores)
                    binding.viewStoresButton.visibility = View.VISIBLE
                }
            }
        }

        private fun setupIsbnInfo(book: Book) {
            binding.bookIsbnTextView.setIsbnText(book.isbn13, book.isbn10)
        }

        private fun toggleStoresVisibility(bookWithStores: BookWithStores) {
            isStoresExpanded = !isStoresExpanded
            binding.storesRecyclerView.visibility = if (isStoresExpanded) View.VISIBLE else View.GONE
            
            if (isStoresExpanded) {
                ensureStoresAdapter()
                storesAdapter?.submitList(bookWithStores.getStoresForDisplay())
                binding.viewStoresButton.setText(R.string.hide_stores)
            } else {
                // When collapsing, restore the correct button text based on store count
                val storeCount = bookWithStores.stores.size
                binding.viewStoresButton.setText(if (storeCount == 1) R.string.visit_store else R.string.view_stores)
            }
        }

        private fun ensureStoresAdapter() {
            if (storesAdapter == null) {
                storesAdapter = StoresAdapter(
                    onStoreClick = onStoreClick
                )
            }
            if (binding.storesRecyclerView.adapter == null) {
                binding.storesRecyclerView.adapter = storesAdapter
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

        private fun setupMinimalPriceInfo(bookWithStores: BookWithStores) {
            val priceSummary = bookWithStores.getPriceSummary()

            binding.priceTextView.text = binding.root.context.getPriceText(
                priceSummary.lowestPrice,
                priceSummary.highestPrice,
                compact = true
            )
        }

        private fun setupMinimalStoreInfo(bookWithStores: BookWithStores) {
            when (val storeCount = bookWithStores.stores.size) {
                0 -> {
                    binding.storeCountTextView.setText(R.string.no_stores)
                }
                1 -> {
                    binding.storeCountTextView.text = binding.root.resources.getQuantityString(
                        R.plurals.store_count_short,
                        storeCount,
                        storeCount
                    )
                }
                else -> {
                    binding.storeCountTextView.text = binding.root.resources.getQuantityString(
                        R.plurals.store_count_short,
                        storeCount,
                        storeCount
                    )
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

        private fun setupExpandedIsbnInfo(book: Book) {
            binding.bookIsbnTextView.setIsbnText(book.isbn13, book.isbn10)
        }

        private fun setupExpandedPriceInfo(bookWithStores: BookWithStores) {
            val priceSummary = bookWithStores.getPriceSummary()

            binding.expandedPriceTextView.text = binding.root.context.getPriceText(
                priceSummary.lowestPrice,
                priceSummary.highestPrice
            )
        }

        private fun setupExpandedStoreInfo(bookWithStores: BookWithStores) {
            binding.storesRecyclerView.visibility = View.GONE

            when (val storeCount = bookWithStores.stores.size) {
                0 -> {
                    binding.expandedStoreCountTextView.setText(R.string.no_stores_available)
                    binding.viewStoresButton.visibility = View.GONE
                }
                1 -> {
                    binding.expandedStoreCountTextView.text = binding.root.context.getString(
                        R.string.available_at_store,
                        bookWithStores.stores.first().storeName
                    )
                    binding.viewStoresButton.setText(R.string.visit_store)
                    binding.viewStoresButton.visibility = View.VISIBLE

                    binding.viewStoresButton.setOnClickListener {
                        onStoreClick(bookWithStores.stores.first())
                    }
                }
                else -> {
                    binding.expandedStoreCountTextView.text = binding.root.context.getString(R.string.available_at_store_count, storeCount)
                    binding.viewStoresButton.setText(R.string.view_stores)
                    binding.viewStoresButton.visibility = View.VISIBLE

                    binding.viewStoresButton.setOnClickListener {
                        toggleStoresVisibility(bookWithStores)
                    }
                }
            }
        }

        private fun toggleStoresVisibility(bookWithStores: BookWithStores) {
            val isStoresExpanded = binding.storesRecyclerView.isVisible
            binding.storesRecyclerView.visibility = if (isStoresExpanded) View.GONE else View.VISIBLE
            
            if (isStoresExpanded) {
                binding.viewStoresButton.setText(R.string.view_stores)
            } else {
                ensureStoresAdapter()
                storesAdapter?.submitList(bookWithStores.getStoresForDisplay())
                binding.viewStoresButton.setText(R.string.hide_stores)
            }
        }

        private fun ensureStoresAdapter() {
            if (storesAdapter == null) {
                storesAdapter = StoresAdapter(
                    onStoreClick = onStoreClick
                )
            }
            if (binding.storesRecyclerView.adapter == null) {
                binding.storesRecyclerView.adapter = storesAdapter
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
