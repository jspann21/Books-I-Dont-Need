package com.booktracker.booksidntneed.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import com.booktracker.booksidntneed.model.Book
import com.booktracker.booksidntneed.model.BookStore
import com.booktracker.booksidntneed.model.BookWithStores
import com.booktracker.booksidntneed.model.Category
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DataExportService(private val context: Context) {
    
    companion object {
        private const val CSV_HEADER = "Book ID,Title,Author,ISBN-10,ISBN-13,Category,Cover Image URL,Language,Pages,Publisher,Published Date,Date Added,Store ID,Store Name,Store URL,Price,Currency,Availability,Store Date Added,Store Last Updated"
        private const val DATE_FORMAT = "yyyy-MM-dd HH:mm:ss"
        private const val CSV_SEPARATOR = ","
        private const val CSV_QUOTE = "\""
    }
    
    /**
     * Export all book data to CSV format
     */
    fun exportToCSV(booksWithStores: List<BookWithStores>): String {
        Log.d("BookTracker", "DataExportService: Starting CSV export for ${booksWithStores.size} books")
        
        val csvBuilder = StringBuilder()
        csvBuilder.append(CSV_HEADER).append("\n")
        
        val dateFormat = SimpleDateFormat(DATE_FORMAT, Locale.US)
        
        var totalRows = 0
        booksWithStores.forEach { bookWithStores ->
            val book = bookWithStores.book
            Log.d("BookTracker", "DataExportService: Processing book: ${book.title} (ID: ${book.id}) with ${bookWithStores.stores.size} stores")
            
            if (bookWithStores.stores.isEmpty()) {
                // Book with no stores - add a single row
                val bookRow = buildBookRow(book, null, dateFormat)
                csvBuilder.append(bookRow).append("\n")
                totalRows++
                Log.d("BookTracker", "DataExportService: Added book row for ${book.title} (no stores)")
            } else {
                // Book with stores - add a row for each store
                bookWithStores.stores.forEach { store ->
                    val bookRow = buildBookRow(book, store, dateFormat)
                    csvBuilder.append(bookRow).append("\n")
                    totalRows++
                    Log.d("BookTracker", "DataExportService: Added book row for ${book.title} with store: ${store.storeName}")
                }
            }
        }
        
        Log.d("BookTracker", "DataExportService: CSV export completed. Total rows: $totalRows")
        return csvBuilder.toString()
    }
    
    private fun buildBookRow(book: Book, store: BookStore?, dateFormat: SimpleDateFormat): String {
        val row = listOf(
            book.id.toString(),
            escapeCsvField(book.title),
            escapeCsvField(book.author),
            escapeCsvField(book.isbn10 ?: ""),
            escapeCsvField(book.isbn13 ?: ""),
            escapeCsvField(book.category),
            escapeCsvField(book.coverImageUrl ?: ""),
            escapeCsvField(book.language ?: ""),
            book.pages?.toString() ?: "",
            escapeCsvField(book.publisher ?: ""),
            escapeCsvField(book.publishedDate ?: ""),
            dateFormat.format(book.dateAdded),
            store?.id?.toString() ?: "",
            escapeCsvField(store?.storeName ?: ""),
            escapeCsvField(store?.storeUrl ?: ""),
            store?.price?.toString() ?: "",
            store?.currency ?: "",
            escapeCsvField(store?.availability ?: ""),
            store?.dateAdded?.let { dateFormat.format(it) } ?: "",
            store?.lastUpdated?.let { dateFormat.format(it) } ?: ""
        ).joinToString(CSV_SEPARATOR)
        
        Log.v("BookTracker", "DataExportService: Built CSV row: ${row.take(100)}...")
        return row
    }
    
    private fun escapeCsvField(field: String): String {
        return if (field.contains(CSV_SEPARATOR) || field.contains(CSV_QUOTE) || field.contains("\n")) {
            "$CSV_QUOTE${field.replace(CSV_QUOTE, "$CSV_QUOTE$CSV_QUOTE")}$CSV_QUOTE"
        } else {
            field
        }
    }
    
    /**
     * Import book data from CSV format
     */
    fun importFromCSV(uri: Uri): ImportResult {
        Log.d("BookTracker", "DataExportService: Starting CSV import from URI: $uri")
        
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Log.e("BookTracker", "DataExportService: Failed to open input stream for URI: $uri")
                return ImportResult.Error("Failed to open file")
            }
            
            val reader = BufferedReader(InputStreamReader(inputStream))
            
            val books = mutableMapOf<String, Book>() // Key: ISBN or title+author
            val storesByBookKey = mutableMapOf<String, MutableList<BookStore>>() // Key: book key, Value: list of stores
            val categories = mutableSetOf<Category>()
            
            var lineNumber = 0
            var headerSkipped = false
            
            Log.d("BookTracker", "DataExportService: Reading CSV file...")
            
            reader.use { br ->
                var line: String?
                while (br.readLine().also { line = it } != null) {
                    lineNumber++
                    val trimmedLine = line?.trim()
                    
                    if (!trimmedLine.isNullOrEmpty()) {
                        if (!headerSkipped) {
                            Log.d("BookTracker", "DataExportService: Skipping header line: ${trimmedLine.take(50)}...")
                            headerSkipped = true
                            continue
                        }
                        
                        Log.v("BookTracker", "DataExportService: Processing line $lineNumber: ${trimmedLine.take(50)}...")
                        
                        val fields = parseCsvLine(trimmedLine)
                        
                        if (fields.size >= 12) {
                            val book = parseBookFromCsv(fields)
                            val store = if (fields.size >= 20) parseStoreFromCsv(fields) else null
                            
                            Log.d("BookTracker", "DataExportService: Parsed book: ${book.title} by ${book.author}")
                            if (store != null) {
                                Log.d("BookTracker", "DataExportService: Parsed store: ${store.storeName} for ${store.price}")
                            }
                            
                            // Create unique key for book identification
                            val bookKey = createBookKey(book)
                            Log.d("BookTracker", "DataExportService: Book key: $bookKey")
                            
                            if (bookKey !in books) {
                                books[bookKey] = book
                                categories.add(Category(book.category))
                                storesByBookKey[bookKey] = mutableListOf()
                                Log.d("BookTracker", "DataExportService: Added new book: ${book.title}")
                            } else {
                                Log.d("BookTracker", "DataExportService: Book already exists, will merge: ${book.title}")
                            }
                            
                            if (store != null) {
                                storesByBookKey[bookKey]?.add(store)
                                Log.d("BookTracker", "DataExportService: Added store: ${store.storeName}")
                            }
                        } else {
                            Log.w("BookTracker", "DataExportService: Line $lineNumber has insufficient fields (${fields.size}), skipping")
                        }
                    } else {
                        Log.v("BookTracker", "DataExportService: Skipping blank line $lineNumber")
                    }
                }
            }
            
            // Flatten stores list for the result
            val stores = storesByBookKey.values.flatten()
            
            Log.d("BookTracker", "DataExportService: CSV parsing completed:")
            Log.d("BookTracker", "DataExportService:   - Books found: ${books.size}")
            Log.d("BookTracker", "DataExportService:   - Stores found: ${stores.size}")
            Log.d("BookTracker", "DataExportService:   - Categories found: ${categories.size}")
            Log.d("BookTracker", "DataExportService:   - Total lines processed: $lineNumber")
            
            ImportResult.Success(
                books = books.values.toList(),
                stores = stores,
                categories = categories.toList(),
                storesByBookKey = storesByBookKey
            )
            
        } catch (e: Exception) {
            Log.e("BookTracker", "DataExportService: Failed to import CSV", e)
            ImportResult.Error("Failed to import CSV: ${e.message}")
        }
    }
    
    private fun parseCsvLine(line: String): List<String> {
        Log.v("BookTracker", "DataExportService: Parsing CSV line: ${line.take(100)}...")
        
        val fields = mutableListOf<String>()
        val currentField = StringBuilder()
        var insideQuotes = false
        var i = 0
        
        while (i < line.length) {
            when (val char = line[i]) {
                '"' -> {
                    if (insideQuotes) {
                        if (i + 1 < line.length && line[i + 1] == '"') {
                            // Escaped quote
                            currentField.append('"')
                            i++ // Skip next quote
                            Log.v("BookTracker", "DataExportService: Found escaped quote at position $i")
                        } else {
                            // End of quoted field
                            insideQuotes = false
                            Log.v("BookTracker", "DataExportService: Exiting quoted field at position $i")
                        }
                    } else {
                        insideQuotes = true
                        Log.v("BookTracker", "DataExportService: Entering quoted field at position $i")
                    }
                }
                ',' -> {
                    if (!insideQuotes) {
                        fields.add(currentField.toString())
                        Log.v("BookTracker", "DataExportService: Added field: ${currentField.toString().take(20)}...")
                        currentField.clear()
                    } else {
                        currentField.append(char)
                    }
                }
                else -> currentField.append(char)
            }
            i++
        }
        
        // Add the last field
        fields.add(currentField.toString())
        Log.v("BookTracker", "DataExportService: Added final field: ${currentField.toString().take(20)}...")
        
        Log.d("BookTracker", "DataExportService: Parsed ${fields.size} fields from CSV line")
        return fields
    }
    
    private fun parseBookFromCsv(fields: List<String>): Book {
        Log.d("BookTracker", "DataExportService: Parsing book from ${fields.size} fields")
        
        val dateFormat = SimpleDateFormat(DATE_FORMAT, Locale.US)
        
        val title = fields.getOrNull(1)?.trim() ?: ""
        val author = fields.getOrNull(2)?.trim() ?: ""
        val isbn10 = fields.getOrNull(3)?.trim().takeIf { it?.isNotEmpty() == true }
        val isbn13 = fields.getOrNull(4)?.trim().takeIf { it?.isNotEmpty() == true }
        val category = fields.getOrNull(5)?.trim() ?: "Uncategorized"
        
        Log.d("BookTracker", "DataExportService: Book details - Title: '$title', Author: '$author', Category: '$category'")
        Log.d("BookTracker", "DataExportService: Book ISBNs - ISBN-10: '$isbn10', ISBN-13: '$isbn13'")
        
        val book = Book(
            id = 0, // Will be set by database
            title = title,
            author = author,
            isbn10 = isbn10,
            isbn13 = isbn13,
            category = category,
            coverImageUrl = fields.getOrNull(6)?.trim().takeIf { it?.isNotEmpty() == true },
            language = fields.getOrNull(7)?.trim().takeIf { it?.isNotEmpty() == true },
            pages = fields.getOrNull(8)?.trim()?.toIntOrNull(),
            publisher = fields.getOrNull(9)?.trim().takeIf { it?.isNotEmpty() == true },
            publishedDate = fields.getOrNull(10)?.trim().takeIf { it?.isNotEmpty() == true },
            dateAdded = try {
                fields.getOrNull(11)?.trim()?.let { dateFormat.parse(it) } ?: Date()
            } catch (e: Exception) {
                Log.w("BookTracker", "DataExportService: Failed to parse date: ${fields.getOrNull(11)}, using current date", e)
                Date()
            }
        )
        
        Log.d("BookTracker", "DataExportService: Successfully parsed book: ${book.title}")
        return book
    }
    
    private fun parseStoreFromCsv(fields: List<String>): BookStore? {
        Log.d("BookTracker", "DataExportService: Parsing store from ${fields.size} fields")
        
        val dateFormat = SimpleDateFormat(DATE_FORMAT, Locale.US)
        
        val storeName = fields.getOrNull(13)?.trim()
        val storeUrl = fields.getOrNull(14)?.trim()
        
        if (storeName.isNullOrEmpty() || storeUrl.isNullOrEmpty()) {
            Log.w("BookTracker", "DataExportService: Store name or URL is empty, skipping store")
            return null
        }
        
        val price = fields.getOrNull(15)?.trim()?.toDoubleOrNull()
        val currency = fields.getOrNull(16)?.trim() ?: "USD"
        
        Log.d("BookTracker", "DataExportService: Store details - Name: '$storeName', URL: '$storeUrl', Price: $price $currency")
        
        val store = BookStore(
            id = 0, // Will be set by database
            bookId = 0, // Will be set later
            storeName = storeName,
            storeUrl = storeUrl,
            price = price,
            currency = currency,
            availability = fields.getOrNull(17)?.trim().takeIf { it?.isNotEmpty() == true },
            dateAdded = try {
                fields.getOrNull(18)?.trim()?.let { dateFormat.parse(it) } ?: Date()
            } catch (e: Exception) {
                Log.w("BookTracker", "DataExportService: Failed to parse store date added: ${fields.getOrNull(18)}, using current date", e)
                Date()
            },
            lastUpdated = try {
                fields.getOrNull(19)?.trim()?.let { dateFormat.parse(it) } ?: Date()
            } catch (e: Exception) {
                Log.w("BookTracker", "DataExportService: Failed to parse store last updated: ${fields.getOrNull(19)}, using current date", e)
                Date()
            }
        )
        
        Log.d("BookTracker", "DataExportService: Successfully parsed store: ${store.storeName}")
        return store
    }
    
    private fun createBookKey(book: Book): String {
        // Try to use ISBN first, then fall back to title+author
        val key = when {
            !book.isbn13.isNullOrEmpty() -> "isbn13:${book.isbn13}"
            !book.isbn10.isNullOrEmpty() -> "isbn10:${book.isbn10}"
            else -> "title:${book.title.lowercase(Locale.ROOT)}:author:${book.author.lowercase(Locale.ROOT)}"
        }
        
        Log.d("BookTracker", "DataExportService: Created book key: $key for book: ${book.title}")
        return key
    }
    
    sealed class ImportResult {
        data class Success(
            val books: List<Book>,
            val stores: List<BookStore>,
            val categories: List<Category>,
            val storesByBookKey: Map<String, List<BookStore>>
        ) : ImportResult()
        
        data class Error(val message: String) : ImportResult()
    }
} 
