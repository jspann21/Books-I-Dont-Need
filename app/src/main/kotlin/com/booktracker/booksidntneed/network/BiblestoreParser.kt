package com.booktracker.booksidntneed.network

import android.util.Log
import org.jsoup.nodes.Document

class BiblestoreParser : BookParser {
    
    override fun canParse(url: String): Boolean {
        return url.contains("biblestore.com", ignoreCase = true)
    }
    
    override fun getStoreName(): String = "Biblestore.com"
    
    override fun parse(document: Document, url: String): ParsedBookInfo? {
        try {
            Log.d("BookTracker", "BiblestoreParser: Starting to parse URL: $url")
            
            val title = extractTitle(document)
            val isbn = extractISBN(document)
            val price = extractPrice(document)
            val coverImage = extractCoverImage(document)
            
            Log.d("BookTracker", "BiblestoreParser: Extracted data - Title: '$title', ISBN: '$isbn', Price: $price")
            
            if (title.isNullOrBlank()) {
                Log.w("BookTracker", "BiblestoreParser: Missing required title")
                return null
            }
            
            val bookInfo = ParsedBookInfo(
                title = title,
                author = null, // Author info not available on biblestore.com
                isbn10 = if (isbn?.length == 10) isbn else null,
                isbn13 = if (isbn?.length == 13) isbn else null,
                price = price,
                storeName = getStoreName(),
                storeUrl = url,
                coverImageUrl = coverImage
            )
            
            Log.d("BookTracker", "BiblestoreParser: Successfully created ParsedBookInfo")
            return bookInfo
            
        } catch (e: Exception) {
            Log.e("BookTracker", "BiblestoreParser: Exception during parsing", e)
            return null
        }
    }
    
    private fun extractTitle(document: Document): String? {
        // Try multiple selectors for title
        val titleSelectors = listOf(
            "h1[itemprop='name']",  // Main product title
            "h1",
            ".product-title",
            "title"
        )
        
        for (selector in titleSelectors) {
            val title = document.select(selector).first()?.text()?.trim()
            if (!title.isNullOrBlank() && title.length > 3 && !title.contains("Biblestore.com")) {
                Log.d("BookTracker", "BiblestoreParser: Found title with selector '$selector': '$title'")
                return cleanTitle(title)
            }
        }
        
        // Try meta properties
        val metaTitle = document.select("meta[property='og:title']").attr("content")
        if (metaTitle.isNotBlank() && !metaTitle.contains("Biblestore.com")) {
            Log.d("BookTracker", "BiblestoreParser: Found title in meta tag: '$metaTitle'")
            return cleanTitle(metaTitle)
        }
        
        Log.w("BookTracker", "BiblestoreParser: No title found")
        return null
    }
    
    private fun cleanTitle(title: String): String {
        return title.replace(Regex("\\s*-\\s*Biblestore\\.com.*$"), "") // Remove site suffix
            .replace(Regex("\\s*\\|\\s*Biblestore.*$"), "") // Remove pipe separator
            .trim()
    }
    
    private fun extractISBN(document: Document): String? {
        Log.d("BookTracker", "BiblestoreParser: Starting ISBN extraction")
        
        // Look for ISBN in JSON data first (most reliable)
        val scriptElements = document.select("script[type='application/json'], script")
        for (script in scriptElements) {
            val content = script.html()
            if (content.contains("sku") && content.contains("978")) {
                // Look for ISBN pattern in JSON
                val isbnRegex = Regex("\"sku\"\\s*:\\s*\"(\\d{13}|\\d{10})\"")
                val match = isbnRegex.find(content)
                if (match != null) {
                    val isbn = match.groupValues[1]
                    Log.d("BookTracker", "BiblestoreParser: Found ISBN in JSON: $isbn")
                    return isbn
                }
            }
        }
        
        // Look for ISBN in meta tags
        val pageText = document.text()
        val isbnPatterns = listOf(
            Regex("\\b(978\\d{10})\\b"), // ISBN-13 starting with 978
            Regex("\\b(\\d{9}[\\dX])\\b") // ISBN-10
        )
        
        for (pattern in isbnPatterns) {
            val match = pattern.find(pageText)
            if (match != null) {
                val isbn = match.groupValues[1]
                Log.d("BookTracker", "BiblestoreParser: Found ISBN in text: $isbn")
                return isbn
            }
        }
        
        Log.w("BookTracker", "BiblestoreParser: No ISBN found")
        return null
    }
    
    private fun extractPrice(document: Document): Double? {
        Log.d("BookTracker", "BiblestoreParser: Starting price extraction")
        
        val priceSelectors = listOf(
            "#ProductPrice .money",        // Main product price (most specific)
            "#ProductPrice",               // Product price container
            "[itemprop='price'] .money",   // Schema.org price with money class
            "[itemprop='price']",          // Schema.org price
            ".product-price .money",       // Product-specific price
            ".main-prod-desc .money",      // Main product description area
            ".price .money"                // Generic price (least specific - last resort)
        )
        
        for (selector in priceSelectors) {
            val priceElement = document.select(selector).first()
            if (priceElement != null) {
                val priceText = priceElement.text()
                val price = extractPriceFromText(priceText)
                if (price != null && price > 0) {
                    Log.d("BookTracker", "BiblestoreParser: Found price with selector '$selector': $price from text: '$priceText'")
                    return price
                }
            }
        }
        
        // Look for price in meta tags (higher priority than JSON since it's more specific)
        val metaPrice = document.select("meta[property='og:price:amount']").attr("content")
        if (metaPrice.isNotBlank()) {
            val price = metaPrice.toDoubleOrNull()
            if (price != null && price > 0) {
                Log.d("BookTracker", "BiblestoreParser: Found price in meta tag 'og:price:amount': $price")
                return price
            }
        }
        
        // Look for price in JSON data
        val scriptElements = document.select("script")
        for (script in scriptElements) {
            val content = script.html()
            if (content.contains("price") && content.contains("amount")) {
                val priceRegex = Regex("\"price\"\\s*:\\s*\\{[^}]*\"amount\"\\s*:\\s*([\\d.]+)")
                val match = priceRegex.find(content)
                if (match != null) {
                    val price = match.groupValues[1].toDoubleOrNull()
                    if (price != null && price > 0) {
                        Log.d("BookTracker", "BiblestoreParser: Found price in JSON: $price")
                        return price
                    }
                }
            }
        }
        
        Log.w("BookTracker", "BiblestoreParser: No price found")
        return null
    }
    
    private fun extractPriceFromText(priceText: String): Double? {
        return try {
            // Remove currency symbols and clean the text
            val cleanPrice = priceText.replace("$", "")
                .replace(",", "")
                .replace("USD", "")
                .trim()
            
            // Extract price using regex
            val priceMatch = Regex("(\\d+\\.\\d{2})").find(cleanPrice)
            priceMatch?.value?.toDoubleOrNull()
        } catch (e: Exception) {
            Log.e("BookTracker", "BiblestoreParser: Error parsing price from '$priceText'", e)
            null
        }
    }
    
    private fun extractCoverImage(document: Document): String? {
        val imageSelectors = listOf(
            ".product-single__photos img",
            ".MagicZoom img",
            "img[alt*='cover']",
            "img[src*='cdn/shop']",
            "meta[property='og:image']"
        )
        
        for (selector in imageSelectors) {
            val element = document.select(selector).first()
            if (element != null) {
                val imageUrl = if (selector.startsWith("meta")) {
                    element.attr("content")
                } else {
                    element.attr("src").ifBlank { element.attr("data-src") }
                }
                
                if (imageUrl.isNotBlank()) {
                    val fullUrl = if (imageUrl.startsWith("http")) {
                        imageUrl
                    } else if (imageUrl.startsWith("//")) {
                        "https:$imageUrl"
                    } else {
                        "https://biblestore.com$imageUrl"
                    }
                    
                    // Filter out small icons and navigation images
                    if (fullUrl.startsWith("http") && 
                        !fullUrl.contains("icon") && 
                        !fullUrl.contains("nav") && 
                        !fullUrl.contains("logo") &&
                        !fullUrl.contains("button")) {
                        Log.d("BookTracker", "BiblestoreParser: Found cover image: $fullUrl")
                        return fullUrl
                    }
                }
            }
        }
        
        Log.w("BookTracker", "BiblestoreParser: No cover image found")
        return null
    }
    
} 