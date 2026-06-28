package com.booktracker.booksidntneed.network

import android.util.Log
import org.jsoup.nodes.Document
import kotlinx.parcelize.Parcelize
import android.os.Parcelable

@Parcelize
data class SellerOption(
    val sellerName: String,
    val price: Double,
    val condition: String?,
    val location: String?
) : Parcelable

class AbeBooksParser : BookParser {
    
    class MultipleSellerException(
        val sellerOptions: List<SellerOption>,
        val bookTitle: String,
        val bookAuthor: String?,
        val bookIsbn: Pair<String?, String?>,
        val coverImageUrl: String?
    ) : Exception("Multiple sellers found - selection required")
    
    override fun canParse(url: String): Boolean {
        return url.contains("abebooks.com", ignoreCase = true)
    }
    
    override fun getStoreName(): String = "AbeBooks"
    
    override fun parse(document: Document, url: String): ParsedBookInfo? {
        try {
            Log.d("BookTracker", "AbeBooksParser: Starting to parse URL: $url")
            
            val title = extractTitle(document)
            val author = extractAuthor(document)
            val isbn = extractISBN(document, url)
            val coverImage = extractCoverImage(document)
            
            // Check for multiple sellers first, before trying to extract price
            val documentText = document.text()
            val isSearchResults = documentText.contains("Search results for", ignoreCase = true) ||
                                  documentText.contains("View all copies", ignoreCase = true) ||
                                  (documentText.contains("Buy Used US$", ignoreCase = true) && 
                                   documentText.contains("Seller:", ignoreCase = true) &&
                                   documentText.split("Buy Used US$").size > 2)
            
            if (isSearchResults) {
                val sellerOptions = extractSellerOptionsFromSearchResults(documentText)
                if (sellerOptions.size > 1 && !title.isNullOrBlank()) {
                    Log.d("BookTracker", "AbeBooksParser: Throwing MultipleSellerException for UI handling")
                    throw MultipleSellerException(
                        sellerOptions = sellerOptions,
                        bookTitle = title,
                        bookAuthor = author,
                        bookIsbn = isbn,
                        coverImageUrl = coverImage
                    )
                }
            }
            
            // Normal single seller processing
            val price = extractPrice(document)
            
            Log.d("BookTracker", "AbeBooksParser: Extracted data - Title: '$title', Author: '$author', Price: $price")
            
            if (title.isNullOrBlank()) {
                Log.w("BookTracker", "AbeBooksParser: Missing required title")
                return null
            }
            
            val bookInfo = ParsedBookInfo(
                title = title,
                author = author,
                isbn10 = isbn.first,
                isbn13 = isbn.second,
                price = price,
                storeName = getStoreName(),
                storeUrl = url,
                coverImageUrl = coverImage
            )
            
            Log.d("BookTracker", "AbeBooksParser: Successfully created ParsedBookInfo")
            return bookInfo
            
        } catch (e: MultipleSellerException) {
            // Re-throw to be handled by UI
            throw e
        } catch (e: Exception) {
            Log.e("BookTracker", "AbeBooksParser: Exception during parsing", e)
            return null
        }
    }
    
    private fun extractTitle(document: Document): String? {
        Log.d("BookTracker", "AbeBooksParser: Starting title extraction")
        
        // AbeBooks title selectors (in order of priority)
        val titleSelectors = listOf(
            "h1", // Main book title
            ".book-title",
            "[data-cy='listing-title']",
            ".listing-title",
            "title" // Page title fallback
        )
        
        for (selector in titleSelectors) {
            val title = document.select(selector).first()?.text()?.trim()
            if (!title.isNullOrBlank() && title.length > 3 && !title.contains("AbeBooks")) {
                Log.d("BookTracker", "AbeBooksParser: Found title with selector '$selector': '$title'")
                return cleanTitle(title)
            }
        }
        
        // Try meta tags
        val metaTitle = document.select("meta[property='og:title']").attr("content")
        if (metaTitle.isNotBlank() && !metaTitle.contains("AbeBooks")) {
            Log.d("BookTracker", "AbeBooksParser: Found title in meta tag: '$metaTitle'")
            return cleanTitle(metaTitle)
        }
        
        Log.w("BookTracker", "AbeBooksParser: No title found")
        return null
    }
    
    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("\\s*-\\s*Softcover\\s*$"), "") // Remove "- Softcover" suffix
            .replace(Regex("\\s*-\\s*Hardcover\\s*$"), "") // Remove "- Hardcover" suffix  
            .replace(Regex("\\s*-\\s*Paperback\\s*$"), "") // Remove "- Paperback" suffix
            .replace(Regex("\\s*\\|\\s*AbeBooks.*$"), "") // Remove pipe separator
            .replace(Regex("\\s*-\\s*AbeBooks.*$"), "") // Remove AbeBooks suffix
            .trim()
    }
    
    private fun extractAuthor(document: Document): String? {
        Log.d("BookTracker", "AbeBooksParser: Starting author extraction")
        
        // AbeBooks author selectors
        val authorSelectors = listOf(
            ".author",
            ".book-author", 
            "[data-cy='author']",
            ".listing-author",
            "a[href*='/servlet/SearchResults'][href*='author']" // AbeBooks author links
        )
        
        for (selector in authorSelectors) {
            val author = document.select(selector).first()?.text()?.trim()
            if (!author.isNullOrBlank() && author.length > 2) {
                Log.d("BookTracker", "AbeBooksParser: Found author with selector '$selector': '$author'")
                return cleanAuthorName(author)
            }
        }
        
        // Look for author in structured data around book details
        val detailSections = document.select("p, div, span")
        for (section in detailSections) {
            val text = section.text()
            if (text.matches(Regex("^[A-Za-z\\s,.-]+$")) && text.length in 5..50 && text.contains(",")) {
                // This might be an author name in "Last, First" format
                if (text.split(" ").size <= 4 && !text.contains("ISBN") && !text.contains("$")) {
                    Log.d("BookTracker", "AbeBooksParser: Potential author found: '$text'")
                    return cleanAuthorName(text)
                }
            }
        }
        
        // Try to extract from page title (AbeBooks format often includes author)
        val pageTitle = document.select("title").first()?.text()
        if (!pageTitle.isNullOrBlank()) {
            // Look for patterns like "Title by Author" or author after title
            val authorMatch = Regex("([A-Z][a-z]+,\\s*[A-Z][a-z]+)").find(pageTitle)
            if (authorMatch != null) {
                val author = authorMatch.groupValues[1]
                Log.d("BookTracker", "AbeBooksParser: Found author in page title: '$author'")
                return cleanAuthorName(author)
            }
        }
        
        Log.w("BookTracker", "AbeBooksParser: No author found")
        return null
    }
    
    private fun cleanAuthorName(author: String): String {
        return author.replace("By:", "", ignoreCase = true)
            .replace("Author:", "", ignoreCase = true)
            .trim()
    }
    
    private fun extractISBN(document: Document, url: String): Pair<String?, String?> {
        var isbn10: String? = null
        var isbn13: String? = null
        
        Log.d("BookTracker", "AbeBooksParser: Starting ISBN extraction")
        
        // Look for explicit ISBN labels in AbeBooks format
        val isbnSelectors = listOf(
            "*:contains(ISBN 10)", 
            "*:contains(ISBN 13)",
            "*:contains(ISBN:)",
            ".isbn",
            "[data-cy*='isbn']"
        )
        
        for (selector in isbnSelectors) {
            val elements = document.select(selector)
            for (element in elements) {
                val text = element.text()
                Log.d("BookTracker", "AbeBooksParser: Checking ISBN element: '$text'")
                
                if (text.contains("ISBN 10", ignoreCase = true) && isbn10 == null) {
                    isbn10 = extractISBNFromText(text, 10)
                    if (isbn10 != null) {
                        Log.d("BookTracker", "AbeBooksParser: Found ISBN-10: $isbn10")
                    }
                }
                if (text.contains("ISBN 13", ignoreCase = true) && isbn13 == null) {
                    isbn13 = extractISBNFromText(text, 13)
                    if (isbn13 != null) {
                        Log.d("BookTracker", "AbeBooksParser: Found ISBN-13: $isbn13")
                    }
                }
            }
        }
        
        // Extract from URL if available (AbeBooks URLs often contain ISBN)
        val urlISBN = Regex("/(\\d{13}|\\d{10})/").find(url)?.groupValues?.get(1)
        if (urlISBN != null) {
            if (urlISBN.length == 13 && isbn13 == null) {
                isbn13 = urlISBN
                Log.d("BookTracker", "AbeBooksParser: Found ISBN-13 in URL: $isbn13")
            } else if (urlISBN.length == 10 && isbn10 == null) {
                isbn10 = urlISBN
                Log.d("BookTracker", "AbeBooksParser: Found ISBN-10 in URL: $isbn10")
            }
        }
        
        // Look for ISBNs in general page text as fallback
        if (isbn10 == null || isbn13 == null) {
            val pageText = document.text()
            val isbnMatches = Regex("\\b(\\d{13}|\\d{9}[\\dX])\\b").findAll(pageText)
            
            for (match in isbnMatches) {
                val potentialISBN = match.value
                if (potentialISBN.length == 13 && potentialISBN.startsWith("978") && isbn13 == null) {
                    isbn13 = potentialISBN
                    Log.d("BookTracker", "AbeBooksParser: Found ISBN-13 in text: $isbn13")
                } else if (potentialISBN.length == 10 && isbn10 == null) {
                    isbn10 = potentialISBN
                    Log.d("BookTracker", "AbeBooksParser: Found ISBN-10 in text: $isbn10")
                }
            }
        }
        
        return Pair(isbn10, isbn13)
    }
    
    private fun extractISBNFromText(text: String, expectedLength: Int): String? {
        val isbnRegex = if (expectedLength == 10) {
            Regex("\\b(\\d{9}[\\dX])\\b")
        } else {
            Regex("\\b(\\d{13})\\b")
        }
        
        return isbnRegex.find(text)?.groupValues?.get(1)
    }
    
    private fun extractPrice(document: Document): Double? {
        Log.d("BookTracker", "AbeBooksParser: Starting price extraction")
        
        // This method now only handles single seller pages
        // Multi-seller detection is handled in the main parse() method
        return extractIndividualListingPrice(document) ?: extractStandardPrice(document)
    }
    
    private fun extractSellerOptionsFromSearchResults(documentText: String): List<SellerOption> {
        val options = mutableListOf<SellerOption>()
        
        // Split text by seller entries
        val sellerSections = documentText.split("Seller:")
        
        for (i in 1 until sellerSections.size) {
            val section = sellerSections[i]
            
            try {
                // Extract seller name (first part before comma)
                val sellerName = section.substringBefore(",").substringBefore("(").trim()
                
                // Extract price (look for "Buy Used US$" pattern)
                val priceMatch = Regex("Buy Used US\\$\\s*([0-9]+\\.[0-9]{2})").find(section)
                val price = priceMatch?.groupValues?.get(1)?.toDoubleOrNull()
                
                // Extract condition (look for "Condition:" pattern)
                val conditionMatch = Regex("Condition:\\s*([^.]+)\\.").find(section)
                val condition = conditionMatch?.groupValues?.get(1)?.trim() ?: "Unknown"
                
                // Extract location (after seller name, before rating)
                val locationMatch = Regex(",\\s*([^(]+)\\s*\\(").find(section)
                val location = locationMatch?.groupValues?.get(1)?.trim() ?: "Unknown"
                
                if (sellerName.isNotBlank() && price != null && price > 0) {
                    val option = SellerOption(sellerName, price, condition, location)
                    options.add(option)
                    Log.d("BookTracker", "AbeBooksParser: Found seller option: $sellerName - $$price ($condition) from $location")
                }
            } catch (e: Exception) {
                Log.w("BookTracker", "AbeBooksParser: Error parsing seller section: ${e.message}")
            }
        }
        
        return options.sortedBy { it.price } // Sort by price for better selection
    }
    
    private fun extractStandardPrice(document: Document): Double? {
        Log.d("BookTracker", "AbeBooksParser: Using standard price extraction")
        
        // Individual seller page selectors (highest priority)
        val priceSelectors = listOf(
            "#book-price .mbdp-item-price",
            ".mbdp-item-price",
            "#book-price",
            ".mbdp-price",
            ".listing-price",
            ".price"
        )
        
        for (selector in priceSelectors) {
            val elements = document.select(selector)
            for (element in elements) {
                val priceText = element.text()
                Log.d("BookTracker", "AbeBooksParser: Checking price element '$selector': '$priceText'")
                
                val price = extractPriceFromText(priceText)
                if (price != null) {
                    Log.d("BookTracker", "AbeBooksParser: Successfully extracted price: $$price from '$selector'")
                    return price
                }
            }
        }
        
        Log.w("BookTracker", "AbeBooksParser: No valid price found with standard selectors")
        return null
    }
    
    private fun extractIndividualListingPrice(document: Document): Double? {
        Log.d("BookTracker", "AbeBooksParser: Checking for individual listing price")
        
        // Check if this is truly an individual seller page (not search results)
        val documentText = document.text()
        val hasMultipleSellers = documentText.contains("Search results for", ignoreCase = true) ||
                                documentText.split("Buy Used US$").size > 2
        
        if (hasMultipleSellers) {
            Log.d("BookTracker", "AbeBooksParser: Skipping individual listing check - multiple sellers detected")
            return null
        }
        
        // Individual listing page selectors
        val selectors = listOf(
            "#book-price .mbdp-item-price",
            ".mbdp-item-price",
            "#book-price"
        )
        
        for (selector in selectors) {
            val elements = document.select(selector)
            for (element in elements) {
                val priceText = element.text()
                val price = extractPriceFromText(priceText)
                if (price != null) {
                    Log.d("BookTracker", "AbeBooksParser: Found individual listing price: $$price with selector '$selector'")
                    return price
                }
            }
        }
        
        return null
    }
    
    private fun extractPriceFromText(priceText: String): Double? {
        return try {
            // Simple price extraction - look for dollar amounts
            val priceMatches = Regex("US\\$\\s*(\\d+\\.\\d{2})|\\$\\s*(\\d+\\.\\d{2})").findAll(priceText)
            
            for (match in priceMatches) {
                val priceValue = match.groupValues[1].ifBlank { match.groupValues[2] }
                val price = priceValue.toDoubleOrNull()
                if (price != null && price > 0 && price < 1000) {
                    return price
                }
            }
            null
        } catch (e: Exception) {
            Log.e("BookTracker", "AbeBooksParser: Error extracting price from: '$priceText': ${e.message}")
            null
        }
    }
    
    private fun extractCoverImage(document: Document): String? {
        Log.d("BookTracker", "AbeBooksParser: Starting cover image extraction")
        
        val imageSelectors = listOf(
            "img[alt*='cover']",
            "img[src*='cover']",
            "img[alt*='Blessed']", // Title-specific
            ".book-image img",
            ".cover-image img",
            "img[src*='book']",
            ".product-image img",
            "img:not([src*='icon']):not([src*='logo']):not([src*='button'])" // Any image that's not an icon/logo/button
        )
        
        for (selector in imageSelectors) {
            val elements = document.select(selector)
            for (element in elements) {
                var imageUrl = element.attr("src")
                if (imageUrl.isBlank()) {
                    imageUrl = element.attr("data-src") // Lazy loading
                }
                
                if (imageUrl.isNotBlank()) {
                    val fullUrl = if (imageUrl.startsWith("http")) {
                        imageUrl
                    } else if (imageUrl.startsWith("//")) {
                        "https:$imageUrl"
                    } else {
                        element.absUrl("src")
                    }
                    
                    // Filter out small icons and irrelevant images
                    if (fullUrl.startsWith("http") && 
                        !fullUrl.contains("icon") && 
                        !fullUrl.contains("nav") && 
                        !fullUrl.contains("logo") &&
                        !fullUrl.contains("button") &&
                        !fullUrl.contains("arrow")) {
                        Log.d("BookTracker", "AbeBooksParser: Found cover image: $fullUrl")
                        return fullUrl
                    }
                }
            }
        }
        
        Log.w("BookTracker", "AbeBooksParser: No cover image found")
        return null
    }
    
} 