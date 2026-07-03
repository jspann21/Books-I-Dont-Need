package com.booktracker.booksidntneed.ui

import android.app.Application
import android.os.Parcelable
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.booktracker.booksidntneed.R
import com.booktracker.booksidntneed.model.Book
import com.booktracker.booksidntneed.model.BookStore
import com.booktracker.booksidntneed.model.BookWithStores
import com.booktracker.booksidntneed.model.Category
import com.booktracker.booksidntneed.model.EditBookData
import com.booktracker.booksidntneed.model.ManualEntryData
import com.booktracker.booksidntneed.network.ParsedBookInfo
import com.booktracker.booksidntneed.network.ScrapingProgressCallback
import com.booktracker.booksidntneed.network.WebScrapingService
import com.booktracker.booksidntneed.repository.BookRepository
import com.booktracker.booksidntneed.repository.CategoryManager
import com.booktracker.booksidntneed.utils.ErrorReporter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

sealed class ConfirmationRequest {
    data class DeleteBook(val bookWithStores: BookWithStores) : ConfirmationRequest()
    data class DeleteStore(val store: BookStore) : ConfirmationRequest()
    data class DeleteCategory(val category: Category) : ConfirmationRequest()
    data class UpdatePrices(val bookWithStores: BookWithStores) : ConfirmationRequest()
}

sealed class ViewEvent {
    data class ShowDuplicateCheck(val duplicateResult: BookRepository.BookAddResult.TitleAuthorDuplicate) : ViewEvent()
    data class ShowSellerSelection(val options: WebScrapingService.ScrapingResult.MultipleSellerOptions) : ViewEvent()
    data class ShowManualEntry(val data: ManualEntryData) : ViewEvent()
    data class ShowEditBook(val data: EditBookData) : ViewEvent()
}

enum class MessageType { SUCCESS, ERROR }

data class UiMessage(
    @param:StringRes val resId: Int,
    val type: MessageType,
    val formatArgs: List<Any> = emptyList()
)



class MainViewModel(private val repository: BookRepository, private val app: Application) : ViewModel() {
    
    companion object {
        // Use null as a sentinel value for "All Categories" to avoid hardcoded strings
        // The UI layer is responsible for displaying the localized string
        private val ALL_CATEGORIES_SENTINEL: String? = null
    }
    

    
    // Exported data state to survive configuration changes
    data class ExportedData(val csvData: String, val tempFile: java.io.File)
    private val _lastExportedData = MutableLiveData<ExportedData?>()
    val lastExportedData: LiveData<ExportedData?> = _lastExportedData
    
    // Scroll target data class to handle different scroll behaviors
    data class ScrollTarget(
        val bookId: Long? = null,  // null means scroll to top
        val shouldScroll: Boolean = false
    )
    
    // Price update progress tracking
    @Parcelize
    data class StoreUpdateProgress(
        val storeId: Long,
        val storeName: String,
        val status: StoreUpdateStatus,
        val errorMessage: String? = null,
        val oldPrice: Double? = null,
        val newPrice: Double? = null,
        val currentTask: StoreUpdateTask = StoreUpdateTask.PENDING,
        val taskProgress: Int = 0, // 0-100 for current task
        val totalTasks: Int = 6 // Total number of tasks per store
    ) : Parcelable
    
    @Parcelize
    enum class StoreUpdateStatus : Parcelable {
        PENDING,     // Waiting to be processed
        UPDATING,    // Currently being scraped
        SUCCESS,     // Successfully updated
        FAILED,      // Failed to update
        SKIPPED;     // Skipped (e.g., no URL to scrape)
    }
    
    @Parcelize
    enum class StoreUpdateTask : Parcelable {
        PENDING,           // Initial state
        VALIDATING_URL,    // Validating URL format
        ESTABLISHING_SESSION, // Setting up session for certain stores
        FETCHING_DOCUMENT, // Downloading webpage with retries
        PARSING_CONTENT,   // Parsing HTML to extract book info
        VALIDATING_DATA,   // Validating extracted data
        UPDATING_DATABASE, // Saving to database
        COMPLETED;         // Task completed
    }
    
    @Parcelize
    data class PriceUpdateProgressState(
        val isActive: Boolean = false,
        val currentStoreIndex: Int = 0,
        val totalStores: Int = 0,
        val stores: List<StoreUpdateProgress> = emptyList(),
        val isComplete: Boolean = false,
        val bookTitle: String = "",
        val overallProgress: Int = 0, // 0-100 overall progress across all stores and tasks
        val totalTasks: Int = 0, // Total tasks across all stores
        val completedTasks: Int = 0 // Completed tasks across all stores
    ) : Parcelable
    
    // Detailed status for better user feedback
    data class DetailedStatus(
        val message: String,
        val subMessage: String? = null,
        val progress: Int? = null, // 0-100
        val isIndeterminate: Boolean = true
    )
    
    // UI State
    private val _loadingState = MutableLiveData<LoadingState>()
    val loadingState: LiveData<LoadingState> = _loadingState
    
    private val _detailedStatus = MutableLiveData<DetailedStatus?>()
    val detailedStatus: LiveData<DetailedStatus?> = _detailedStatus

    // --- REFACTORED: Transient events as SharedFlow ---
    private val _scrollTarget = MutableSharedFlow<ScrollTarget>(extraBufferCapacity = 1)
    val scrollTarget: SharedFlow<ScrollTarget> = _scrollTarget.asSharedFlow()

    private val _uiMessage = MutableSharedFlow<UiMessage>(extraBufferCapacity = 1)
    val uiMessage: SharedFlow<UiMessage> = _uiMessage.asSharedFlow()

    private val _viewEvent = MutableSharedFlow<ViewEvent>(extraBufferCapacity = 1)
    val viewEvent: SharedFlow<ViewEvent> = _viewEvent.asSharedFlow()

    data class ConfirmationDialogState(
        val request: ConfirmationRequest,
        val title: String,
        val message: String,
        val positiveButton: String,
        val negativeButton: String?
    )

    private val _confirmationDialog = MutableSharedFlow<ConfirmationDialogState>(extraBufferCapacity = 1)
    val confirmationDialog: SharedFlow<ConfirmationDialogState> = _confirmationDialog.asSharedFlow()

    private var pendingConfirmation: ConfirmationRequest? = null
    // --- END REFACTOR ---
    
    private val _currentSortOrder = MutableLiveData(SortOrder.DATE_ADDED_DESC)
    val currentSortOrder: LiveData<SortOrder> = _currentSortOrder
    
    private val _selectedCategory = MutableLiveData<String?>(ALL_CATEGORIES_SENTINEL)
    val selectedCategory: LiveData<String?> = _selectedCategory

    private val _searchQuery = MutableLiveData("")
    val searchQuery: LiveData<String> = _searchQuery
    
    private val _isMinimalCardMode = MutableLiveData(false)
    val isMinimalCardMode: LiveData<Boolean> = _isMinimalCardMode
    
    private val _priceUpdateProgress = MutableLiveData<PriceUpdateProgressState>()
    val priceUpdateProgress: LiveData<PriceUpdateProgressState> = _priceUpdateProgress
    
    fun requestPriceUpdate(bookWithStores: BookWithStores) {
        updateBookPrices(bookWithStores)
    }
    
    fun requestEditBook(data: EditBookData) {
        viewModelScope.launch { _viewEvent.emit(ViewEvent.ShowEditBook(data)) }
    }

    fun confirmSameBook(duplicateResult: BookRepository.BookAddResult.TitleAuthorDuplicate) {
        viewModelScope.launch {
            _loadingState.value = LoadingState.SAVING_TO_DATABASE
            _detailedStatus.value = DetailedStatus("Adding to existing book...")
            var isSuccess = false
            try {
                when (val result = repository.addToExistingBook(
                    existingBookId = duplicateResult.existingBookWithStores.book.id,
                    storeName = duplicateResult.newStoreName,
                    storeUrl = duplicateResult.newStoreUrl,
                    price = duplicateResult.newPrice
                )) {
                    is BookRepository.BookAddResult.Updated -> {
                        _loadingState.value = LoadingState.SUCCESS
                        _detailedStatus.value = DetailedStatus("Added to existing book!")
                        viewModelScope.launch { _uiMessage.emit(UiMessage(R.string.book_added_to_existing_successfully, MessageType.SUCCESS, listOf(duplicateResult.newStoreName))) }
                        triggerScrollToBook(duplicateResult.existingBookWithStores.book.id)
                        isSuccess = true
                    }
                    is BookRepository.BookAddResult.StoreUpdated -> {
                        _loadingState.value = LoadingState.SUCCESS
                        _detailedStatus.value = DetailedStatus("Store information updated!")
                        viewModelScope.launch { _uiMessage.emit(UiMessage(R.string.store_updated_successfully, MessageType.SUCCESS, listOf(duplicateResult.newStoreName))) }
                        triggerScrollToBook(duplicateResult.existingBookWithStores.book.id)
                        isSuccess = true
                    }
                    is BookRepository.BookAddResult.Duplicate -> {
                        viewModelScope.launch { _uiMessage.emit(UiMessage(R.string.store_already_associated_with_book, MessageType.ERROR, listOf(duplicateResult.newStoreName))) }
                        triggerScrollToBook(duplicateResult.existingBookWithStores.book.id)
                    }
                    is BookRepository.BookAddResult.Error -> {
                        viewModelScope.launch { _uiMessage.emit(UiMessage(R.string.failed_to_add_to_existing_book, MessageType.ERROR, listOf(result.message))) }
                    }
                    else -> {
                        viewModelScope.launch { _uiMessage.emit(UiMessage(R.string.unexpected_result_adding_existing_book, MessageType.ERROR)) }
                    }
                }
            } catch (e: Exception) {
                recordUiException(e, "confirm_same_book")
                viewModelScope.launch { _uiMessage.emit(UiMessage(R.string.failed_to_add_to_existing_book, MessageType.ERROR, listOf(e.message ?: ""))) }
            } finally {
                _loadingState.value = LoadingState.IDLE
                if (!isSuccess) {
                    _detailedStatus.value = null
                }
            }
        }
    }

    fun confirmDifferentBook(duplicateResult: BookRepository.BookAddResult.TitleAuthorDuplicate) {
        viewModelScope.launch {
            _loadingState.value = LoadingState.SAVING_TO_DATABASE
            _detailedStatus.value = DetailedStatus("Adding as new book...")
            var isSuccess = false
            try {
                when (val result = repository.forceAddAsNewBook(
                    title = duplicateResult.newTitle,
                    author = duplicateResult.newAuthor,
                    isbn = duplicateResult.newIsbn,
                    price = duplicateResult.newPrice,
                    storeName = duplicateResult.newStoreName,
                    storeUrl = duplicateResult.newStoreUrl,
                    category = duplicateResult.newCategory
                )) {
                    is BookRepository.BookAddResult.Created -> {
                        _loadingState.value = LoadingState.SUCCESS
                        _detailedStatus.value = DetailedStatus("Added as new book!")
                        viewModelScope.launch { _uiMessage.emit(UiMessage(R.string.book_added_success, MessageType.SUCCESS)) }
                        triggerScrollToBook(result.bookId)
                        isSuccess = true
                    }
                    is BookRepository.BookAddResult.Error -> {
                        viewModelScope.launch { _uiMessage.emit(UiMessage(R.string.failed_to_add_new_book, MessageType.ERROR, listOf(result.message))) }
                    }
                    else -> {
                        viewModelScope.launch { _uiMessage.emit(UiMessage(R.string.unexpected_result_adding_new_book, MessageType.ERROR)) }
                    }
                }
            } catch (e: Exception) {
                recordUiException(e, "confirm_different_book")
                viewModelScope.launch { _uiMessage.emit(UiMessage(R.string.failed_to_add_new_book, MessageType.ERROR, listOf(e.message ?: ""))) }
            } finally {
                _loadingState.value = LoadingState.IDLE
                if (!isSuccess) {
                    _detailedStatus.value = null
                }
            }
        }
    }

    fun onSellerSelected(
        options: WebScrapingService.ScrapingResult.MultipleSellerOptions,
        selectedOption: com.booktracker.booksidntneed.network.SellerOption
    ) {
        processSelectedSeller(options, selectedOption)
    }

    fun confirmManualEntry(data: ManualEntryData) {
        addBookManually(
            title = data.title,
            author = data.author,
            isbn = data.isbn,
            price = data.price,
            storeName = data.storeName ?: app.getString(R.string.manual_entry),
            storeUrl = data.storeUrl,
            category = data.category ?: app.getString(R.string.uncategorized)
        )
    }

    fun confirmEditBook(data: EditBookData) {
        updateBook(
            book = data.book,
            store = data.store,
            title = data.title,
            author = data.author,
            isbn = data.isbn,
            price = data.price,
            storeName = data.storeName ?: app.getString(R.string.manual_entry),
            storeUrl = data.storeUrl,
            category = data.category ?: app.getString(R.string.uncategorized)
        )
    }
    
    // Data
    val allCategories: LiveData<List<Category>> = repository.getAllCategories()

    private fun List<BookWithStores>.filterBySearchQuery(query: String): List<BookWithStores> {
        if (query.isBlank()) return this

        val normalizedQuery = query.lowercase(Locale.ROOT)
        val numericQuery = query.filter { it.isDigit() || it.equals('x', ignoreCase = true) }
            .lowercase(Locale.ROOT)

        return filter { bookWithStores ->
            val book = bookWithStores.book
            val searchableText = buildString {
                append(book.title)
                append(' ')
                append(book.author)
                append(' ')
                append(book.isbn10.orEmpty())
                append(' ')
                append(book.isbn13.orEmpty())
                append(' ')
                append(bookWithStores.stores.joinToString(" ") { it.storeName })
            }.lowercase(Locale.ROOT)

            val isbnText = "${book.isbn10.orEmpty()} ${book.isbn13.orEmpty()}"
                .filter { it.isDigit() || it.equals('x', ignoreCase = true) }
                .lowercase(Locale.ROOT)

            searchableText.contains(normalizedQuery) ||
                (numericQuery.isNotBlank() && isbnText.contains(numericQuery))
        }
    }
    
    // --- REFACTOR: Use switchMap for filteredBooks ---
    val filteredBooks: LiveData<List<BookWithStores>> = androidx.lifecycle.MediatorLiveData<List<BookWithStores>>().apply {
        val sortOrderSource = _currentSortOrder
        val categorySource = _selectedCategory
        val searchQuerySource = _searchQuery
        var currentSource: LiveData<List<BookWithStores>>? = null
        var latestBooks: List<BookWithStores> = emptyList()

        fun applySearch() {
            value = latestBooks.filterBySearchQuery(searchQuerySource.value.orEmpty())
        }

        fun updateSource() {
            val sortOrder = sortOrderSource.value ?: SortOrder.DATE_ADDED_DESC
            val category = categorySource.value
            val newSource = repository.getFilteredAndSortedBooks(sortOrder, category)
            currentSource?.let { removeSource(it) }
            currentSource = newSource
            addSource(newSource) { books ->
                latestBooks = books ?: emptyList()
                applySearch()
            }
        }
        addSource(sortOrderSource) { updateSource() }
        addSource(categorySource) { updateSource() }
        addSource(searchQuerySource) { applySearch() }
        // Initial source
        updateSource()
    }
    
    // Track the current LiveData source for filtered books
    // private var currentBooksSource: LiveData<List<BookWithStores>>? = null
    
    // Unified confirmation event channel
    // private val _pendingConfirmation = MutableLiveData<ConfirmationRequest?>() // Removed
    // val pendingConfirmation: LiveData<ConfirmationRequest?> = _pendingConfirmation // Removed

    fun requestBookDeletion(bookWithStores: BookWithStores) {
        val request = ConfirmationRequest.DeleteBook(bookWithStores)
        pendingConfirmation = request
        viewModelScope.launch {
            _confirmationDialog.emit(
                ConfirmationDialogState(
                    request = request,
                    title = app.getString(R.string.confirm_delete_book_title),
                    message = app.getString(R.string.confirm_delete_book_message),
                    positiveButton = app.getString(R.string.delete),
                    negativeButton = app.getString(R.string.cancel)
                )
            )
        }
    }

    fun requestStoreDeletion(store: BookStore) {
        val request = ConfirmationRequest.DeleteStore(store)
        pendingConfirmation = request
        viewModelScope.launch {
            _confirmationDialog.emit(
                ConfirmationDialogState(
                    request = request,
                    title = app.getString(R.string.delete_store_title),
                    message = app.getString(R.string.delete_store_message, store.storeName),
                    positiveButton = app.getString(R.string.delete),
                    negativeButton = app.getString(R.string.cancel)
                )
            )
        }
    }

    fun requestCategoryDeletion(category: Category) {
        if (category.isDefault) {
            viewModelScope.launch {
                _uiMessage.emit(
                    UiMessage(
                        R.string.failed_to_delete_category,
                        MessageType.ERROR,
                        listOf(app.getString(R.string.category_not_found_or_cannot_edit))
                    )
                )
            }
            return
        }
        val request = ConfirmationRequest.DeleteCategory(category)
        pendingConfirmation = request
        viewModelScope.launch {
            _confirmationDialog.emit(
                ConfirmationDialogState(
                    request = request,
                    title = app.getString(R.string.confirm_delete_category_title),
                    message = app.getString(R.string.confirm_delete_category_message),
                    positiveButton = app.getString(R.string.delete),
                    negativeButton = app.getString(R.string.cancel)
                )
            )
        }
    }

    fun executeConfirmedAction() {
        val request = pendingConfirmation
        pendingConfirmation = null
        when (request) {
            is ConfirmationRequest.DeleteBook -> deleteBook(request.bookWithStores)
            is ConfirmationRequest.DeleteStore -> deleteStoreFromBook(request.store)
            is ConfirmationRequest.DeleteCategory -> {
                if (request.category.isDefault) {
                    viewModelScope.launch {
                        _uiMessage.emit(
                            UiMessage(
                                R.string.failed_to_delete_category,
                                MessageType.ERROR,
                                listOf(app.getString(R.string.category_not_found_or_cannot_edit))
                            )
                        )
                    }
                } else {
                    deleteCustomCategory(request.category.name)
                }
            }
            is ConfirmationRequest.UpdatePrices -> updateBookPrices(request.bookWithStores)
            null -> {
                // No pending confirmation to execute
            }
        }
    }

    fun cancelConfirmationAction() {
        pendingConfirmation = null
    }
    
    fun triggerScrollToTop() {
        viewModelScope.launch { _scrollTarget.emit(ScrollTarget(bookId = null, shouldScroll = true)) }
    }
    
    private fun triggerScrollToBook(bookId: Long) {
        viewModelScope.launch { _scrollTarget.emit(ScrollTarget(bookId = bookId, shouldScroll = true)) }
    }
    
    fun addBookFromUrl(url: String, selectedCategory: String = app.getString(R.string.uncategorized)) {
        viewModelScope.launch {
            _loadingState.value = LoadingState.ANALYZING_URL
            _detailedStatus.value = DetailedStatus("Analyzing URL format...")
            
            Log.d("BookTracker", "ViewModel: Processing URL: $url")
            
            try {
                _loadingState.value = LoadingState.CONNECTING_TO_SITE
                _detailedStatus.value = DetailedStatus("Connecting to website...")
                
                _loadingState.value = LoadingState.DOWNLOADING_PAGE
                _detailedStatus.value = DetailedStatus("Downloading page content...")
                
                _loadingState.value = LoadingState.PARSING_CONTENT
                _detailedStatus.value = DetailedStatus("Parsing webpage content...")
                
                _loadingState.value = LoadingState.EXTRACTING_DETAILS
                _detailedStatus.value = DetailedStatus("Extracting book details...")
                
                _loadingState.value = LoadingState.VALIDATING_DATA
                _detailedStatus.value = DetailedStatus("Validating book information...")
                
                _loadingState.value = LoadingState.CHECKING_DUPLICATES
                _detailedStatus.value = DetailedStatus("Checking for existing books...")
                
                _loadingState.value = LoadingState.SAVING_TO_DATABASE
                _detailedStatus.value = DetailedStatus("Saving to library")
                
                when (val result = repository.addBookFromUrl(url, selectedCategory)) {
                    is BookRepository.BookAddResult.Created -> {
                        _loadingState.value = LoadingState.SUCCESS
                        _detailedStatus.value = DetailedStatus("Book added successfully!")
                        Log.d("BookTracker", "ViewModel: Book created successfully with ID: ${result.bookId}")
                        viewModelScope.launch { _uiMessage.emit(UiMessage(R.string.book_added_success, MessageType.SUCCESS)) }
                        triggerScrollToTop() // New books go to top with current sort order
                    }
                    is BookRepository.BookAddResult.Updated -> {
                        _loadingState.value = LoadingState.SUCCESS
                        _detailedStatus.value = DetailedStatus("Book updated successfully!")
                        Log.d("BookTracker", "ViewModel: Book updated successfully with ID: ${result.bookId}, new store: ${result.storeName}")
                        viewModelScope.launch { _uiMessage.emit(UiMessage(R.string.book_updated_successfully, MessageType.SUCCESS, listOf(result.storeName))) }
                        triggerScrollToBook(result.bookId) // Scroll to the updated book
                    }
                    is BookRepository.BookAddResult.StoreUpdated -> {
                        _loadingState.value = LoadingState.SUCCESS
                        _detailedStatus.value = DetailedStatus("Store information updated!")
                        Log.d("BookTracker", "ViewModel: Store info updated for book ID: ${result.bookId}, store: ${result.storeName}")
                        viewModelScope.launch { _uiMessage.emit(UiMessage(R.string.store_updated_successfully, MessageType.SUCCESS, listOf(result.storeName))) }
                        triggerScrollToBook(result.bookId) // Scroll to the updated book
                    }
                    is BookRepository.BookAddResult.Duplicate -> {
                        _loadingState.value = LoadingState.IDLE
                        _detailedStatus.value = null
                        Log.w("BookTracker", "ViewModel: Duplicate entry detected for book ID: ${result.bookId}, store: ${result.storeName}")
                        viewModelScope.launch { _uiMessage.emit(UiMessage(R.string.book_already_in_library, MessageType.ERROR, listOf(result.storeName))) }
                        triggerScrollToBook(result.bookId) // Scroll to show the existing book
                    }
                    is BookRepository.BookAddResult.TitleAuthorDuplicate -> {
                        _loadingState.value = LoadingState.IDLE
                        _detailedStatus.value = null
                        // This shouldn't happen for URL-based additions, but handle it gracefully
                        Log.e("BookTracker", "ViewModel: Unexpected title/author duplicate in URL addition")
                        viewModelScope.launch { _uiMessage.emit(UiMessage(R.string.unexpected_duplicate_detection_error, MessageType.ERROR)) }
                    }
                    is BookRepository.BookAddResult.MultipleSellerOptionsFound -> {
                        _loadingState.value = LoadingState.IDLE
                        _detailedStatus.value = null
                        Log.d("BookTracker", "ViewModel: Multiple sellers found, triggering selection dialog")
                        viewModelScope.launch { _viewEvent.emit(ViewEvent.ShowSellerSelection(result.multipleSellerOptions)) }
                    }
                    is BookRepository.BookAddResult.Error -> {
                        _loadingState.value = LoadingState.IDLE
                        _detailedStatus.value = null
                        Log.e("BookTracker", "ViewModel: Repository error: ${result.message}")
                        viewModelScope.launch { _uiMessage.emit(UiMessage(R.string.failed_to_add_book_from_url, MessageType.ERROR, listOf(result.message))) }
                    }
                }
            } catch (e: Exception) {
                _loadingState.value = LoadingState.IDLE
                _detailedStatus.value = null
                Log.e("BookTracker", "ViewModel: Unexpected error: ${e.message}", e)
                recordUiException(e, "add_book_from_url")
                viewModelScope.launch { _uiMessage.emit(UiMessage(R.string.unexpected_error, MessageType.ERROR, listOf(e.message ?: ""))) }
            } finally {
                // Reset to idle after a brief success state display
                if (_loadingState.value == LoadingState.SUCCESS) {
                    viewModelScope.launch {
                        kotlinx.coroutines.delay(1500.milliseconds) // Allow UI animation to complete
                        _loadingState.value = LoadingState.IDLE
                        _detailedStatus.value = null
                    }
                } else {
                    // If it wasn't success, reset immediately (e.g., error or duplicate)
                    if (_loadingState.value != LoadingState.IDLE) {
                        _loadingState.value = LoadingState.IDLE
                        _detailedStatus.value = null
                    }
                }
            }
        }
    }
    
    fun addBookManually(
        title: String,
        author: String,
        isbn: String? = null,
        price: Double? = null,
        storeName: String = app.getString(R.string.manual_entry),
        storeUrl: String? = null,
        category: String = app.getString(R.string.uncategorized),

    ) {
        viewModelScope.launch {
            _loadingState.value = LoadingState.VALIDATING_DATA
            _detailedStatus.value = DetailedStatus("Validating book information...")
            
            Log.d("BookTracker", "ViewModel: Adding book manually - Title: '$title', Author: '$author'")
            
            try {
                _loadingState.value = LoadingState.CHECKING_DUPLICATES
                _detailedStatus.value = DetailedStatus("Checking for existing books...")
                
                _loadingState.value = LoadingState.SAVING_TO_DATABASE
                _detailedStatus.value = DetailedStatus("Saving to library")
                
                when (val result = repository.addBookManually(
                    title = title,
                    author = author,
                    isbn = isbn,
                    price = price,
                    storeName = storeName,
                    storeUrl = storeUrl,
                    category = category,

                )) {
                    is BookRepository.BookAddResult.Created -> {
                        _loadingState.value = LoadingState.SUCCESS
                        _detailedStatus.value = DetailedStatus("Book added successfully!")
                        Log.d("BookTracker", "ViewModel: Manual book created successfully with ID: ${result.bookId}")
                        viewModelScope.launch { _uiMessage.emit(UiMessage(R.string.book_added_success, MessageType.SUCCESS)) }
                        triggerScrollToTop() // New books go to top
                    }
                    is BookRepository.BookAddResult.Updated -> {
                        _loadingState.value = LoadingState.SUCCESS
                        _detailedStatus.value = DetailedStatus("Book updated successfully!")
                        Log.d("BookTracker", "ViewModel: Manual book updated successfully with ID: ${result.bookId}, new store: ${result.storeName}")
                        viewModelScope.launch { _uiMessage.emit(UiMessage(R.string.book_updated_successfully, MessageType.SUCCESS, listOf(result.storeName))) }
                        triggerScrollToBook(result.bookId) // Scroll to the updated book
                    }
                    is BookRepository.BookAddResult.StoreUpdated -> {
                        _loadingState.value = LoadingState.SUCCESS
                        _detailedStatus.value = DetailedStatus("Store information updated!")
                        Log.d("BookTracker", "ViewModel: Manual book store info updated for book ID: ${result.bookId}, store: ${result.storeName}")
                        viewModelScope.launch { _uiMessage.emit(UiMessage(R.string.store_updated_successfully, MessageType.SUCCESS, listOf(result.storeName))) }
                        triggerScrollToBook(result.bookId) // Scroll to the updated book
                    }
                    is BookRepository.BookAddResult.Duplicate -> {
                        _loadingState.value = LoadingState.IDLE
                        _detailedStatus.value = null
                        Log.w("BookTracker", "ViewModel: Manual book duplicate entry detected for book ID: ${result.bookId}, store: ${result.storeName}")
                        viewModelScope.launch { _uiMessage.emit(UiMessage(R.string.book_already_in_library, MessageType.ERROR, listOf(result.storeName))) }
                        triggerScrollToBook(result.bookId) // Scroll to show the existing book
                    }
                    is BookRepository.BookAddResult.TitleAuthorDuplicate -> {
                        _loadingState.value = LoadingState.IDLE
                        _detailedStatus.value = null
                        Log.d("BookTracker", "ViewModel: Manual book title/author duplicate detected")
                        viewModelScope.launch { _viewEvent.emit(ViewEvent.ShowDuplicateCheck(result)) }
                    }
                    is BookRepository.BookAddResult.MultipleSellerOptionsFound -> {
                        _loadingState.value = LoadingState.IDLE
                        _detailedStatus.value = null
                        // This shouldn't happen for manual additions, but handle it gracefully
                        Log.e("BookTracker", "ViewModel: Unexpected multiple seller options in manual addition")
                        viewModelScope.launch { _uiMessage.emit(UiMessage(R.string.unexpected_multiple_seller_detection_error, MessageType.ERROR)) }
                    }
                    is BookRepository.BookAddResult.Error -> {
                        _loadingState.value = LoadingState.IDLE
                        _detailedStatus.value = null
                        Log.e("BookTracker", "ViewModel: Manual book repository error: ${result.message}")
                        viewModelScope.launch { _uiMessage.emit(UiMessage(R.string.failed_to_add_book_manually, MessageType.ERROR, listOf(result.message))) }
                    }
                }
            } catch (e: Exception) {
                _loadingState.value = LoadingState.IDLE
                _detailedStatus.value = null
                Log.e("BookTracker", "ViewModel: Manual book unexpected error: ${e.message}", e)
                recordUiException(e, "add_book_manually")
                viewModelScope.launch { _uiMessage.emit(UiMessage(R.string.unexpected_error, MessageType.ERROR, listOf(e.message ?: ""))) }
            } finally {
                // Reset to idle after a brief success state display
                if (_loadingState.value == LoadingState.SUCCESS) {
                    viewModelScope.launch {
                        kotlinx.coroutines.delay(1500.milliseconds) // Allow UI animation to complete
                        _loadingState.value = LoadingState.IDLE
                        _detailedStatus.value = null
                    }
                } else {
                    // If it wasn't success, reset immediately (e.g., error or duplicate)
                    if (_loadingState.value != LoadingState.IDLE) {
                        _loadingState.value = LoadingState.IDLE
                        _detailedStatus.value = null
                    }
                }
            }
        }
    }
    
    fun deleteBook(bookWithStores: BookWithStores) {
        viewModelScope.launch {
            try {
                repository.deleteBook(bookWithStores.book)
                viewModelScope.launch { _uiMessage.emit(UiMessage(R.string.book_deleted_successfully, MessageType.SUCCESS)) }
            } catch (e: Exception) {
                recordUiException(e, "delete_book")
                viewModelScope.launch { _uiMessage.emit(UiMessage(R.string.failed_to_delete_book, MessageType.ERROR, listOf(e.message ?: ""))) }
            }
        }
    }
    
    fun updateBookCategory(bookId: Long, newCategory: String) {
        viewModelScope.launch {
            try {
                repository.updateBookCategory(bookId, newCategory)
                viewModelScope.launch { _uiMessage.emit(UiMessage(R.string.category_updated_successfully, MessageType.SUCCESS)) }
            } catch (e: Exception) {
                recordUiException(e, "update_book_category")
                viewModelScope.launch { _uiMessage.emit(UiMessage(R.string.failed_to_update_category, MessageType.ERROR, listOf(e.message ?: ""))) }
            }
        }
    }

    fun deleteStoreFromBook(store: BookStore) {
        viewModelScope.launch {
            try {
                repository.deleteStore(store)
                viewModelScope.launch { _uiMessage.emit(UiMessage(R.string.store_removed_successfully, MessageType.SUCCESS, listOf(store.storeName))) }
            } catch (e: Exception) {
                recordUiException(e, "delete_store")
                viewModelScope.launch { _uiMessage.emit(UiMessage(R.string.failed_to_remove_store, MessageType.ERROR, listOf(e.message ?: ""))) }
            }
        }
    }

    fun updateBookPrices(bookWithStores: BookWithStores) {
        viewModelScope.launch {
            // Filter stores that can be updated (have valid URLs)
            val updatableStores = bookWithStores.stores.filter { store ->
                val url = store.storeUrl
                url.isNotBlank() && url != app.getString(R.string.manual_entry)
            }
            
            if (updatableStores.isEmpty()) {
                viewModelScope.launch { _uiMessage.emit(UiMessage(R.string.no_stores_found_with_valid_urls, MessageType.ERROR)) }
                return@launch
            }
            
            // Calculate total tasks (6 tasks per store)
            val totalTasks = updatableStores.size * 6
            
            // Initialize progress state
            val initialProgress = updatableStores.map { store ->
                StoreUpdateProgress(
                    storeId = store.id,
                    storeName = store.storeName,
                    status = StoreUpdateStatus.PENDING,
                    oldPrice = store.price,
                    currentTask = StoreUpdateTask.PENDING,
                    taskProgress = 0,
                    totalTasks = 6
                )
            }
            
            _priceUpdateProgress.value = PriceUpdateProgressState(
                isActive = true,
                currentStoreIndex = 0,
                totalStores = updatableStores.size,
                stores = initialProgress,
                isComplete = false,
                bookTitle = bookWithStores.book.title,
                overallProgress = 0,
                totalTasks = totalTasks,
                completedTasks = 0
            )
            
            try {
                var successCount = 0
                var failureCount = 0
                var completedTasks = 0
                val updatedProgress = initialProgress.toMutableList()
                
                val deferredResults = updatableStores.mapIndexed { index, store ->
                    async {
                        // Create progress callback for this store
                        val progressCallback = object : ScrapingProgressCallback {
                            override fun onTaskStarted(task: String, progress: Int) {
                                val taskEnum = when (task) {
                                    "Validating URL" -> StoreUpdateTask.VALIDATING_URL
                                    "Establishing Session" -> StoreUpdateTask.ESTABLISHING_SESSION
                                    "Fetching Document" -> StoreUpdateTask.FETCHING_DOCUMENT
                                    "Parsing Content" -> StoreUpdateTask.PARSING_CONTENT
                                    "Validating Data" -> StoreUpdateTask.VALIDATING_DATA
                                    "Updating Database" -> StoreUpdateTask.UPDATING_DATABASE
                                    else -> StoreUpdateTask.PENDING
                                }
                                
                                updatedProgress[index] = updatedProgress[index].copy(
                                    status = StoreUpdateStatus.UPDATING,
                                    currentTask = taskEnum,
                                    taskProgress = progress
                                )
                                
                                _priceUpdateProgress.postValue(_priceUpdateProgress.value?.copy(
                                    currentStoreIndex = index,
                                    stores = updatedProgress.toList()
                                ))
                            }
                            
                            override fun onTaskProgress(task: String, progress: Int) {
                                val taskEnum = when (task) {
                                    "Validating URL" -> StoreUpdateTask.VALIDATING_URL
                                    "Establishing Session" -> StoreUpdateTask.ESTABLISHING_SESSION
                                    "Fetching Document" -> StoreUpdateTask.FETCHING_DOCUMENT
                                    "Parsing Content" -> StoreUpdateTask.PARSING_CONTENT
                                    "Validating Data" -> StoreUpdateTask.VALIDATING_DATA
                                    "Updating Database" -> StoreUpdateTask.UPDATING_DATABASE
                                    else -> StoreUpdateTask.PENDING
                                }
                                
                                updatedProgress[index] = updatedProgress[index].copy(
                                    currentTask = taskEnum,
                                    taskProgress = progress
                                )
                                
                                // Calculate overall progress
                                val overallProgress = calculateOverallProgress(updatedProgress, totalTasks)
                                
                                _priceUpdateProgress.postValue(_priceUpdateProgress.value?.copy(
                                    stores = updatedProgress.toList(),
                                    overallProgress = overallProgress
                                ))
                            }
                            
                            override fun onTaskCompleted(task: String) {
                                completedTasks++
                                val taskEnum = when (task) {
                                    "Validating URL" -> StoreUpdateTask.VALIDATING_URL
                                    "Establishing Session" -> StoreUpdateTask.ESTABLISHING_SESSION
                                    "Fetching Document" -> StoreUpdateTask.FETCHING_DOCUMENT
                                    "Parsing Content" -> StoreUpdateTask.PARSING_CONTENT
                                    "Validating Data" -> StoreUpdateTask.VALIDATING_DATA
                                    "Updating Database" -> StoreUpdateTask.UPDATING_DATABASE
                                    else -> StoreUpdateTask.COMPLETED
                                }
                                
                                // Move to next task or complete
                                val nextTask = when (taskEnum) {
                                    StoreUpdateTask.VALIDATING_URL -> StoreUpdateTask.ESTABLISHING_SESSION
                                    StoreUpdateTask.ESTABLISHING_SESSION -> StoreUpdateTask.FETCHING_DOCUMENT
                                    StoreUpdateTask.FETCHING_DOCUMENT -> StoreUpdateTask.PARSING_CONTENT
                                    StoreUpdateTask.PARSING_CONTENT -> StoreUpdateTask.VALIDATING_DATA
                                    StoreUpdateTask.VALIDATING_DATA -> StoreUpdateTask.UPDATING_DATABASE
                                    StoreUpdateTask.UPDATING_DATABASE -> StoreUpdateTask.COMPLETED
                                    else -> StoreUpdateTask.COMPLETED
                                }
                                
                                if (nextTask == StoreUpdateTask.COMPLETED) {
                                    updatedProgress[index] = updatedProgress[index].copy(
                                        status = StoreUpdateStatus.SUCCESS,
                                        currentTask = StoreUpdateTask.COMPLETED,
                                        taskProgress = 100
                                    )
                                } else {
                                    updatedProgress[index] = updatedProgress[index].copy(
                                        currentTask = nextTask,
                                        taskProgress = 0
                                    )
                                }
                                
                                // Calculate overall progress
                                val overallProgress = calculateOverallProgress(updatedProgress, totalTasks)
                                
                                _priceUpdateProgress.postValue(_priceUpdateProgress.value?.copy(
                                    stores = updatedProgress.toList(),
                                    overallProgress = overallProgress,
                                    completedTasks = completedTasks
                                ))
                            }
                            
                            override fun onError(task: String, error: String) {
                                updatedProgress[index] = updatedProgress[index].copy(
                                    status = StoreUpdateStatus.FAILED,
                                    errorMessage = "$task: $error"
                                )
                                
                                _priceUpdateProgress.postValue(_priceUpdateProgress.value?.copy(
                                    stores = updatedProgress.toList()
                                ))
                            }
                        }
                        
                        try {
                            when (val result = repository.updateSingleStorePrices(store, progressCallback)) {
                                is BookRepository.SingleStoreUpdateResult.Success -> {
                                    updatedProgress[index] = updatedProgress[index].copy(
                                        status = StoreUpdateStatus.SUCCESS,
                                        newPrice = result.newPrice,
                                        currentTask = StoreUpdateTask.COMPLETED,
                                        taskProgress = 100
                                    )
                                    successCount++
                                }
                                is BookRepository.SingleStoreUpdateResult.Failed -> {
                                    updatedProgress[index] = updatedProgress[index].copy(
                                        status = StoreUpdateStatus.FAILED,
                                        errorMessage = result.errorMessage
                                    )
                                    failureCount++
                                }
                                is BookRepository.SingleStoreUpdateResult.Skipped -> {
                                    updatedProgress[index] = updatedProgress[index].copy(
                                        status = StoreUpdateStatus.SKIPPED,
                                        errorMessage = result.reason
                                    )
                                    // Don't count skipped as failures
                                }
                            }
                        } catch (e: Exception) {
                            recordUiException(
                                e,
                                "update_single_store_price",
                                mapOf("store_host" to hostFrom(store.storeUrl))
                            )
                            updatedProgress[index] = updatedProgress[index].copy(
                                status = StoreUpdateStatus.FAILED,
                                errorMessage = "Unexpected error: ${e.message}"
                            )
                            failureCount++
                        }
                        
                        // Update progress after each store finishes
                        _priceUpdateProgress.postValue(_priceUpdateProgress.value?.copy(
                            stores = updatedProgress.toList()
                        ))
                    }
                }
                
                // Wait for all async operations to complete
                deferredResults.awaitAll()
                
                // Mark as complete
                _priceUpdateProgress.value = _priceUpdateProgress.value?.copy(
                    isComplete = true,
                    currentStoreIndex = updatableStores.size,
                    overallProgress = 100
                )
                
                // Note: No toast messages for price updates - results are shown in the dialog
            } catch (e: Exception) {
                recordUiException(e, "update_book_prices")
                viewModelScope.launch { _uiMessage.emit(UiMessage(R.string.failed_to_update_prices, MessageType.ERROR, listOf(e.message ?: ""))) }
                _priceUpdateProgress.value = _priceUpdateProgress.value?.copy(
                    isActive = false,
                    isComplete = true
                )
            } finally {
                // Keep progress visible until user manually closes dialog
                // No auto-clear - user can review results and close when ready
            }
        }
    }
    
    private fun calculateOverallProgress(stores: List<StoreUpdateProgress>, totalTasks: Int): Int {
        var completedTasks = 0
        stores.forEach { store ->
            when (store.status) {
                StoreUpdateStatus.SUCCESS -> completedTasks += store.totalTasks
                StoreUpdateStatus.FAILED, StoreUpdateStatus.SKIPPED -> completedTasks += store.totalTasks
                StoreUpdateStatus.UPDATING -> {
                    // Count completed tasks based on current task
                    val completedForStore = when (store.currentTask) {
                        StoreUpdateTask.PENDING -> 0
                        StoreUpdateTask.VALIDATING_URL -> 1
                        StoreUpdateTask.ESTABLISHING_SESSION -> 2
                        StoreUpdateTask.FETCHING_DOCUMENT -> 3
                        StoreUpdateTask.PARSING_CONTENT -> 4
                        StoreUpdateTask.VALIDATING_DATA -> 5
                        StoreUpdateTask.UPDATING_DATABASE -> 6
                        StoreUpdateTask.COMPLETED -> 6
                    }
                    completedTasks += completedForStore
                }
                else -> {} // PENDING - no tasks completed
            }
        }
        return if (totalTasks > 0) (completedTasks * 100) / totalTasks else 0
    }
    
    fun clearPriceUpdateProgress() {
        _priceUpdateProgress.value = PriceUpdateProgressState(bookTitle = "")
    }
    
    val categoryManager = CategoryManager(repository, viewModelScope)

    fun addCustomCategory(categoryName: String, color: String? = null) {
        categoryManager.addCustomCategory(app, categoryName, color) { success, message ->
            viewModelScope.launch { _uiMessage.emit(UiMessage(
                if (success) R.string.category_created_successfully else R.string.failed_to_add_category,
                if (success) MessageType.SUCCESS else MessageType.ERROR,
                listOfNotNull(message)
            )) }
        }
    }

    fun deleteCustomCategory(categoryName: String) {
        categoryManager.deleteCustomCategory(app, categoryName) { success, message ->
            viewModelScope.launch { _uiMessage.emit(UiMessage(
                if (success) R.string.category_deleted_successfully else R.string.failed_to_delete_category,
                if (success) MessageType.SUCCESS else MessageType.ERROR,
                listOfNotNull(message)
            )) }
        }
    }

    fun updateCustomCategory(oldName: String, newName: String, newColor: String? = null) {
        categoryManager.updateCustomCategory(app, oldName, newName, newColor) { success, message ->
            viewModelScope.launch { _uiMessage.emit(UiMessage(
                if (success) R.string.category_renamed_successfully else R.string.failed_to_update_category,
                if (success) MessageType.SUCCESS else MessageType.ERROR,
                listOfNotNull(message)
            )) }
        }
    }

    fun validateCategoryName(name: String): String? {
        return categoryManager.validateCategoryName(app, name)
    }

    fun setSortOrder(sortOrder: SortOrder) {
        _currentSortOrder.value = sortOrder
    }
    
    fun setSelectedCategory(category: String?) {
        _selectedCategory.value = category
    }

    fun setSearchQuery(query: String) {
        val normalizedQuery = query.trim()
        if (_searchQuery.value != normalizedQuery) {
            _searchQuery.value = normalizedQuery
        }
    }
    
    fun toggleCardViewMode() {
        _isMinimalCardMode.value = !(_isMinimalCardMode.value ?: false)
    }
    
    // private fun updateFilteredBooksFromDatabase() {
    //     val sortOrder = _currentSortOrder.value ?: SortOrder.DATE_ADDED_DESC
    //     val category = _selectedCategory.value // null means "All Categories"

    //     // Remove the old source if it exists
    //     currentBooksSource?.let {
    //         _filteredBooks.removeSource(it)
    //     }

    //     // Get the new source and store a reference to it
    //     val newSource = repository.getFilteredAndSortedBooks(sortOrder, category)
    //     currentBooksSource = newSource

    //     // Add the new source
    //     _filteredBooks.addSource(newSource) { books ->
    //         _filteredBooks.value = books ?: emptyList()
    //     }
    // }
    
    // Handle user selection from multiple seller options
    private fun processSelectedSeller(
        sellerOptions: WebScrapingService.ScrapingResult.MultipleSellerOptions,
        selectedSeller: com.booktracker.booksidntneed.network.SellerOption,
        selectedCategory: String = app.getString(R.string.uncategorized)
    ) {
        viewModelScope.launch {
            _loadingState.value = LoadingState.SAVING_TO_DATABASE
            
            Log.d("BookTracker", "ViewModel: User selected seller: ${selectedSeller.sellerName} - $${selectedSeller.price}")
            
            try {
                // Create ParsedBookInfo with the selected seller's information
                val bookInfo = ParsedBookInfo(
                    title = sellerOptions.bookTitle,
                    author = sellerOptions.bookAuthor ?: app.getString(R.string.unknown_author),
                    isbn10 = sellerOptions.bookIsbn.first,
                    isbn13 = sellerOptions.bookIsbn.second,
                    price = selectedSeller.price,
                    storeName = selectedSeller.sellerName,
                    storeUrl = sellerOptions.originalUrl, // Use original URL
                    coverImageUrl = sellerOptions.coverImageUrl,
                    currency = "USD"
                )
                
                // Use the repository's addBookFromParsedInfo method 
                // (we'll need to add this method to handle pre-parsed book info)
                when (val result = repository.addBookFromParsedInfo(bookInfo, selectedCategory)) {
                    is BookRepository.BookAddResult.Created -> {
                        _loadingState.value = LoadingState.SUCCESS
                        viewModelScope.launch { _uiMessage.emit(UiMessage(R.string.book_added_success, MessageType.SUCCESS)) }
                        triggerScrollToTop()
                    }
                    is BookRepository.BookAddResult.Updated -> {
                        _loadingState.value = LoadingState.SUCCESS
                        viewModelScope.launch { _uiMessage.emit(UiMessage(R.string.book_updated_successfully, MessageType.SUCCESS, listOf(selectedSeller.sellerName))) }
                        triggerScrollToBook(result.bookId)
                    }
                    is BookRepository.BookAddResult.StoreUpdated -> {
                        _loadingState.value = LoadingState.SUCCESS
                        viewModelScope.launch { _uiMessage.emit(UiMessage(R.string.store_updated_successfully, MessageType.SUCCESS, listOf(selectedSeller.sellerName))) }
                        triggerScrollToBook(result.bookId)
                    }
                    is BookRepository.BookAddResult.Duplicate -> {
                        _loadingState.value = LoadingState.IDLE
                        viewModelScope.launch { _uiMessage.emit(UiMessage(R.string.book_already_in_library, MessageType.ERROR, listOf(selectedSeller.sellerName))) }
                        triggerScrollToBook(result.bookId)
                    }
                    is BookRepository.BookAddResult.Error -> {
                        _loadingState.value = LoadingState.IDLE
                        viewModelScope.launch { _uiMessage.emit(UiMessage(R.string.failed_to_add_book_from_selected_seller, MessageType.ERROR, listOf(result.message))) }
                    }
                    else -> {
                        _loadingState.value = LoadingState.IDLE
                        viewModelScope.launch { _uiMessage.emit(UiMessage(R.string.unexpected_result_adding_selected_seller, MessageType.ERROR)) }
                    }
                }
                
                // Clear the multiple seller options
                // No longer emit null to SharedFlow (not allowed for non-nullable types)
                
            } catch (e: Exception) {
                _loadingState.value = LoadingState.IDLE
                recordUiException(e, "selected_seller")
                viewModelScope.launch { _uiMessage.emit(UiMessage(R.string.failed_to_add_book_from_selected_seller, MessageType.ERROR, listOf(e.message ?: ""))) }
            } finally {
                if (_loadingState.value == LoadingState.SUCCESS) {
                    _loadingState.value = LoadingState.IDLE
                }
            }
        }
    }
    
    fun updateBook(
        book: Book,
        store: BookStore,
        title: String,
        author: String,
        isbn: String? = null,
        price: Double? = null,
        storeName: String,
        storeUrl: String? = null,
        category: String
    ) {
        viewModelScope.launch {
            _loadingState.value = LoadingState.VALIDATING_DATA
            _detailedStatus.value = DetailedStatus("Validating book information...")
            var isSuccess = false
            try {
                _loadingState.value = LoadingState.SAVING_TO_DATABASE
                _detailedStatus.value = DetailedStatus("Saving changes...")

                val isbnResult = parseISBN(isbn)
                val isbn10 = isbnResult.isbn10
                val isbn13 = isbnResult.isbn13
                val updatedBook = book.copy(
                    title = title,
                    author = author,
                    isbn10 = isbn10,
                    isbn13 = isbn13,
                    category = category
                )
                repository.updateBook(updatedBook)
                val updatedStore = store.copy(
                    storeName = storeName,
                    storeUrl = storeUrl ?: "",
                    price = price,
                    lastUpdated = java.util.Date()
                )
                repository.updateStore(updatedStore)
                _loadingState.value = LoadingState.SUCCESS
                _detailedStatus.value = DetailedStatus("Book updated successfully!")
                viewModelScope.launch { _uiMessage.emit(UiMessage(R.string.book_updated_successfully, MessageType.SUCCESS)) }
                triggerScrollToBook(book.id)
                isSuccess = true
            } catch (e: Exception) {
                recordUiException(e, "update_book")
                viewModelScope.launch { _uiMessage.emit(UiMessage(R.string.failed_to_update_book, MessageType.ERROR, listOf(e.message ?: ""))) }
            } finally {
                if (!isSuccess) {
                    _loadingState.value = LoadingState.IDLE
                    _detailedStatus.value = null
                }
            }
        }
    }
    
    private fun parseISBN(isbn: String?): IsbnResult {
        if (isbn.isNullOrBlank()) return IsbnResult(null, null)
        
        // Clean the ISBN (remove spaces, hyphens)
        val cleanISBN = isbn.replace(Regex("[\\s-]"), "")
        
        return when {
            cleanISBN.matches(Regex("\\d{10}")) -> IsbnResult(cleanISBN, null) // ISBN-10
            cleanISBN.matches(Regex("\\d{9}[\\dX]")) -> IsbnResult(cleanISBN, null) // ISBN-10 with X
            cleanISBN.matches(Regex("\\d{13}")) -> IsbnResult(null, cleanISBN) // ISBN-13
            else -> IsbnResult(null, null) // Invalid ISBN
        }
    }
    

    
    enum class LoadingState {
        IDLE,                    // Not loading
        ANALYZING_URL,           // Analyzing the URL format and extracting info  
        CONNECTING_TO_SITE,      // Establishing connection to the website
        DOWNLOADING_PAGE,        // Downloading the webpage content
        PARSING_CONTENT,         // Parsing the webpage for book information
        EXTRACTING_DETAILS,      // Extracting specific book details (title, author, price, etc.)
        VALIDATING_DATA,         // Validating extracted book information
        CHECKING_DUPLICATES,     // Checking for existing books in database
        SAVING_TO_DATABASE,      // Saving book information to database
        SUCCESS                  // Successfully completed (brief state before returning to IDLE)
    }
    
    enum class SortOrder {
        TITLE_ASC,
        TITLE_DESC,
        AUTHOR_ASC,
        AUTHOR_DESC,
        DATE_ADDED_ASC,
        DATE_ADDED_DESC,
        PRICE_ASC,
        PRICE_DESC,
        STORE_NAME_ASC,
        STORE_NAME_DESC
    }
    
    /**
     * Get all books with stores for export
     */
    suspend fun getAllBooksForExport(): List<BookWithStores> {
        Log.d("BookTracker", "ViewModel: Getting all books with stores for export")
        return repository.getAllBooksForExport()
    }
    
    /**
     * Import data from CSV with optimized bulk operations to avoid N+1 query problem
     */
    suspend fun importData(books: List<Book>, stores: List<BookStore>, categories: List<Category>, storesByBookKey: Map<String, List<BookStore>>): ImportResult {
        Log.d("BookTracker", "ViewModel: Starting robust transactional data import - Books: "+books.size+", Stores: "+stores.size+", Categories: "+categories.size)
        // Get the localized default category name
        val defaultCategoryName = app.getString(R.string.uncategorized)
        return try {
            var booksImported = 0
            var storesImported = 0
            var storesUpdated = 0
            var storesUnchanged = 0
            var categoriesImported = 0
            var duplicatesMerged = 0

            Log.d("BookTracker", "ViewModel: Importing categories first")
            // Import categories first
            categories.forEach { category ->
                val existingCategory = repository.getCategoryByName(category.name)
                if (existingCategory == null) {
                    repository.insertCategory(category)
                    categoriesImported++
                    Log.d("BookTracker", "ViewModel: Imported new category: "+category.name)
                } else {
                    Log.d("BookTracker", "ViewModel: Category already exists: "+category.name)
                }
            }

            repository.runInTransaction {
                books.forEach { bookToImport ->
                    val bookKey = createBookKey(bookToImport)
                    val bookStores = storesByBookKey[bookKey] ?: emptyList()
                    val existingBook = findExistingImportBook(bookToImport)
                    if (existingBook == null) {
                        // New book
                        val newBookId = repository.insertBook(bookToImport)
                        booksImported++
                        val storesForNewBook = bookStores.map { it.copy(bookId = newBookId) }
                        storesForNewBook.forEach { store ->
                            when (repository.upsertBookStoreForImport(store)) {
                                BookRepository.StoreImportResult.Added -> storesImported++
                                BookRepository.StoreImportResult.Updated -> storesUpdated++
                                BookRepository.StoreImportResult.Unchanged -> storesUnchanged++
                            }
                        }
                    } else {
                        // Merge with existing book
                        val mergedBook = existingBook.copy(
                            isbn10 = existingBook.isbn10 ?: bookToImport.isbn10,
                            isbn13 = existingBook.isbn13 ?: bookToImport.isbn13,
                            category = if (existingBook.category == defaultCategoryName) bookToImport.category else existingBook.category,
                            coverImageUrl = existingBook.coverImageUrl ?: bookToImport.coverImageUrl,
                            language = existingBook.language ?: bookToImport.language,
                            pages = existingBook.pages ?: bookToImport.pages,
                            publisher = existingBook.publisher ?: bookToImport.publisher,
                            publishedDate = existingBook.publishedDate ?: bookToImport.publishedDate
                        )
                        repository.updateBook(mergedBook)
                        duplicatesMerged++
                        val storesForExistingBook = bookStores.map { it.copy(bookId = existingBook.id) }
                        storesForExistingBook.forEach { store ->
                            when (repository.upsertBookStoreForImport(store)) {
                                BookRepository.StoreImportResult.Added -> storesImported++
                                BookRepository.StoreImportResult.Updated -> storesUpdated++
                                BookRepository.StoreImportResult.Unchanged -> storesUnchanged++
                            }
                        }
                    }
                }
            }
            Log.d("BookTracker", "ViewModel: Import completed successfully:")
            Log.d("BookTracker", "ViewModel:   - Books imported: $booksImported")
            Log.d("BookTracker", "ViewModel:   - Stores imported: $storesImported")
            Log.d("BookTracker", "ViewModel:   - Stores updated: $storesUpdated")
            Log.d("BookTracker", "ViewModel:   - Stores unchanged: $storesUnchanged")
            Log.d("BookTracker", "ViewModel:   - Categories imported: $categoriesImported")
            Log.d("BookTracker", "ViewModel:   - Duplicates merged: $duplicatesMerged")
            ImportResult(
                booksImported = booksImported,
                storesImported = storesImported,
                storesUpdated = storesUpdated,
                storesUnchanged = storesUnchanged,
                categoriesImported = categoriesImported,
                duplicatesMerged = duplicatesMerged
            )
        } catch (e: Exception) {
            Log.e("BookTracker", "ViewModel: Import failed", e)
            recordUiException(e, "import_data")
            throw e
        }
    }

    suspend fun previewImport(books: List<Book>, categories: List<Category>, storesByBookKey: Map<String, List<BookStore>>): ImportPreview {
        Log.d("BookTracker", "ViewModel: Building import preview - Books: ${books.size}, Categories: ${categories.size}")
        var booksToAdd = 0
        var booksToMerge = 0
        var storesToAdd = 0
        var storesToUpdate = 0
        var storesUnchanged = 0
        var categoriesToAdd = 0

        categories.forEach { category ->
            if (repository.getCategoryByName(category.name) == null) {
                categoriesToAdd++
            }
        }

        books.forEach { bookToImport ->
            val bookStores = storesByBookKey[createBookKey(bookToImport)] ?: emptyList()
            val existingBook = findExistingImportBook(bookToImport)
            if (existingBook == null) {
                booksToAdd++
                storesToAdd += bookStores.size
            } else {
                booksToMerge++
                bookStores.forEach { importedStore ->
                    val existingStore = repository.getStoreByBookIdAndStoreName(existingBook.id, importedStore.storeName)
                    if (existingStore == null) {
                        storesToAdd++
                    } else {
                        val updatedStore = existingStore.copy(
                            storeUrl = importedStore.storeUrl,
                            price = importedStore.price,
                            currency = importedStore.currency,
                            availability = importedStore.availability,
                            lastUpdated = importedStore.lastUpdated
                        )
                        if (updatedStore != existingStore) {
                            storesToUpdate++
                        } else {
                            storesUnchanged++
                        }
                    }
                }
            }
        }

        return ImportPreview(
            booksToAdd = booksToAdd,
            booksToMerge = booksToMerge,
            storesToAdd = storesToAdd,
            storesToUpdate = storesToUpdate,
            storesUnchanged = storesUnchanged,
            categoriesToAdd = categoriesToAdd
        )
    }

    private suspend fun findExistingImportBook(book: Book): Book? {
        val isbn13 = book.isbn13
        val isbn10 = book.isbn10
        return when {
            !isbn13.isNullOrEmpty() -> repository.getBookByISBN13(isbn13)
            !isbn10.isNullOrEmpty() -> repository.getBookByISBN10(isbn10)
            else -> repository.getBookByTitleAndAuthor(book.title, book.author)
        }
    }

    private fun recordUiException(
        throwable: Throwable,
        source: String,
        extraKeys: Map<String, String> = emptyMap()
    ) {
        ErrorReporter.recordException(
            throwable,
            "UI workflow failed: $source",
            mapOf("source" to source) + extraKeys
        )
    }

    private fun hostFrom(url: String?): String {
        return runCatching { java.net.URL(url.orEmpty()).host }
            .getOrDefault("unknown")
            .ifBlank { "unknown" }
    }
    
    private fun createBookKey(book: Book): String {
        // Try to use ISBN first, then fall back to title+author
        return when {
            !book.isbn13.isNullOrEmpty() -> "isbn13:${book.isbn13}"
            !book.isbn10.isNullOrEmpty() -> "isbn10:${book.isbn10}"
            else -> "title:${book.title.lowercase(Locale.ROOT)}:author:${book.author.lowercase(Locale.ROOT)}"
        }
    }
    
    data class IsbnResult(
        val isbn10: String?,
        val isbn13: String?
    )
    
    data class ImportResult(
        val booksImported: Int,
        val storesImported: Int,
        val storesUpdated: Int,
        val storesUnchanged: Int,
        val categoriesImported: Int,
        val duplicatesMerged: Int
    )

    data class ImportPreview(
        val booksToAdd: Int,
        val booksToMerge: Int,
        val storesToAdd: Int,
        val storesToUpdate: Int,
        val storesUnchanged: Int,
        val categoriesToAdd: Int
    )
    
    /**
     * Set the last exported data to survive configuration changes
     */
    fun setLastExportedData(csvData: String, tempFile: java.io.File) {
        _lastExportedData.value = ExportedData(csvData, tempFile)
    }
    
    /**
     * Clear the last exported data and delete the temp file if it exists
     */
    fun clearLastExportedData() {
        _lastExportedData.value?.tempFile?.delete()
        _lastExportedData.value = null
    }
} 
