package com.booktracker.booksidntneed.network

import android.util.Log
import org.jsoup.nodes.Document
import java.net.URL
import java.util.Locale

class GenericParser : BookParser {
    companion object {
        private const val TAG = "GenericParser"
    }
    
    override fun canParse(url: String): Boolean {
        // This is the fallback parser, it can "parse" any URL
        return true
    }
    
    override fun getStoreName(): String {
        return "Unknown Store"
    }
    
    override fun parse(document: Document, url: String): ParsedBookInfo? {
        try {
            Log.d(TAG, "Starting to parse URL: $url")
            
            val storeName = extractStoreName(url)
            val title = extractTitle(document)
            val author = extractAuthor(document)
            val price = extractPrice(document)
            val isbn = extractISBN(document)
            val coverImage = extractCoverImage(document)
            
            Log.d(TAG, "Extracted data - Title: '$title', Author: '$author', Price: $price")
            
            if (title.isNullOrBlank() || author.isNullOrBlank()) {
                Log.w(TAG, "Missing required fields - Title: '$title', Author: '$author'")
                return null
            }
            
            return ParsedBookInfo(
                title = title,
                author = author,
                isbn10 = isbn.first,
                isbn13 = isbn.second,
                price = price,
                storeName = storeName,
                storeUrl = url,
                coverImageUrl = coverImage
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception during parsing", e)
            return null
        }
    }
    
    private fun extractStoreName(url: String): String {
        return try {
            val host = URL(url).host
            host.removePrefix("www.").split(".").first().replaceFirstChar { it.uppercase() }
        } catch (_: Exception) {
            "Unknown Store"
        }
    }
    
    private fun extractTitle(document: Document): String? {
        // PRIORITY 1: Open Graph and Twitter meta tags (most reliable)
        val ogTitle = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
        if (!ogTitle.isNullOrBlank()) {
            return cleanTitle(ogTitle)
        }
        
        val twitterTitle = document.selectFirst("meta[name=twitter:title]")?.attr("content")?.trim()
        if (!twitterTitle.isNullOrBlank()) {
            return cleanTitle(twitterTitle)
        }
        
        // PRIORITY 2: JSON-LD structured data
        val jsonTitle = extractFromJsonLD(document, "name")
        if (!jsonTitle.isNullOrBlank()) {
            return cleanTitle(jsonTitle)
        }
        
        // PRIORITY 3: Product-specific selectors
        val productTitleSelectors = listOf(
            "h1[data-testid*='title']",
            "#productTitle",
            ".product-title",
            ".book-title",
            "[itemprop='name']",
            "h1.title",
            "h1.product-name"
        )
        
        for (selector in productTitleSelectors) {
            val title = document.selectFirst(selector)?.text()?.trim()
            if (!title.isNullOrBlank() && title.length > 3 && title.length < 300) {
                return cleanTitle(title)
            }
        }
        
        // PRIORITY 4: General h1 titles
        val h1Elements = document.select("h1")
        for (h1 in h1Elements) {
            val title = h1.text().trim()
            if (title.isNotBlank() && title.length in 5..200 && !isNavigationTitle(title)) {
                return cleanTitle(title)
            }
        }
        
        // PRIORITY 5: Page title as fallback
        val pageTitle = document.title().trim()
        if (pageTitle.isNotBlank() && pageTitle.length > 5) {
            return cleanTitle(pageTitle)
        }
        
        return null
    }
    
    private fun cleanTitle(title: String): String {
        return title
            // Remove common store suffixes
            .replace(Regex("\\s*[|\\-]\\s*(Book Outlet|Amazon\\.com|Barnes & Noble|Logos Bible Software).*$"), "")
            .replace(Regex("\\s*[|\\-]\\s*Books.*$"), "")
            // Remove trailing parentheses with publication info
            .replace(Regex("\\s*\\([^)]*\\d{4}[^)]*\\)$"), "")
            // Clean up extra whitespace
            .replace(Regex("\\s+"), " ")
            .trim()
    }
    
    private fun isNavigationTitle(title: String): Boolean {
        val lowercaseTitle = title.lowercase(Locale.ROOT)
        return lowercaseTitle.contains("home") ||
               lowercaseTitle.contains("search") ||
               lowercaseTitle.contains("menu") ||
               lowercaseTitle.contains("navigation") ||
               lowercaseTitle.length < 5
    }
    
    private fun extractAuthor(document: Document): String? {
        // PRIORITY 1: Book-specific meta tags
        val bookAuthor = document.selectFirst("meta[property='book:author']")?.attr("content")?.trim()
        if (!bookAuthor.isNullOrBlank()) {
            return cleanAuthorName(bookAuthor)
        }
        
        // PRIORITY 2: JSON-LD structured data
        val jsonAuthor = extractFromJsonLD(document, "author")
        if (!jsonAuthor.isNullOrBlank()) {
            return cleanAuthorName(jsonAuthor)
        }
        
        // PRIORITY 3: Author-specific selectors
        val authorSelectors = listOf(
            "div[class*='author'] a[href*='/author']",
            ".author a",
            ".by-author a",
            ".book-author a",
            "[itemprop='author']",
            ".contributor a",
            ".writer a",
            ".creator a",
            "a[href*='/author/']",
            "a[aria-label*='author']"
        )
        
        for (selector in authorSelectors) {
            val element = document.selectFirst(selector)
            if (element != null) {
                val author = element.text().trim()
                if (author.isNotBlank() && author.length in 2..100) {
                    return cleanAuthorName(author)
                }
            }
        }
        
        // PRIORITY 4: Text pattern matching for "by Author"
        val byPatternSelectors = listOf(
            "[class*='author']",
            "[class*='byline']", 
            "[id*='author']"
        )
        
        for (selector in byPatternSelectors) {
            val elements = document.select(selector)
            for (element in elements) {
                val text = element.text()
                val byMatch = Regex("(?i)by\\s+([^,()\\n]+)").find(text)
                if (byMatch != null) {
                    val author = byMatch.groupValues[1].trim()
                    if (author.length in 3..100 && !author.contains("amazon", ignoreCase = true)) {
                        return cleanAuthorName(author)
                    }
                }
            }
        }
        
        // PRIORITY 5: Extract from page title (Amazon-style)
        val pageTitle = document.title()
        if (pageTitle.contains(":")) {
            val parts = pageTitle.split(":").map { it.trim() }
            for (i in 1 until parts.size) {
                val candidate = parts[i]
                if (isLikelyAuthorName(candidate)) {
                    return cleanAuthorName(candidate)
                }
            }
        }
        
        return null
    }
    
    private fun isLikelyAuthorName(text: String): Boolean {
        if (text.length !in 3..80) return false
        
        val words = text.split("\\s+".toRegex())
        if (words.size !in 1..6) return false
        
        // Avoid obvious non-author text
        val lowerText = text.lowercase(Locale.ROOT)
        if (lowerText.contains("amazon") || 
            lowerText.contains("books") ||
            lowerText.contains("edition") ||
            lowerText.matches(Regex("\\d+")) ||
            text.contains("(") || text.contains(")")) {
            return false
        }
        
        // Prefer proper name capitalization
        return words.all { it.firstOrNull()?.isUpperCase() == true }
    }
    
    private fun cleanAuthorName(author: String): String {
        return author
            .replace(Regex("\\(.*?\\)"), "") // Remove parentheses
            .replace(Regex("\\[.*?]"), "") // Remove brackets
            .replace("by ", "", ignoreCase = true)
            .replace("author:", "", ignoreCase = true)
            .replace("written by", "", ignoreCase = true)
            .trim()
    }
    
    private fun extractPrice(document: Document): Double? {
        // PRIORITY 1: JSON-LD structured data
        val jsonPrice = extractPriceFromJsonLD(document)
        if (jsonPrice != null && jsonPrice > 0) {
            return jsonPrice
        }
        
        // PRIORITY 2: Open Graph price meta tags
        val ogPrice = document.selectFirst("meta[property='product:price:amount']")?.attr("content")
        if (!ogPrice.isNullOrBlank()) {
            val price = ogPrice.toDoubleOrNull()
            if (price != null && price > 0) return price
        }
        
        // PRIORITY 3: Specific price selectors (avoid list/strike-through prices)
        val priceSelectors = listOf(
            ".price:not([data-a-strike='true'])",
            ".current-price",
            ".sale-price", 
            ".our-price",
            "[data-testid*='price']:not([data-testid*='list'])",
            ".priceToPay",
            "[itemprop='price']",
            ".cost",
            ".amount"
        )
        
        for (selector in priceSelectors) {
            val elements = document.select(selector)
            for (element in elements) {
                // Skip if this is a struck-through list price
                if (element.hasAttr("data-a-strike") || 
                    element.attr("data-a-color") == "secondary" ||
                    element.parent()?.text()?.contains("list price", ignoreCase = true) == true) {
                    continue
                }
                
                val priceText = element.text()
                val price = extractPriceFromText(priceText)
                if (price != null && price > 0) return price
            }
        }
        
        // PRIORITY 4: Search for price patterns in the page
        val pricePatternElements = document.select("*:containsOwn($)")
        for (element in pricePatternElements) {
            val text = element.text()
            // Skip very long text blocks to avoid descriptions
            if (text.length < 100) {
                val price = extractPriceFromText(text)
                if (price != null && price > 0) return price
            }
        }
        
        return null
    }
    
    private fun extractPriceFromText(priceText: String): Double? {
        return try {
            // Enhanced price regex to handle various formats
            val priceRegex = Regex("\\$([0-9,]+(?:\\.[0-9]{2})?)")
            val match = priceRegex.find(priceText)
            if (match != null) {
                val cleanPrice = match.groupValues[1].replace(",", "")
                return cleanPrice.toDoubleOrNull()
            }
            
            // Alternative: just numeric values in price context
            if (priceText.lowercase(Locale.ROOT).contains("price") || priceText.contains("$")) {
                val numericRegex = Regex("([0-9,]+\\.[0-9]{2})")
                val numMatch = numericRegex.find(priceText)
                if (numMatch != null) {
                    val cleanNum = numMatch.groupValues[1].replace(",", "")
                    return cleanNum.toDoubleOrNull()
                }
            }
            
            null
        } catch (_: Exception) {
            null
        }
    }
    
    private fun extractISBN(document: Document): Pair<String?, String?> {
        var isbn10: String? = null
        var isbn13: String? = null
        
        // PRIORITY 1: Book-specific meta tags
        val bookIsbn = document.selectFirst("meta[property='book:isbn']")?.attr("content")?.trim()
        if (!bookIsbn.isNullOrBlank()) {
            if (bookIsbn.length == 10) {
                isbn10 = bookIsbn
            } else if (bookIsbn.length == 13) {
                isbn13 = bookIsbn
            }
        }
        
        // PRIORITY 2: JSON-LD structured data
        val jsonIsbn = extractFromJsonLD(document, "isbn")
        if (!jsonIsbn.isNullOrBlank()) {
            if (jsonIsbn.length == 10) {
                isbn10 = jsonIsbn
            } else if (jsonIsbn.length == 13) {
                isbn13 = jsonIsbn
            }
        }
        
        // PRIORITY 3: Search document text for ISBN patterns
        val documentText = document.text()
        
        // Look for labeled ISBNs first
        val labeledIsbnRegex = Regex("(?i)isbn[-\\s]*(?:13|10)?[-\\s]*:?[-\\s]*(\\d{10,13})")
        val labeledMatch = labeledIsbnRegex.find(documentText)
        if (labeledMatch != null) {
            val isbn = labeledMatch.groupValues[1]
            if (isbn.length == 10) {
                isbn10 = isbn
            } else if (isbn.length == 13) {
                isbn13 = isbn
            }
        }
        
        // PRIORITY 4: Look for ISBN in image URLs (like BookOutlet)
        if (isbn10 == null && isbn13 == null) {
            val images = document.select("img[src]")
            for (img in images) {
                val src = img.attr("src")
                val isbnMatch = Regex("(?i)isbn\\d*/(\\d{10,13})").find(src)
                if (isbnMatch != null) {
                    val isbn = isbnMatch.groupValues[1]
                    if (isbn.length == 10) {
                        isbn10 = isbn
                    } else if (isbn.length == 13) {
                        isbn13 = isbn
                    }
                    break
                }
            }
        }
        
        // PRIORITY 5: Look for standalone 10 or 13 digit numbers
        if (isbn10 == null && isbn13 == null) {
            val standaloneIsbnRegex = Regex("\\b(\\d{13})\\b|\\b(\\d{9}[\\dX])\\b")
            val matches = standaloneIsbnRegex.findAll(documentText)
            for (match in matches) {
                val isbn13Candidate = match.groupValues[1]
                val isbn10Candidate = match.groupValues[2]
                
                if (isbn13Candidate.isNotEmpty()) {
                    isbn13 = isbn13Candidate
                    break
                } else if (isbn10Candidate.isNotEmpty()) {
                    isbn10 = isbn10Candidate
                    break
                }
            }
        }
        
        return Pair(isbn10, isbn13)
    }
    
    private fun extractCoverImage(document: Document): String? {
        // PRIORITY 1: Open Graph and Twitter meta tags
        val ogImage = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
        if (!ogImage.isNullOrBlank() && isValidImageUrl(ogImage)) {
            return ogImage
        }
        
        val twitterImage = document.selectFirst("meta[name=twitter:image]")?.attr("content")?.trim()
        if (!twitterImage.isNullOrBlank() && isValidImageUrl(twitterImage)) {
            return twitterImage
        }
        
        // PRIORITY 2: JSON-LD structured data
        val jsonImage = extractFromJsonLD(document, "image")
        if (!jsonImage.isNullOrBlank() && isValidImageUrl(jsonImage)) {
            return jsonImage
        }
        
        // PRIORITY 3: Product/book-specific image selectors
        val imageSelectors = listOf(
            ".product-image img",
            ".book-cover img", 
            ".cover img",
            ".main-image img",
            "[class*='product'][class*='image'] img",
            "img[alt*='cover' i]",
            "img[alt*='book' i]",
            "img[class*='cover']",
            "img[class*='product']"
        )
        
        for (selector in imageSelectors) {
            val imageElement = document.selectFirst(selector)
            if (imageElement != null) {
                // Check srcset first for higher resolution images
                val srcset = imageElement.attr("srcset")
                if (srcset.isNotBlank()) {
                    val bestImage = extractBestImageFromSrcset(srcset)
                    if (bestImage != null && isValidImageUrl(bestImage)) {
                        return bestImage
                    }
                }
                
                // Fallback to src attribute
                val src = imageElement.attr("src")
                if (src.isNotBlank() && isValidImageUrl(src)) {
                    return makeAbsoluteUrl(src, document)
                }
            }
        }
        
        return null
    }
    
    private fun extractBestImageFromSrcset(srcset: String): String? {
        // Extract all URLs from srcset and return the largest one
        val srcsetPattern = Regex("(https?://\\S+)\\s+(\\d+)w")
        val matches = srcsetPattern.findAll(srcset)
        
        return matches
            .map { it.groupValues[1] to it.groupValues[2].toIntOrNull() }
            .filter { it.second != null }
            .maxByOrNull { it.second!! }
            ?.first
    }
    
    private fun makeAbsoluteUrl(url: String, document: Document): String {
        return if (url.startsWith("http")) {
            url
        } else {
            val baseUri = document.baseUri()
            try {
                URL(URL(baseUri), url).toString()
            } catch (_: Exception) {
                url
            }
        }
    }
    
    private fun isValidImageUrl(url: String): Boolean {
        return url.startsWith("http") && 
               !url.contains("1x1") && 
               !url.contains("placeholder") &&
               !url.contains("spacer") &&
               !url.contains("blank") &&
               (url.contains("jpg") || url.contains("jpeg") || 
                url.contains("png") || url.contains("webp") || 
                url.contains("gif"))
    }
    

    
    private fun extractFromJsonLD(document: Document, field: String): String? {
        val jsonLdScripts = document.select("script[type='application/ld+json']")
        for (script in jsonLdScripts) {
            try {
                val content = script.html()
                
                // Simple regex extraction for common fields
                when (field) {
                    "name" -> {
                        val nameMatch = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(content)
                        if (nameMatch != null) return nameMatch.groupValues[1]
                    }
                    "author" -> {
                        val authorMatch = Regex("\"author\"\\s*:\\s*\"([^\"]+)\"").find(content)
                        if (authorMatch != null) return authorMatch.groupValues[1]
                    }
                    "price" -> {
                        // Handled separately in extractPriceFromJsonLD
                    }
                    "isbn" -> {
                        val isbnMatch = Regex("\"isbn\"\\s*:\\s*\"([^\"]+)\"").find(content)
                        if (isbnMatch != null) return isbnMatch.groupValues[1]
                    }
                    "image" -> {
                        val imageMatch = Regex("\"image\"\\s*:\\s*\"([^\"]+)\"").find(content)
                        if (imageMatch != null) return imageMatch.groupValues[1]
                    }
                    "description" -> {
                        val descMatch = Regex("\"description\"\\s*:\\s*\"([^\"]+)\"").find(content)
                        if (descMatch != null) return descMatch.groupValues[1]
                    }
                }
            } catch (_: Exception) {
                // Continue to next script
            }
        }
        return null
    }
    
    private fun extractPriceFromJsonLD(document: Document): Double? {
        val jsonLdScripts = document.select("script[type='application/ld+json']")
        for (script in jsonLdScripts) {
            try {
                val content = script.html()
                if (content.contains("\"price\"") || content.contains("\"@type\":\"Product\"")) {
                    // Look for various price fields
                    val pricePatterns = listOf(
                        "\"price\"\\s*:\\s*\"?([0-9.]+)\"?",
                        "\"lowPrice\"\\s*:\\s*\"?([0-9.]+)\"?",
                        "\"highPrice\"\\s*:\\s*\"?([0-9.]+)\"?"
                    )
                    
                    for (pattern in pricePatterns) {
                        val priceMatch = Regex(pattern).find(content)
                        if (priceMatch != null) {
                            val price = priceMatch.groupValues[1].toDoubleOrNull()
                            if (price != null && price > 0) {
                                return price
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Continue to next script
            }
        }
        return null
    }
} 