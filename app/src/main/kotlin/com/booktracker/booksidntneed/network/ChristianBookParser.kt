package com.booktracker.booksidntneed.network

import android.util.Log
import org.jsoup.nodes.Document

class ChristianBookParser : BookParser {
    
    override fun canParse(url: String): Boolean {
        return url.contains("christianbook.com", ignoreCase = true)
    }
    
    override fun getStoreName(): String = "ChristianBook.com"
    
    override fun parse(document: Document, url: String): ParsedBookInfo? {
        try {
            Log.d("BookTracker", "ChristianBookParser: Starting to parse URL: $url")
            
            val title = extractTitle(document)
            val author = extractAuthor(document) ?: "Unknown Author"
            val price = extractPrice(document)
            val isbn = extractISBN(document)
            val coverImage = extractCoverImage(document)
            
            Log.d("BookTracker", "ChristianBookParser: Extracted data - Title: '$title', Author: '$author', Price: $price")
            
            if (title.isNullOrBlank()) {
                Log.w("BookTracker", "ChristianBookParser: Missing required fields - Title: '$title'")
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
            
            Log.d("BookTracker", "ChristianBookParser: Successfully created ParsedBookInfo")
            return bookInfo
            
        } catch (e: Exception) {
            Log.e("BookTracker", "ChristianBookParser: Exception during parsing", e)
            return null
        }
    }
    
    private fun extractTitle(document: Document): String? {
        // Try multiple selectors for title
        val titleSelectors = listOf(
            "h1",  // Main title heading
            ".product-title",
            "[data-testid='product-title']",
            "title", // Page title as fallback
            ".book-title",
            ".item-title"
        )
        
        for (selector in titleSelectors) {
            val title = document.select(selector).first()?.text()?.trim()
            if (!title.isNullOrBlank() && title.length > 3 && !title.contains("ChristianBook.com")) {
                Log.d("BookTracker", "ChristianBookParser: Found title with selector '$selector': '$title'")
                return cleanTitle(title)
            }
        }
        
        // Try meta tags
        val metaTitle = document.select("meta[property='og:title']").attr("content")
        if (metaTitle.isNotBlank() && !metaTitle.contains("ChristianBook.com")) {
            Log.d("BookTracker", "ChristianBookParser: Found title in meta tag: '$metaTitle'")
            return cleanTitle(metaTitle)
        }
        
        Log.w("BookTracker", "ChristianBookParser: No title found")
        return null
    }
    
    private fun cleanTitle(title: String): String {
        return title.replace(Regex("\\s*-\\s*ChristianBook\\.com.*$"), "") // Remove site suffix
            .replace(Regex("\\s*\\|\\s*ChristianBook.*$"), "") // Remove pipe separator
            .trim()
    }
    
    private fun extractAuthor(document: Document): String? {
        // Try multiple selectors for author
        val authorSelectors = listOf(
            "[data-testid='author']",
            ".author",
            ".by-author",
            ".book-author",
            ".product-author",
            "a[href*='/author/']",
            ".contributor"
        )
        
        for (selector in authorSelectors) {
            val author = document.select(selector).first()?.text()?.trim()
            if (!author.isNullOrBlank() && author.length > 2) {
                Log.d("BookTracker", "ChristianBookParser: Found author with selector '$selector': '$author'")
                return cleanAuthorName(author)
            }
        }
        
        // Look for "By:" pattern in specific product information sections
        val productInfoSelectors = listOf(
            "table tr",
            ".product-info",
            ".book-details",
            "tr td"
        )
        
        for (selector in productInfoSelectors) {
            val elements = document.select(selector)
            for (element in elements) {
                val text = element.text()
                if (text.contains("By:", ignoreCase = true) && text.length < 200) {
                    // More specific regex that stops at common delimiters
                    val byRegex = Regex("(?i)By:\\s*([^|\\n\\t]+?)\\s*(?:Format|Stock|Thomas|Vendor|Publication|\\||$)")
                    val match = byRegex.find(text)
                    if (match != null) {
                        val author = match.groupValues[1].trim()
                        if (author.length in 3..<50 && !author.contains("$")) {
                            Log.d("BookTracker", "ChristianBookParser: Found author with 'By:' pattern: '$author'")
                            return cleanAuthorName(author)
                        }
                    }
                }
            }
        }
        
        // Look for author in page title
        val pageTitle = document.select("title").first()?.text()
        if (!pageTitle.isNullOrBlank()) {
            // ChristianBook format: "Title by Author | ChristianBook.com"
            val titleAuthorRegex = Regex("(.+?)\\s+by\\s+([^|]+?)\\s*\\|")
            val match = titleAuthorRegex.find(pageTitle)
            if (match != null) {
                val author = match.groupValues[2].trim()
                if (author.length in 3..<50) {
                    Log.d("BookTracker", "ChristianBookParser: Found author in page title: '$author'")
                    return cleanAuthorName(author)
                }
            }
        }
        
        // Try meta tags
        val metaAuthor = document.select("meta[name='author']").attr("content")
        if (metaAuthor.isNotBlank()) {
            Log.d("BookTracker", "ChristianBookParser: Found author in meta tag: '$metaAuthor'")
            return cleanAuthorName(metaAuthor)
        }
        
        // Look in meta description or other structured data
        val metaDescription = document.select("meta[name='description']").attr("content")
        if (metaDescription.isNotBlank()) {
            val colonPattern = Regex(":\\s*([^:]+?)\\s*: ?\\d{9,}")
            colonPattern.find(metaDescription)?.groupValues?.getOrNull(1)?.let { candidate ->
                val author = candidate.trim()
                if (author.length in 3..80) {
                    Log.d("BookTracker", "ChristianBookParser: Found author in meta description: '$author'")
                    return cleanAuthorName(author)
                }
            }
        }

        // Fallback to colon-delimited pattern in page title (e.g., "Title: Author: ISBN")
        val colonTitleRegex = Regex(":\\s*([^:]+?)\\s*:(?:\\s*\\d{9,}|\\s*Christianbook)", RegexOption.IGNORE_CASE)
        colonTitleRegex.find(document.select("title").first()?.text().orEmpty())?.groupValues?.getOrNull(1)?.let { candidate ->
            val author = candidate.trim()
            if (author.length in 3..80) {
                Log.d("BookTracker", "ChristianBookParser: Parsed author from colon-delimited title: '$author'")
                return cleanAuthorName(author)
            }
        }

        Log.w("BookTracker", "ChristianBookParser: No author found")
        return null
    }
    
    private fun cleanAuthorName(author: String): String {
        return author.replace("By:", "", ignoreCase = true)
            .replace("Author:", "", ignoreCase = true)
            .replace(Regex("\\(.*?\\)"), "") // Remove parentheses content
            .trim()
    }
    
    private fun extractPrice(document: Document): Double? {
        Log.d("BookTracker", "ChristianBookParser: Starting price extraction")
        
        val priceSelectors = listOf(
            ".current-price",
            ".price",
            ".our-price",
            ".sale-price",
            "[data-testid='price']",
            ".product-price",
            ".price-current"
        )
        
        for (selector in priceSelectors) {
            val elements = document.select(selector)
            Log.d("BookTracker", "ChristianBookParser: Trying price selector '$selector', found ${elements.size} elements")
            
            for (element in elements) {
                val priceText = element.text()
                if (priceText.isNotBlank()) {
                    Log.d("BookTracker", "ChristianBookParser: Found price text: '$priceText'")
                    val price = extractPriceFromText(priceText)
                    if (price != null && price > 0) {
                        Log.d("BookTracker", "ChristianBookParser: Successfully extracted price: $price")
                        return price
                    }
                }
            }
        }
        
        // Look for price in any text containing "Our Price" or "$"
        val allText = document.text()
        val pricePatterns = listOf(
            Regex("Our Price\\s*\\$([0-9]+\\.\\d{2})"),
            Regex("\\$([0-9]+\\.\\d{2})(?!.*Retail)")  // Price that's not followed by "Retail"
        )
        
        for (pattern in pricePatterns) {
            val match = pattern.find(allText)
            if (match != null) {
                val priceValue = match.groupValues[1].toDoubleOrNull()
                if (priceValue != null && priceValue > 0) {
                    Log.d("BookTracker", "ChristianBookParser: Found price with pattern: $priceValue")
                    return priceValue
                }
            }
        }
        
        Log.w("BookTracker", "ChristianBookParser: No price found")
        return null
    }
    
    private fun extractPriceFromText(priceText: String): Double? {
        return try {
            // Remove currency symbols and clean the text
            val cleanPrice = priceText.replace("$", "")
                .replace(",", "")
                .replace("USD", "")
                .replace("Our Price", "")
                .replace("Sale Price", "")
                .trim()
            
            // Handle various price formats
            val priceMatch = Regex("(\\d+\\.\\d{2})").find(cleanPrice)
            priceMatch?.value?.toDoubleOrNull()
        } catch (e: Exception) {
            Log.e("BookTracker", "ChristianBookParser: Error parsing price from '$priceText'", e)
            null
        }
    }
    
    private fun extractISBN(document: Document): Pair<String?, String?> {
        var isbn10: String? = null
        var isbn13: String? = null
        
        Log.d("BookTracker", "ChristianBookParser: Starting ISBN extraction")
        
        // Look for ISBN in product information table
        val rows = document.select("tr, .product-info, .specifications")
        
        for (row in rows) {
            val text = row.text()
            if (text.contains("ISBN", ignoreCase = true)) {
                val extractedISBN = extractISBNFromText(text)
                if (extractedISBN != null) {
                    if (extractedISBN.length == 10) {
                        isbn10 = extractedISBN
                        Log.d("BookTracker", "ChristianBookParser: Found ISBN-10: $isbn10")
                    } else if (extractedISBN.length == 13) {
                        isbn13 = extractedISBN
                        Log.d("BookTracker", "ChristianBookParser: Found ISBN-13: $isbn13")
                    }
                }
            }
        }
        
        // Also try to find ISBN in URL or any text on page (look for both types)
        if (isbn13 == null || isbn10 == null) {
            val pageText = document.text()
            val isbnMatch = Regex("\\b(\\d{13}|\\d{10}|\\d{9}[\\dX])\\b").findAll(pageText)
            
            for (match in isbnMatch) {
                val potentialISBN = match.value
                if (potentialISBN.length == 13 && potentialISBN.startsWith("978") && isbn13 == null) {
                    isbn13 = potentialISBN
                    Log.d("BookTracker", "ChristianBookParser: Found ISBN-13 in text: $isbn13")
                } else if ((potentialISBN.length == 10) && isbn10 == null) {
                    isbn10 = potentialISBN
                    Log.d("BookTracker", "ChristianBookParser: Found ISBN-10 in text: $isbn10")
                }
            }
        }
        
        // Try to find ISBN-13 from ISBN-10 conversion or vice versa in the URL itself
        if (isbn13 == null && isbn10 != null) {
            // Look for 13-digit version in URL or page
            val url = document.location()
            val isbn13InUrl = Regex("978\\d{10}").find(url)?.value
            if (isbn13InUrl != null) {
                isbn13 = isbn13InUrl
                Log.d("BookTracker", "ChristianBookParser: Found ISBN-13 in URL: $isbn13")
            }
        }
        
        return Pair(isbn10, isbn13)
    }
    
    private fun extractISBNFromText(text: String): String? {
        val isbnRegex = Regex("\\b\\d{9}[\\dX]\\b|\\b\\d{13}\\b")
        return isbnRegex.find(text)?.value
    }
    
    private fun extractCoverImage(document: Document): String? {
        val imageSelectors = listOf(
            ".product-image img",
            ".book-cover img",
            ".cover img",
            "img[alt*='cover']",
            "img[src*='cover']",
            ".main-image img",
            "img[alt*='Unoffendable']", // Title-specific for this example
            "img[src*='product']",
            "img[src*='book']",
            "table img", // ChristianBook often has images in tables
            ".product-close-up img"
        )
        
        for (selector in imageSelectors) {
            val elements = document.select(selector)
            for (element in elements) {
                val imageUrl = element.attr("src")
                if (imageUrl.isNotBlank()) {
                    val fullUrl = if (imageUrl.startsWith("http")) {
                        imageUrl
                    } else {
                        element.absUrl("src")
                    }
                    
                    // Filter out small icons and navigation images
                    if (fullUrl.startsWith("http") && 
                        !fullUrl.contains("icon") && 
                        !fullUrl.contains("nav") && 
                        !fullUrl.contains("logo") &&
                        !fullUrl.contains("button")) {
                        Log.d("BookTracker", "ChristianBookParser: Found cover image: $fullUrl")
                        return fullUrl
                    }
                }
            }
        }
        
        // Try data-src attribute (lazy loading)
        for (selector in imageSelectors) {
            val imageUrl = document.select(selector).first()?.attr("data-src")
            if (!imageUrl.isNullOrBlank()) {
                val fullUrl = if (imageUrl.startsWith("http")) imageUrl else "https://www.christianbook.com$imageUrl"
                Log.d("BookTracker", "ChristianBookParser: Found cover image (data-src): $fullUrl")
                return fullUrl
            }
        }
        
        Log.w("BookTracker", "ChristianBookParser: No cover image found")
        return null
    }
    
} 
