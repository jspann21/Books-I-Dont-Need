package com.booktracker.booksidntneed.repository

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.booktracker.booksidntneed.database.BookDatabase
import com.booktracker.booksidntneed.model.Book
import com.booktracker.booksidntneed.model.BookStore
import com.booktracker.booksidntneed.model.BookWithSortData
import com.booktracker.booksidntneed.model.BookWithStores
import com.booktracker.booksidntneed.model.Category
import com.booktracker.booksidntneed.network.ParsedBookInfo
import com.booktracker.booksidntneed.network.ScrapingProgressCallback
import com.booktracker.booksidntneed.network.WebScrapingService
import com.booktracker.booksidntneed.utils.ErrorReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Serializable
import java.util.Date

data class IsbnResult(val isbn10: String?, val isbn13: String?)

class BookRepository(
    private val database: BookDatabase,
    private val webScrapingService: WebScrapingService
) {
    private val bookDao = database.bookDao()
    private val bookStoreDao = database.bookStoreDao()
    private val categoryDao = database.categoryDao()
    
    suspend fun <T> runInTransaction(block: suspend () -> T): T {
        return database.withTransaction(block)
    }

    // New optimized filtering and sorting methods using DatabaseView
    fun getFilteredAndSortedBooks(
        sortOrder: com.booktracker.booksidntneed.ui.MainViewModel.SortOrder,
        category: String?
    ): LiveData<List<BookWithStores>> {
        val result = MediatorLiveData<List<BookWithStores>>()
        val sortedBookDataLiveData = getSortedBookData(sortOrder, category)

        var booksWithStoresSource: LiveData<List<BookWithStores>>? = null

        val sortedBookDataObserver = androidx.lifecycle.Observer<List<BookWithSortData>> { sortedList ->
            if (sortedList.isEmpty()) {
                result.value = emptyList()
                return@Observer
            }
            val sortedIds = sortedList.map { it.book.id }
            booksWithStoresSource?.let { result.removeSource(it) }
            val newSource = bookDao.getBooksWithStoresByIds(sortedIds)
            booksWithStoresSource = newSource
            result.addSource(newSource) { unsortedFullBooks ->
                val idToBookMap = unsortedFullBooks.associateBy { it.book.id }
                result.value = sortedIds.mapNotNull { idToBookMap[it] }
            }
        }

        result.addSource(sortedBookDataLiveData, sortedBookDataObserver)
        return result
    }

    private fun getSortedBookData(sortOrder: com.booktracker.booksidntneed.ui.MainViewModel.SortOrder, category: String?): LiveData<List<BookWithSortData>> {
        val sortColumn = when (sortOrder) {
            com.booktracker.booksidntneed.ui.MainViewModel.SortOrder.TITLE_ASC -> "title ASC"
            com.booktracker.booksidntneed.ui.MainViewModel.SortOrder.TITLE_DESC -> "title DESC"
            com.booktracker.booksidntneed.ui.MainViewModel.SortOrder.AUTHOR_ASC -> "author ASC"
            com.booktracker.booksidntneed.ui.MainViewModel.SortOrder.AUTHOR_DESC -> "author DESC"
            com.booktracker.booksidntneed.ui.MainViewModel.SortOrder.DATE_ADDED_ASC -> "dateAdded ASC"
            com.booktracker.booksidntneed.ui.MainViewModel.SortOrder.DATE_ADDED_DESC -> "dateAdded DESC"
            com.booktracker.booksidntneed.ui.MainViewModel.SortOrder.PRICE_ASC -> "lowestPrice ASC"
            com.booktracker.booksidntneed.ui.MainViewModel.SortOrder.PRICE_DESC -> "lowestPrice DESC"
            com.booktracker.booksidntneed.ui.MainViewModel.SortOrder.STORE_NAME_ASC -> "primaryStoreName ASC"
            com.booktracker.booksidntneed.ui.MainViewModel.SortOrder.STORE_NAME_DESC -> "primaryStoreName DESC"
        }

        // Use '?' for parameter binding to prevent SQL injection
        // null category means "All Categories" (no filtering)
        val query = if (category == null) {
            "SELECT * FROM BookWithSortDataView ORDER BY $sortColumn"
        } else {
            "SELECT * FROM BookWithSortDataView WHERE category = ? ORDER BY $sortColumn"
        }
        
        val simpleSQLiteQuery = if (category == null) {
            SimpleSQLiteQuery(query)
        } else {
            SimpleSQLiteQuery(query, arrayOf(category))
        }
        
        return bookDao.getBooksWithSortData(simpleSQLiteQuery)
    }

    suspend fun deleteBook(book: Book) {
        bookDao.deleteBook(book)
    }
    
    suspend fun updateBook(book: Book) {
        Log.d("BookTracker", "BookRepository: Updating book: ${book.title} (ID: ${book.id})")
        bookDao.updateBook(book)
    }
    
    // Category operations
    fun getAllCategories(): LiveData<List<Category>> {
        return categoryDao.getAllCategories()
    }
    
    suspend fun addCategory(category: Category) {
        Log.d("BookTracker", "BookRepository: Adding category: ${category.name}")
        categoryDao.insertCategory(category)
    }
    
    suspend fun deleteCategory(categoryName: String) {
        Log.d("BookTracker", "BookRepository: Deleting category: $categoryName")
        categoryDao.deleteCustomCategory(categoryName)
    }
    
    suspend fun updateCategory(oldName: String, updatedCategory: Category) {
        Log.d("BookTracker", "BookRepository: Updating category from '$oldName' to '${updatedCategory.name}'")
        // For updating category names, we need to handle books that reference this category
        if (oldName != updatedCategory.name) {
            // Update all books that use the old category name to use the new name
            bookDao.updateBooksCategory(oldName, updatedCategory.name)
        }
        // Update the category itself
        categoryDao.updateCategory(updatedCategory)
    }
    
    suspend fun getCategoryByName(name: String): Category? {
        Log.d("BookTracker", "BookRepository: Looking up category by name: $name")
        return categoryDao.getCategoryByName(name)
    }

    // Web scraping and book addition
    suspend fun addBookFromUrl(url: String, selectedCategory: String = "Uncategorized"): BookAddResult {
        return withContext(Dispatchers.IO) {
            try {
                when (val result = webScrapingService.scrapeBookInfo(url)) {
                    is WebScrapingService.ScrapingResult.Success -> {
                        val bookInfo = result.bookInfo
                        val existingBook = findExistingBookByISBN(bookInfo)
                        
                        if (existingBook != null) {
                            // Book exists, handle store information
                            return@withContext addStoreToExistingBook(existingBook.book.id, bookInfo)
                        } else {
                            // Create new book
                            val bookId = createNewBook(bookInfo, selectedCategory)
                            BookAddResult.Created(bookId)
                        }
                    }
                    is WebScrapingService.ScrapingResult.MultipleSellerOptions -> {
                        // Return a special result that tells UI to show seller selection dialog
                        BookAddResult.MultipleSellerOptionsFound(result)
                    }
                    is WebScrapingService.ScrapingResult.Error -> {
                        BookAddResult.Error(result.message)
                    }
                }
            } catch (e: Exception) {
                ErrorReporter.recordException(
                    e,
                    "Failed to add book from URL",
                    mapOf(
                        "source" to "repository_add_book_url",
                        "store_host" to hostFrom(url)
                    )
                )
                BookAddResult.Error("Failed to save book: ${e.message}")
            }
        }
    }
    
    // Add book from already parsed information (e.g., from seller selection)
    suspend fun addBookFromParsedInfo(bookInfo: ParsedBookInfo, selectedCategory: String = "Uncategorized"): BookAddResult {
        return withContext(Dispatchers.IO) {
            try {
                val existingBook = findExistingBookByISBN(bookInfo)
                
                if (existingBook != null) {
                    // Book exists, handle store information
                    addStoreToExistingBook(existingBook.book.id, bookInfo)
                } else {
                    // Create new book
                    val bookId = createNewBook(bookInfo, selectedCategory)
                    BookAddResult.Created(bookId)
                }
            } catch (e: Exception) {
                ErrorReporter.recordException(
                    e,
                    "Failed to add parsed book",
                    mapOf("source" to "repository_add_parsed_book")
                )
                BookAddResult.Error("Failed to save book: ${e.message}")
            }
        }
    }

    private suspend fun findExistingBookByISBN(bookInfo: ParsedBookInfo): BookWithStores? {
        // Check both ISBN-10 and ISBN-13 if available
        val isbn10 = bookInfo.isbn10
        val isbn13 = bookInfo.isbn13
        
        if (!isbn10.isNullOrBlank()) {
            val existingByISBN10 = bookDao.getBookByISBN(isbn10)
            if (existingByISBN10 != null) {
                return bookDao.getBookWithStores(existingByISBN10.id)
            }
        }
        
        if (!isbn13.isNullOrBlank()) {
            val existingByISBN13 = bookDao.getBookByISBN(isbn13)
            if (existingByISBN13 != null) {
                return bookDao.getBookWithStores(existingByISBN13.id)
            }
        }
        
        return null
    }
    
    private suspend fun addStoreToExistingBook(bookId: Long, bookInfo: ParsedBookInfo): BookAddResult {
        // First, enrich the existing book with any missing bibliographic data from the new source
        enrichMissingBibliographicData(bookId, bookInfo)
        
        // Check if this store already exists for this book
        val storeName = bookInfo.storeName ?: "Unknown Store"
        val existingStore = bookStoreDao.getStoreForBook(bookId, storeName)
        
        if (existingStore == null) {
            // Add new store entry - same book, new store
            val newStore = BookStore(
                bookId = bookId,
                storeName = storeName,
                storeUrl = bookInfo.storeUrl,
                price = bookInfo.price,
                currency = bookInfo.currency,
                dateAdded = Date(),
                lastUpdated = Date()
            )
            bookStoreDao.insertStore(newStore)
            return BookAddResult.Updated(bookId, storeName)
        } else {
            // Store already exists - check if information is different
            val priceChanged = existingStore.price != bookInfo.price
            val urlChanged = existingStore.storeUrl != bookInfo.storeUrl
            
            if (priceChanged || urlChanged) {
                // Update existing store entry with new information
                val updatedStore = existingStore.copy(
                    storeUrl = bookInfo.storeUrl,
                    price = bookInfo.price,
                    lastUpdated = Date()
                )
                bookStoreDao.updateStore(updatedStore)
                return BookAddResult.StoreUpdated(bookId, storeName)
            } else {
                // Exact duplicate - same book, same store, same info
                return BookAddResult.Duplicate(bookId, storeName)
            }
        }
    }
    
    private suspend fun enrichMissingBibliographicData(bookId: Long, newBookInfo: ParsedBookInfo) {
        val existingBook = bookDao.getBookById(bookId) ?: return
        
        var hasUpdates = false
        val enrichedFields = mutableListOf<String>()
        
        // Create updated book with enriched data (only update missing/empty fields)
        val updatedBook = existingBook.copy(
            // Update author if missing or appears to be a placeholder
            author = if (existingBook.author.isBlank() || 
                         existingBook.author.equals("Unknown Author", ignoreCase = true) ||
                         existingBook.author.equals("Author Not Available", ignoreCase = true)) {
                if (!newBookInfo.author.isNullOrBlank()) {
                    enrichedFields.add("author")
                    hasUpdates = true
                    newBookInfo.author
                } else existingBook.author
            } else existingBook.author,
            
            // Update ISBN-10 if missing
            isbn10 = if (existingBook.isbn10.isNullOrBlank() && !newBookInfo.isbn10.isNullOrBlank()) {
                enrichedFields.add("isbn10")
                hasUpdates = true
                newBookInfo.isbn10
            } else existingBook.isbn10,
            
            // Update ISBN-13 if missing
            isbn13 = if (existingBook.isbn13.isNullOrBlank() && !newBookInfo.isbn13.isNullOrBlank()) {
                enrichedFields.add("isbn13")
                hasUpdates = true
                newBookInfo.isbn13
            } else existingBook.isbn13,
            
            // Update cover image if missing or improve quality
            coverImageUrl = if (existingBook.coverImageUrl.isNullOrBlank() && !newBookInfo.coverImageUrl.isNullOrBlank()) {
                enrichedFields.add("coverImage")
                hasUpdates = true
                newBookInfo.coverImageUrl
            } else existingBook.coverImageUrl,
            
            // Update language if missing
            language = if (existingBook.language.isNullOrBlank() && !newBookInfo.language.isNullOrBlank()) {
                enrichedFields.add("language")
                hasUpdates = true
                newBookInfo.language
            } else existingBook.language,
            
            // Update pages if missing
            pages = if (existingBook.pages == null && newBookInfo.pages != null) {
                enrichedFields.add("pages")
                hasUpdates = true
                newBookInfo.pages
            } else existingBook.pages,
            
            // Update publisher if missing
            publisher = if (existingBook.publisher.isNullOrBlank() && !newBookInfo.publisher.isNullOrBlank()) {
                enrichedFields.add("publisher")
                hasUpdates = true
                newBookInfo.publisher
            } else existingBook.publisher,
            
            // Update published date if missing
            publishedDate = if (existingBook.publishedDate.isNullOrBlank() && !newBookInfo.publishedDate.isNullOrBlank()) {
                enrichedFields.add("publishedDate")
                hasUpdates = true
                newBookInfo.publishedDate
            } else existingBook.publishedDate
        )
        
        if (hasUpdates) {
            bookDao.updateBook(updatedBook)
            Log.d("BookTracker", "BookRepository: Enriched existing book (ID: $bookId) with missing data from ${newBookInfo.storeName}: ${enrichedFields.joinToString(", ")}")
        } else {
            Log.d("BookTracker", "BookRepository: No missing bibliographic data to enrich for book ID: $bookId")
        }
    }
    
    private suspend fun createNewBook(bookInfo: ParsedBookInfo, selectedCategory: String): Long {
        // Create the book
        val book = Book(
            title = bookInfo.title ?: "",
            author = bookInfo.author ?: "",
            isbn10 = bookInfo.isbn10,
            isbn13 = bookInfo.isbn13,
            coverImageUrl = bookInfo.coverImageUrl,
            category = selectedCategory,
            language = bookInfo.language,
            pages = bookInfo.pages,
            publisher = bookInfo.publisher,
            publishedDate = bookInfo.publishedDate,
            dateAdded = Date()
        )
        
        val bookId = bookDao.insertBook(book)
        
        // Create the store entry
        val store = BookStore(
            bookId = bookId,
            storeName = bookInfo.storeName ?: "Unknown Store",
            storeUrl = bookInfo.storeUrl,
            price = bookInfo.price,
            currency = bookInfo.currency,
            availability = bookInfo.availability,
            dateAdded = Date(),
            lastUpdated = Date()
        )
        
        bookStoreDao.insertStore(store)
        
        return bookId
    }

    suspend fun updateBookCategory(bookId: Long, newCategory: String) {
        val book = bookDao.getBookById(bookId)
        if (book != null) {
            val updatedBook = book.copy(category = newCategory)
            bookDao.updateBook(updatedBook)
        }
    }

    suspend fun deleteStore(store: BookStore) {
        bookStoreDao.deleteStore(store)
    }
    
    suspend fun updateStore(store: BookStore) {
        bookStoreDao.updateStore(store)
    }
    
    suspend fun findBookByTitleAndAuthor(title: String, author: String): BookWithStores? {
        return withContext(Dispatchers.IO) {
            // Find books with similar title and author (case-insensitive)
            val books = bookDao.getAllBooksSync()
            val matchingBook = books.find { book ->
                book.title.equals(title, ignoreCase = true) && 
                book.author.equals(author, ignoreCase = true)
            }
            
            if (matchingBook != null) {
                bookDao.getBookWithStores(matchingBook.id)
            } else {
                null
            }
        }
    }
    
    // Manual book addition without web scraping
    suspend fun addBookManually(
        title: String,
        author: String,
        isbn: String? = null,
        price: Double? = null,
        storeName: String = "Manual Entry",
        storeUrl: String? = null,
        category: String = "Uncategorized"
    ): BookAddResult {
        return withContext(Dispatchers.IO) {
            try {
                // Validate required fields
                if (title.isBlank() || author.isBlank()) {
                    return@withContext BookAddResult.Error("Title and author are required")
                }
                
                // Determine if ISBN is 10 or 13 digits
                val isbnResult = parseISBN(isbn)
                val isbn10 = isbnResult.isbn10
                val isbn13 = isbnResult.isbn13
                
                // Check if book already exists by ISBN (check both ISBN-10 and ISBN-13)
                if (!isbn10.isNullOrBlank()) {
                    val existingByISBN10 = bookDao.getBookByISBN(isbn10)
                    if (existingByISBN10 != null) {
                        return@withContext addStoreToExistingBookManual(
                            existingByISBN10.id, storeName, storeUrl, price
                        )
                    }
                }
                
                if (!isbn13.isNullOrBlank()) {
                    val existingByISBN13 = bookDao.getBookByISBN(isbn13)
                    if (existingByISBN13 != null) {
                        return@withContext addStoreToExistingBookManual(
                            existingByISBN13.id, storeName, storeUrl, price
                        )
                    }
                }
                
                // If no ISBN match, check for title/author match
                val existingBookByTitleAuthor = findBookByTitleAndAuthor(title, author)
                if (existingBookByTitleAuthor != null) {
                    // Found potential duplicate by title/author - return for user decision
                    return@withContext BookAddResult.TitleAuthorDuplicate(
                        existingBookWithStores = existingBookByTitleAuthor,
                        newTitle = title,
                        newAuthor = author,
                        newIsbn = isbn,
                        newPrice = price,
                        newStoreName = storeName,
                        newStoreUrl = storeUrl,
                        newCategory = category
                    )
                }
                
                // Create new book
                val book = Book(
                    title = title.trim(),
                    author = author.trim(),
                    isbn10 = isbn10,
                    isbn13 = isbn13,
                    category = category,
                    dateAdded = Date()
                )
                
                val bookId = bookDao.insertBook(book)
                
                // Create store entry
                val store = BookStore(
                    bookId = bookId,
                    storeName = storeName,
                    storeUrl = storeUrl ?: "",
                    price = price,
                    currency = "USD",
                    dateAdded = Date(),
                    lastUpdated = Date()
                )
                
                bookStoreDao.insertStore(store)
                
                BookAddResult.Created(bookId)
            } catch (e: Exception) {
                ErrorReporter.recordException(
                    e,
                    "Failed to add manual book",
                    mapOf("source" to "repository_add_manual_book")
                )
                BookAddResult.Error("Failed to save book: ${e.message}")
            }
        }
    }
    
    // Force add to existing book (user confirmed they're the same)
    suspend fun addToExistingBook(
        existingBookId: Long,
        storeName: String,
        storeUrl: String?,
        price: Double?
    ): BookAddResult {
        return withContext(Dispatchers.IO) {
            try {
                addStoreToExistingBookManual(existingBookId, storeName, storeUrl, price)
            } catch (e: Exception) {
                ErrorReporter.recordException(
                    e,
                    "Failed to add store to existing book",
                    mapOf("source" to "repository_add_existing_book")
                )
                BookAddResult.Error("Failed to add store to existing book: ${e.message}")
            }
        }
    }
    
    // Force add as new book (user confirmed they're different)
    suspend fun forceAddAsNewBook(
        title: String,
        author: String,
        isbn: String?,
        price: Double?,
        storeName: String,
        storeUrl: String?,
        category: String
    ): BookAddResult {
        return withContext(Dispatchers.IO) {
            try {
                // Determine if ISBN is 10 or 13 digits
                val isbnResult = parseISBN(isbn)
                val isbn10 = isbnResult.isbn10
                val isbn13 = isbnResult.isbn13
                
                // Create new book (skip duplicate checks)
                val book = Book(
                    title = title.trim(),
                    author = author.trim(),
                    isbn10 = isbn10,
                    isbn13 = isbn13,
                    category = category,
                    dateAdded = Date()
                )
                
                val bookId = bookDao.insertBook(book)
                
                // Create store entry
                val store = BookStore(
                    bookId = bookId,
                    storeName = storeName,
                    storeUrl = storeUrl ?: "",
                    price = price,
                    currency = "USD",
                    dateAdded = Date(),
                    lastUpdated = Date()
                )
                
                bookStoreDao.insertStore(store)
                
                BookAddResult.Created(bookId)
            } catch (e: Exception) {
                ErrorReporter.recordException(
                    e,
                    "Failed to force add book",
                    mapOf("source" to "repository_force_add_book")
                )
                BookAddResult.Error("Failed to save book: ${e.message}")
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
    
    private suspend fun addStoreToExistingBookManual(
        bookId: Long, 
        storeName: String, 
        storeUrl: String?, 
        price: Double?
    ): BookAddResult {
        // Check if this store already exists for this book
        val existingStore = bookStoreDao.getStoreForBook(bookId, storeName)
        
        if (existingStore == null) {
            // Add new store entry
            val newStore = BookStore(
                bookId = bookId,
                storeName = storeName,
                storeUrl = storeUrl ?: "",
                price = price,
                currency = "USD",
                dateAdded = Date(),
                lastUpdated = Date()
            )
            bookStoreDao.insertStore(newStore)
            return BookAddResult.Updated(bookId, storeName)
        } else {
            // Store exists - check if we should update it
            val priceChanged = existingStore.price != price
            val urlChanged = existingStore.storeUrl != (storeUrl ?: "")
            
            if (priceChanged || urlChanged) {
                val updatedStore = existingStore.copy(
                    storeUrl = storeUrl ?: existingStore.storeUrl,
                    price = price,
                    lastUpdated = Date()
                )
                bookStoreDao.updateStore(updatedStore)
                return BookAddResult.StoreUpdated(bookId, storeName)
            } else {
                return BookAddResult.Duplicate(bookId, storeName)
            }
        }
    }
    
    suspend fun updateSingleStorePrices(store: BookStore, progressCallback: ScrapingProgressCallback? = null): SingleStoreUpdateResult {
        return withContext(Dispatchers.IO) {
            try {
                // Check if store has a valid URL to scrape
                if (store.storeUrl.isBlank() || store.storeUrl == "Manual Entry") {
                    return@withContext SingleStoreUpdateResult.Skipped("No valid URL to scrape")
                }
                
                when (val result = webScrapingService.scrapeBookInfo(store.storeUrl, progressCallback)) {
                    is WebScrapingService.ScrapingResult.Success -> {
                        val bookInfo = result.bookInfo
                        // Update the store with new price information
                        progressCallback?.onTaskProgress("Updating Database", 50)
                        val updatedStore = store.copy(
                            price = bookInfo.price,
                            availability = bookInfo.availability,
                            lastUpdated = Date()
                        )
                        bookStoreDao.updateStore(updatedStore)
                        progressCallback?.onTaskProgress("Updating Database", 100)
                        progressCallback?.onTaskCompleted("Updating Database")
                        
                        return@withContext SingleStoreUpdateResult.Success(bookInfo.price)
                    }
                    is WebScrapingService.ScrapingResult.MultipleSellerOptions -> {
                        // Can't automatically update when multiple sellers are available
                        return@withContext SingleStoreUpdateResult.Failed("Multiple sellers found - manual selection required")
                    }
                    is WebScrapingService.ScrapingResult.Error -> {
                        return@withContext SingleStoreUpdateResult.Failed(result.message)
                    }
                }
                
            } catch (e: Exception) {
                ErrorReporter.recordException(
                    e,
                    "Failed to update one store price",
                    mapOf(
                        "source" to "repository_update_store_price",
                        "store_host" to hostFrom(store.storeUrl)
                    )
                )
                return@withContext SingleStoreUpdateResult.Failed(e.message ?: "Unknown error occurred")
            }
        }
    }

    sealed class BookAddResult : Serializable {
        data class Created(val bookId: Long) : BookAddResult()
        data class Updated(val bookId: Long, val storeName: String) : BookAddResult()
        data class StoreUpdated(val bookId: Long, val storeName: String) : BookAddResult()
        data class Duplicate(val bookId: Long, val storeName: String) : BookAddResult()
        data class TitleAuthorDuplicate(
            val existingBookWithStores: BookWithStores,
            val newTitle: String,
            val newAuthor: String,
            val newIsbn: String?,
            val newPrice: Double?,
            val newStoreName: String,
            val newStoreUrl: String?,
            val newCategory: String
        ) : BookAddResult(), Serializable
        data class MultipleSellerOptionsFound(
            val multipleSellerOptions: WebScrapingService.ScrapingResult.MultipleSellerOptions
        ) : BookAddResult()
        data class Error(val message: String) : BookAddResult()
    }

    sealed class SingleStoreUpdateResult {
        data class Success(val newPrice: Double?) : SingleStoreUpdateResult()
        data class Failed(val errorMessage: String) : SingleStoreUpdateResult()
        data class Skipped(val reason: String) : SingleStoreUpdateResult()
    }

    // Export/Import helpers
    suspend fun getAllBooksForExport(): List<BookWithStores> {
        Log.d("BookTracker", "BookRepository: Getting all books with stores for export")
        return bookDao.getAllBooksForExport()
    }
    
    suspend fun getBookByISBN13(isbn13: String): Book? {
        Log.d("BookTracker", "BookRepository: Looking up book by ISBN-13: $isbn13")
        return bookDao.getBookByISBN13(isbn13)
    }
    
    suspend fun getBookByISBN10(isbn10: String): Book? {
        Log.d("BookTracker", "BookRepository: Looking up book by ISBN-10: $isbn10")
        return bookDao.getBookByISBN10(isbn10)
    }
    
    suspend fun getBookByTitleAndAuthor(title: String, author: String): Book? {
        Log.d("BookTracker", "BookRepository: Looking up book by title and author: '$title' by '$author'")
        return bookDao.getBookByTitleAndAuthor(title, author)
    }

    suspend fun insertBookStoresInTransaction(bookStores: List<BookStore>) {
        Log.d("BookTracker", "BookRepository: Bulk inserting ${bookStores.size} book stores in transaction")
        bookStoreDao.insertStoresInTransaction(bookStores)
    }

    suspend fun upsertBookStoreForImport(bookStore: BookStore): StoreImportResult {
        val existingStore = bookStoreDao.getStoreForBook(bookStore.bookId, bookStore.storeName)
        if (existingStore == null) {
            bookStoreDao.insertStore(bookStore)
            return StoreImportResult.Added
        }

        val updatedStore = existingStore.copy(
            storeUrl = bookStore.storeUrl,
            price = bookStore.price,
            currency = bookStore.currency,
            availability = bookStore.availability,
            lastUpdated = bookStore.lastUpdated
        )

        return if (updatedStore != existingStore) {
            bookStoreDao.updateStore(updatedStore)
            StoreImportResult.Updated
        } else {
            StoreImportResult.Unchanged
        }
    }
    
    suspend fun insertCategory(category: Category) {
        Log.d("BookTracker", "BookRepository: Inserting category: ${category.name}")
        categoryDao.insertCategory(category)
    }
    
    suspend fun getStoreByBookIdAndStoreName(bookId: Long, storeName: String): BookStore? {
        Log.d("BookTracker", "BookRepository: Looking up store by book ID $bookId and store name: $storeName")
        return bookStoreDao.getStoreForBook(bookId, storeName)
    }

    suspend fun insertBook(book: Book): Long {
        Log.d("BookTracker", "BookRepository: Inserting new book: ${book.title}")
        val bookId = bookDao.insertBook(book)
        Log.d("BookTracker", "BookRepository: New book inserted with ID: $bookId")
        return bookId
    }

    private fun hostFrom(url: String?): String {
        return runCatching { java.net.URL(url.orEmpty()).host }
            .getOrDefault("unknown")
            .ifBlank { "unknown" }
    }

    enum class StoreImportResult {
        Added,
        Updated,
        Unchanged
    }
} 
