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
import java.io.Writer
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
    fun exportToCSV(booksWithStores: List<BookWithStores>, writer: Writer) {
        Log.d("BookTracker", "DataExportService: Starting CSV export for ${booksWithStores.size} books")

        writer.append(CSV_HEADER).append('\n')
        
        val dateFormat = SimpleDateFormat(DATE_FORMAT, Locale.US)
        
        var totalRows = 0
        booksWithStores.forEach { bookWithStores ->
            val book = bookWithStores.book

            if (bookWithStores.stores.isEmpty()) {
                // Book with no stores - add a single row
                val bookRow = buildBookRow(book, null, dateFormat)
                writer.append(bookRow).append('\n')
                totalRows++
            } else {
                // Book with stores - add a row for each store
                bookWithStores.stores.forEach { store ->
                    val bookRow = buildBookRow(book, store, dateFormat)
                    writer.append(bookRow).append('\n')
                    totalRows++
                }
            }
        }

        Log.d("BookTracker", "DataExportService: CSV export completed. Total rows: $totalRows")
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
                ErrorReporter.recordException(
                    IllegalStateException("Failed to open import file"),
                    "CSV import failed before parsing",
                    mapOf(
                        "source" to "csv_import",
                        "import_phase" to "open_input_stream",
                        "uri_scheme" to (uri.scheme ?: "unknown")
                    )
                )
                return ImportResult.Error("Failed to open file")
            }
            
            val reader = BufferedReader(InputStreamReader(inputStream))
            
            val books = mutableMapOf<String, Book>() // Key: ISBN or title+author
            val storesByBookKey = mutableMapOf<String, MutableList<BookStore>>() // Key: book key, Value: list of stores
            val categories = mutableSetOf<Category>()
            val dateFormat = SimpleDateFormat(DATE_FORMAT, Locale.US)
            
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
                            headerSkipped = true
                            continue
                        }

                        val fields = parseCsvLine(trimmedLine)
                        
                        if (fields.size >= 12) {
                            val book = parseBookFromCsv(fields, dateFormat)
                            val store = if (fields.size >= 20) parseStoreFromCsv(fields, dateFormat) else null
                            
                            // Create unique key for book identification
                            val bookKey = createBookKey(book)

                            if (bookKey !in books) {
                                books[bookKey] = book
                                categories.add(Category(book.category))
                                storesByBookKey[bookKey] = mutableListOf()
                            }

                            if (store != null) {
                                storesByBookKey[bookKey]?.add(store)
                            }
                        } else {
                            Log.w("BookTracker", "DataExportService: Line $lineNumber has insufficient fields (${fields.size}), skipping")
                        }
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
            ErrorReporter.recordException(
                e,
                "CSV import parsing failed",
                mapOf(
                    "source" to "csv_import",
                    "import_phase" to "parse_csv",
                    "uri_scheme" to (uri.scheme ?: "unknown")
                )
            )
            ImportResult.Error("Failed to import CSV: ${e.message}")
        }
    }
    
    private fun parseCsvLine(line: String): List<String> {
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
                        } else {
                            // End of quoted field
                            insideQuotes = false
                        }
                    } else {
                        insideQuotes = true
                    }
                }
                ',' -> {
                    if (!insideQuotes) {
                        fields.add(currentField.toString())
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
        return fields
    }
    
    private fun parseBookFromCsv(fields: List<String>, dateFormat: SimpleDateFormat): Book {
        
        val title = fields.getOrNull(1)?.trim() ?: ""
        val author = fields.getOrNull(2)?.trim() ?: ""
        val isbn10 = fields.getOrNull(3)?.trim().takeIf { it?.isNotEmpty() == true }
        val isbn13 = fields.getOrNull(4)?.trim().takeIf { it?.isNotEmpty() == true }
        val category = fields.getOrNull(5)?.trim() ?: "Uncategorized"
        
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
        
        return book
    }
    
    private fun parseStoreFromCsv(fields: List<String>, dateFormat: SimpleDateFormat): BookStore? {
        
        val storeName = fields.getOrNull(13)?.trim()
        val storeUrl = fields.getOrNull(14)?.trim()
        
        if (storeName.isNullOrEmpty() || storeUrl.isNullOrEmpty()) {
            Log.w("BookTracker", "DataExportService: Store name or URL is empty, skipping store")
            return null
        }
        
        val price = fields.getOrNull(15)?.trim()?.toDoubleOrNull()
        val currency = fields.getOrNull(16)?.trim() ?: "USD"
        
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
        
        return store
    }
    
    private fun createBookKey(book: Book): String {
        // Try to use ISBN first, then fall back to title+author
        val key = when {
            !book.isbn13.isNullOrEmpty() -> "isbn13:${book.isbn13}"
            !book.isbn10.isNullOrEmpty() -> "isbn10:${book.isbn10}"
            else -> "title:${book.title.lowercase(Locale.ROOT)}:author:${book.author.lowercase(Locale.ROOT)}"
        }
        
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
