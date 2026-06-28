package com.booktracker.booksidntneed.ui.dialog

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.LifecycleOwner
import com.booktracker.booksidntneed.R
import com.booktracker.booksidntneed.model.BookStore
import com.booktracker.booksidntneed.model.BookWithStores
import com.booktracker.booksidntneed.model.Category
import com.booktracker.booksidntneed.repository.BookRepository
import com.booktracker.booksidntneed.ui.MainViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class DialogManager(
    private val fragmentManager: FragmentManager,
    private val viewModel: MainViewModel,
    private val context: Context,
    private val layoutInflater: LayoutInflater
) {
    fun showBookOptionsDialogFragment(bookWithStores: BookWithStores) {
        val dialogFragment = BookOptionsDialogFragment.newInstance(bookWithStores)
        dialogFragment.show(fragmentManager, "book_options_dialog")
    }

    fun showCategorySelection(bookId: Long) {
        val currentBooks = viewModel.filteredBooks.value ?: emptyList()
        val bookWithStores = currentBooks.find { it.book.id == bookId }
        val currentCategory = bookWithStores?.book?.category
        val bookTitle = bookWithStores?.book?.title ?: "Unknown Book"
        showEnhancedCategoryDialog(
            title = "Change Category",
            bookTitle = bookTitle,
            currentCategoryName = currentCategory,
            onCategorySelected = { category ->
                viewModel.updateBookCategory(bookId, category.name)
            }
        )
    }

    fun showStoreEditDialogFragment(store: BookStore, bookWithStores: BookWithStores) {
        val hasMultipleStores = bookWithStores.stores.size > 1
        val dialogFragment = StoreEditDialogFragment.newInstance(bookWithStores.book, store, hasMultipleStores)
        dialogFragment.show(fragmentManager, "store_edit_dialog")
    }

    fun showSortOptions() {
        val currentSort = when (viewModel.currentSortOrder.value) {
            MainViewModel.SortOrder.TITLE_ASC -> "title"
            MainViewModel.SortOrder.TITLE_DESC -> "title"
            MainViewModel.SortOrder.AUTHOR_ASC -> "author"
            MainViewModel.SortOrder.AUTHOR_DESC -> "author"
            MainViewModel.SortOrder.DATE_ADDED_ASC -> "date"
            MainViewModel.SortOrder.DATE_ADDED_DESC -> "date"
            MainViewModel.SortOrder.PRICE_ASC -> "price"
            MainViewModel.SortOrder.PRICE_DESC -> "price"
            else -> "date"
        }
        val dialogFragment = SortDialogFragment.newInstance(currentSort)
        dialogFragment.setOnSortOptionSelectedListener { sortOption ->
            val sortOrder = when (sortOption) {
                "title" -> MainViewModel.SortOrder.TITLE_ASC
                "author" -> MainViewModel.SortOrder.AUTHOR_ASC
                "date" -> MainViewModel.SortOrder.DATE_ADDED_DESC
                "price" -> MainViewModel.SortOrder.PRICE_ASC
                else -> MainViewModel.SortOrder.DATE_ADDED_DESC
            }
            viewModel.setSortOrder(sortOrder)
            viewModel.triggerScrollToTop()
        }
        dialogFragment.show(fragmentManager, "sort_dialog")
    }

    fun showFilterOptions() {
        showEnhancedCategoryDialog(
            title = "Filter by Category",
            bookTitle = "Select a category to filter books",
            currentCategoryName = viewModel.selectedCategory.value,
            showAllOption = true,
            onCategorySelected = { category ->
                viewModel.setSelectedCategory(category.name)
            },
            onAllSelected = {
                viewModel.setSelectedCategory(null)
            }
        )
    }

    fun showSettings() {
        val dialogFragment = SettingsDialogFragment()
        dialogFragment.show(fragmentManager, "settings_dialog")
    }

    fun showImportResultDialogFragment(message: String) {
        val dialogFragment = ResultDialogFragment.newInstance(message)
        dialogFragment.show(fragmentManager, "import_result")
    }

    fun showAddBookOptionsDialog(onManualEntry: () -> Unit) {
        val options = arrayOf("Add from URL", "Add Manually")
        MaterialAlertDialogBuilder(context)
            .setTitle("Add Book")
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> {
                        // Focus on URL input should be handled by the caller
                        dialog.dismiss()
                    }
                    1 -> {
                        onManualEntry()
                        dialog.dismiss()
                    }
                }
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    fun showDuplicateCheckDialogFragment(duplicateResult: BookRepository.BookAddResult.TitleAuthorDuplicate) {
        val dialogFragment = DuplicateCheckDialogFragment.newInstance(duplicateResult)
        dialogFragment.show(fragmentManager, "duplicate_check_dialog")
    }

    fun showSellerSelectionDialogFragment(multipleSellerOptions: com.booktracker.booksidntneed.network.WebScrapingService.ScrapingResult.MultipleSellerOptions) {
        val options = ArrayList<com.booktracker.booksidntneed.network.SellerOption>(multipleSellerOptions.sellerOptions.sortedBy { it.price })
        val dialogFragment = SellerSelectionDialogFragment.newInstance(multipleSellerOptions.bookTitle, options, multipleSellerOptions)
        dialogFragment.show(fragmentManager, "seller_selection_dialog")
    }

    fun showCategoryOptionsDialogFragment(category: Category) {
        val dialogFragment = CategoryOptionsDialogFragment.newInstance(category)
        dialogFragment.show(fragmentManager, "category_options_dialog")
    }

    fun showEditCategoryDialogFragment(category: Category) {
        val dialogFragment = EditCategoryDialogFragment.newInstance(category)
        dialogFragment.show(fragmentManager, "edit_category_dialog")
    }

    fun showManualEntryDialogFragment(
        categories: List<String>,
        title: String = "",
        author: String = "",
        isbn: String = "",
        price: String = "",
        storeName: String = "Manual Entry",
        storeUrl: String = "",
        category: String = "Uncategorized"
    ) {
        val dialogFragment = ManualEntryDialogFragment.newInstance(
            title = title,
            author = author,
            isbn = isbn,
            price = price,
            storeName = storeName,
            storeUrl = storeUrl,
            category = category,
            categories = ArrayList(categories)
        )
        dialogFragment.show(fragmentManager, "manual_entry_dialog")
    }

    fun showEditBookDialogFragment(
        book: com.booktracker.booksidntneed.model.Book,
        store: BookStore,
        categories: List<String>
    ) {
        val dialogFragment = EditBookDialogFragment.newInstance(
            book = book,
            store = store,
            categories = ArrayList(categories)
        )
        dialogFragment.show(fragmentManager, "edit_book_dialog")
    }

    fun showExportOptionsDialog(
        onSave: () -> Unit,
        onShare: () -> Unit
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_export_complete, null)
        val dialog = MaterialAlertDialogBuilder(context)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialogView.findViewById<View>(R.id.saveButton).setOnClickListener {
            dialog.dismiss()
            onSave()
        }
        dialogView.findViewById<View>(R.id.shareButton).setOnClickListener {
            dialog.dismiss()
            onShare()
        }
        dialogView.findViewById<View>(R.id.cancelButton).setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun showEnhancedCategoryDialog(
        title: String,
        bookTitle: String,
        currentCategoryName: String? = null,
        showAllOption: Boolean = false,
        onCategorySelected: (Category) -> Unit,
        onAllSelected: (() -> Unit)? = null
    ) {
        val showDialog: (List<Category>) -> Unit = { categories ->
            val dialogFragment = CategorySelectionDialogFragment.newInstance(
                title = title,
                bookTitle = bookTitle,
                currentCategoryName = currentCategoryName,
                showAllOption = showAllOption,
                selectedCategory = if (showAllOption) viewModel.selectedCategory.value else null
            )
            dialogFragment.setOnCategorySelectedListener { category ->
                onCategorySelected(category)
            }
            if (onAllSelected != null) {
                dialogFragment.setOnAllSelectedListener {
                    onAllSelected()
                }
            }
            dialogFragment.setOnCategoryMenuClickListener { category ->
                showCategoryOptionsDialogFragment(category)
            }
            dialogFragment.setOnValidateCategoryNameListener { categoryName ->
                viewModel.validateCategoryName(categoryName)
            }
            dialogFragment.setOnAddCategoryListener { categoryName, color ->
                viewModel.addCustomCategory(categoryName, color)
            }
            dialogFragment.show(fragmentManager, "category_selection_dialog")
        }
        val currentCategories = viewModel.allCategories.value
        if (currentCategories == null || currentCategories.isEmpty()) {
            val observer = object : androidx.lifecycle.Observer<List<Category>> {
                override fun onChanged(value: List<Category>) {
                    if (value.isNotEmpty()) {
                        viewModel.allCategories.removeObserver(this)
                        showDialog(value)
                    }
                }
            }
            // Observe with a lifecycle-aware owner to avoid leaking the Context
            (context as? LifecycleOwner)?.let { owner ->
                viewModel.allCategories.observe(owner, observer)
            } ?: run {
                // If context is not a LifecycleOwner, show immediately to avoid holding observer
                showDialog(emptyList())
            }
        } else {
            showDialog(currentCategories)
        }
    }
} 