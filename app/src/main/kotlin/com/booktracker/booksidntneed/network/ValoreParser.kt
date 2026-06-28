package com.booktracker.booksidntneed.network

import android.util.Log
import org.jsoup.nodes.Document
import java.util.Locale

class ValoreParser : BookParser {
    companion object {
        private const val TAG = "ValoreParser"
    }
    
    override fun canParse(url: String): Boolean {
        return url.contains("valore.com", ignoreCase = true)
    }
    
    override fun getStoreName(): String = "Valore Books"
    
    override fun parse(document: Document, url: String): ParsedBookInfo? {
        try {
            Log.d(TAG, "Starting to parse Valore URL: $url")
            
            val title = extractTitle(document)
            val author = extractAuthor(document)
            val price = extractPrice(document)
            val isbn = extractISBN(document, url)
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
                storeName = "Valore Books",
                storeUrl = url,
                coverImageUrl = coverImage
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception during parsing", e)
            return null
        }
    }
    
    private fun extractTitle(document: Document): String? {
        // Valore Books title extraction priority
        val titleSelectors = listOf(
            "h1[data-testid='product-title']",
            "h1.product-title",
            ".product-header h1",
            ".book-title",
            "h1",
            "meta[property='og:title']",
            "meta[name='title']"
        )
        
        for (selector in titleSelectors) {
            val element = document.selectFirst(selector)
            if (element != null) {
                val title = if (selector.startsWith("meta")) {
                    element.attr("content")
                } else {
                    element.text()
                }.trim()
                
                if (title.isNotBlank() && title.length > 3) {
                    return cleanTitle(title)
                }
            }
        }
        
        return null
    }
    
    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("\\s*[|\\-]\\s*Valore Books.*$"), "")
            .replace(Regex("\\s*[|\\-]\\s*Valore.*$"), "")
            .replace(Regex("\\s*[|\\-]\\s*Book.*$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
    
    private fun extractAuthor(document: Document): String? {
        // Valore Books author extraction
        val authorSelectors = listOf(
            "[data-testid='product-author']",
            ".product-author",
            ".book-author",
            ".author-name",
            ".author",
            "meta[name='author']",
            "[itemprop='author']",
            ".product-details .author"
        )
        
        for (selector in authorSelectors) {
            val element = document.selectFirst(selector)
            if (element != null) {
                val author = if (selector.startsWith("meta")) {
                    element.attr("content")
                } else {
                    element.text()
                }.trim()
                
                if (author.isNotBlank() && author.length > 2) {
                    return cleanAuthor(author)
                }
            }
        }
        
        // Try extracting from structured data
        val jsonLdAuthor = extractFromJsonLD(document, "author")
        if (!jsonLdAuthor.isNullOrBlank()) {
            return cleanAuthor(jsonLdAuthor)
        }
        
        // Try finding author in product details or description
        val productDetails = document.select(".product-details, .book-details, .item-details")
        for (detailSection in productDetails) {
            val text = detailSection.text()
            val authorMatch = Regex("(?i)(?:author|by)[:\\s]+([^\\n\\r,;]+)").find(text)
            if (authorMatch != null) {
                val author = authorMatch.groupValues[1].trim()
                if (author.length in 3..80) {
                    return cleanAuthor(author)
                }
            }
        }
        
        return null
    }
    
    private fun cleanAuthor(author: String): String {
        return author
            .replace("by ", "", ignoreCase = true)
            .replace("author:", "", ignoreCase = true)
            .replace("written by", "", ignoreCase = true)
            .replace(Regex("\\(.*?\\)"), "") // Remove parentheses
            .trim()
    }
    
    private fun extractPrice(document: Document): Double? {
        Log.d(TAG, "Starting price extraction")
        
        // PRIORITY 1: Valore Books specific price selectors
        val priceSelectors = listOf(
            "[data-testid='price']",
            ".price-current",
            ".current-price",
            ".product-price",
            ".book-price",
            ".price",
            "[itemprop='price']",
            ".cost",
            ".amount"
        )
        
        for (selector in priceSelectors) {
            val elements = document.select(selector)
            for (element in elements) {
                // Skip crossed-out or "was" prices
                val parent = element.parent()
                val elementClass = element.className().lowercase(Locale.ROOT)
                val parentClass = parent?.className()?.lowercase(Locale.ROOT) ?: ""
                
                if (elementClass.contains("was") || elementClass.contains("original") ||
                    parentClass.contains("was") || parentClass.contains("original") ||
                    element.hasAttr("style") && element.attr("style").contains("text-decoration: line-through")) {
                    continue
                }
                
                val priceText = element.text().trim()
                Log.d(TAG, "Found price element with text: '$priceText'")
                val price = extractPriceFromText(priceText)
                if (price != null && price > 0) {
                    Log.d(TAG, "Successfully extracted price: $price")
                    return price
                }
            }
        }
        
        // PRIORITY 2: Extract from structured data
        val jsonPrice = extractPriceFromJsonLD(document)
        if (jsonPrice != null && jsonPrice > 0) {
            Log.d(TAG, "Successfully extracted price from JSON-LD: $jsonPrice")
            return jsonPrice
        }
        
        // PRIORITY 3: Look for price patterns in the page
        val priceElements = document.select("*:containsOwn($)").filter { it.text().length < 100 }
        val foundPrices = mutableListOf<Double>()
        
        for (element in priceElements) {
            val priceText = element.text().trim()
            // Skip very long text blocks and obvious non-price elements
            if (priceText.length < 50 && !priceText.lowercase(Locale.ROOT).contains("shipping")) {
                val price = extractPriceFromText(priceText)
                if (price != null && price > 0) {
                    foundPrices.add(price)
                }
            }
        }
        
        // If we found multiple prices, return the most reasonable one
        if (foundPrices.isNotEmpty()) {
            // Filter out unreasonably high prices (over $500 for books)
            val reasonablePrices = foundPrices.filter { it <= 500.0 }
            if (reasonablePrices.isNotEmpty()) {
                val selectedPrice = reasonablePrices.minOrNull()
                Log.d(TAG, "Found multiple prices: $foundPrices, returning: $selectedPrice")
                return selectedPrice
            }
        }
        
        Log.d(TAG, "No price found")
        return null
    }
    
    private fun extractPriceFromText(priceText: String): Double? {
        return try {
            // Enhanced price regex for various formats including international
            val priceRegex = Regex("\\$([0-9,]+(?:\\.[0-9]{2})?)|([0-9,]+(?:\\.[0-9]{2})?)\\s*USD")
            val match = priceRegex.find(priceText)
            if (match != null) {
                val dollarPrice = match.groupValues[1]
                val usdPrice = match.groupValues[2]
                val cleanPrice = if (dollarPrice.isNotEmpty()) {
                    dollarPrice.replace(",", "")
                } else {
                    usdPrice.replace(",", "")
                }
                return cleanPrice.toDoubleOrNull()
            }
            
            // Try to find just numeric values with decimal
            val numericRegex = Regex("([0-9,]+\\.[0-9]{2})")
            val numMatch = numericRegex.find(priceText)
            if (numMatch != null) {
                val cleanNum = numMatch.groupValues[1].replace(",", "")
                return cleanNum.toDoubleOrNull()
            }
            
            null
        } catch (_: Exception) {
            null
        }
    }
    
    private fun extractISBN(document: Document, url: String): Pair<String?, String?> {
        var isbn10: String? = null
        var isbn13: String? = null
        
        // PRIORITY 1: Extract from URL (Valore often includes ISBN in URL)
        val isbnFromUrl = Regex("\\b(\\d{13})\\b|\\b(\\d{9}[\\dX])\\b").find(url)
        if (isbnFromUrl != null) {
            val isbn13Candidate = isbnFromUrl.groupValues[1]
            val isbn10Candidate = isbnFromUrl.groupValues[2]
            
            if (isbn13Candidate.isNotEmpty()) {
                isbn13 = isbn13Candidate
            } else if (isbn10Candidate.isNotEmpty()) {
                isbn10 = isbn10Candidate
            }
        }
        
        // PRIORITY 2: Check meta tags
        val isbnSelectors = listOf(
            "meta[name='isbn']",
            "meta[property='book:isbn']",
            "meta[name='book:isbn']"
        )
        
        for (selector in isbnSelectors) {
            val isbnMeta = document.selectFirst(selector)
            if (isbnMeta != null) {
                val isbn = isbnMeta.attr("content").trim()
                if (isbn.length == 10 && isbn10 == null) {
                    isbn10 = isbn
                } else if (isbn.length == 13 && isbn13 == null) {
                    isbn13 = isbn
                }
            }
        }
        
        // PRIORITY 3: Look for ISBN in product details
        val productDetailsSelectors = listOf(
            ".product-details",
            ".book-details", 
            ".item-details",
            ".specifications"
        )
        
        for (selector in productDetailsSelectors) {
            val element = document.selectFirst(selector)
            if (element != null) {
                val text = element.text()
                val isbnMatch = Regex("ISBN[-\\s]*(?:13|10)?[-\\s]*:?[-\\s]*(\\d{10,13})").find(text)
                if (isbnMatch != null) {
                    val isbn = isbnMatch.groupValues[1]
                    if (isbn.length == 10 && isbn10 == null) {
                        isbn10 = isbn
                    } else if (isbn.length == 13 && isbn13 == null) {
                        isbn13 = isbn
                    }
                }
            }
        }
        
        return Pair(isbn10, isbn13)
    }
    
    private fun extractCoverImage(document: Document): String? {
        val imageSelectors = listOf(
            "meta[property='og:image']",
            "meta[name='twitter:image']",
            ".product-image img",
            ".book-cover img",
            ".main-image img",
            "img[data-testid='product-image']",
            ".product-gallery img",
            "img[alt*='cover' i]",
            "img[alt*='book' i]"
        )
        
        for (selector in imageSelectors) {
            val element = document.selectFirst(selector)
            if (element != null) {
                val imageUrl = if (selector.startsWith("meta")) {
                    element.attr("content")
                } else {
                    // Check srcset first for higher quality
                    val srcset = element.attr("srcset")
                    if (srcset.isNotBlank()) {
                        extractBestImageFromSrcset(srcset) ?: element.attr("src")
                    } else {
                        element.attr("src")
                    }
                }.trim()
                
                if (isValidImageUrl(imageUrl)) {
                    return makeAbsoluteUrl(imageUrl, document)
                }
            }
        }
        
        return null
    }
    
    private fun extractBestImageFromSrcset(srcset: String): String? {
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
                java.net.URL(java.net.URL(baseUri), url).toString()
            } catch (_: Exception) {
                url
            }
        }
    }
    
    private fun isValidImageUrl(url: String): Boolean {
        return url.isNotBlank() &&
               url.startsWith("http") && 
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
                when (field) {
                    "author" -> {
                        val authorMatch = Regex("\"author\"\\s*:\\s*\"([^\"]+)\"").find(content)
                        if (authorMatch != null) return authorMatch.groupValues[1]
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
                    val pricePatterns = listOf(
                        "\"lowPrice\"\\s*:\\s*\"?([0-9.]+)\"?",
                        "\"price\"\\s*:\\s*\"?([0-9.]+)\"?",
                        "\"offers\"[^}]*\"price\"\\s*:\\s*\"?([0-9.]+)\"?",
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