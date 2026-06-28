package com.booktracker.booksidntneed.network

import android.util.Log
import org.jsoup.nodes.Document

class BetterWorldBooksParser : BookParser {
    
    override fun canParse(url: String): Boolean {
        return url.contains("betterworldbooks.com", ignoreCase = true)
    }
    
    override fun getStoreName(): String = "Better World Books"
    
    override fun parse(document: Document, url: String): ParsedBookInfo? {
        try {
            Log.d("BookTracker", "BetterWorldBooksParser: Starting to parse URL: $url")
            
            val title = extractTitle(document)
            val author = extractAuthor(document)
            val price = extractPrice(document)
            val (isbn10, isbn13) = extractISBN(document, url)
            val coverImage = extractCoverImage(document)
            
            return ParsedBookInfo(
                title = title,
                author = author,
                price = price?.toDoubleOrNull(),
                isbn10 = isbn10,
                isbn13 = isbn13,
                coverImageUrl = coverImage,
                storeName = getStoreName(),
                storeUrl = url
            )
        } catch (e: Exception) {
            Log.e("BookTracker", "BetterWorldBooksParser: Error parsing book", e)
            return null
        }
    }
    
    private fun extractTitle(document: Document): String? {
        Log.d("BookTracker", "BetterWorldBooksParser: Starting title extraction")
        
        // Try h1 tags first
        val h1Elements = document.select("h1")
        for (element in h1Elements) {
            val title = element.text().trim()
            if (title.isNotBlank() && !title.equals("Better World Books", ignoreCase = true)) {
                Log.d("BookTracker", "BetterWorldBooksParser: Found title from h1: '$title'")
                return title
            }
        }
        
        // Try Open Graph title
        val ogTitle = document.select("meta[property='og:title']").first()
        if (ogTitle != null) {
            val title = ogTitle.attr("content").trim()
            if (title.isNotBlank()) {
                // Remove "used book by [author]: ISBN" pattern
                val cleanTitle = title.replace(Regex("\\s+used\\s+book\\s+by\\s+.*?:\\s*\\d+.*$", RegexOption.IGNORE_CASE), "")
                Log.d("BookTracker", "BetterWorldBooksParser: Found title from og:title: '$cleanTitle'")
                return cleanTitle
            }
        }
        
        // Try page title as fallback
        val pageTitle = document.title()
        if (pageTitle.isNotBlank()) {
            val cleanTitle = pageTitle.replace(Regex("\\s+used\\s+book\\s+by\\s+.*?:\\s*\\d+.*$", RegexOption.IGNORE_CASE), "")
                .replace(" - Better World Books", "")
            Log.d("BookTracker", "BetterWorldBooksParser: Found title from page title: '$cleanTitle'")
            return cleanTitle
        }
        
        Log.w("BookTracker", "BetterWorldBooksParser: No title found")
        return null
    }
    
    private fun extractAuthor(document: Document): String? {
        Log.d("BookTracker", "BetterWorldBooksParser: Starting author extraction")
        
        // Try author links first
        val authorSelectors = listOf(
            "a[href*='/author/']",
            "[itemprop='author']",
            ".grid-book-author a"
        )
        
        for (selector in authorSelectors) {
            val elements = document.select(selector)
            for (element in elements) {
                val author = element.text().trim()
                if (author.isNotBlank() && author.length > 1) {
                    Log.d("BookTracker", "BetterWorldBooksParser: Found author with selector '$selector': '$author'")
                    return author
                }
            }
        }
        
        // Try extracting from "by [Author]" pattern
        val textElements = document.select("div")
        for (element in textElements) {
            val text = element.ownText()
            if (text.startsWith("by ", ignoreCase = true)) {
                val author = text.substring(3).trim()
                if (author.isNotBlank()) {
                    Log.d("BookTracker", "BetterWorldBooksParser: Found author from 'by' pattern: '$author'")
                    return author
                }
            }
        }
        
        Log.w("BookTracker", "BetterWorldBooksParser: No author found")
        return null
    }
    
    private fun extractPrice(document: Document): String? {
        Log.d("BookTracker", "BetterWorldBooksParser: Starting price extraction")
        
        val priceSelectors = listOf(
            ".atc-selected-price .price",
            ".grid-book-price",
            ".atc-pod-price",
            "[class*='price']"
        )
        
        for (selector in priceSelectors) {
            val elements = document.select(selector)
            for (element in elements) {
                val priceText = element.text().trim()
                Log.d("BookTracker", "BetterWorldBooksParser: Checking price text: '$priceText'")
                
                // Look for $X.XX pattern
                val priceMatch = Regex("\\$([0-9]+\\.?[0-9]*)").find(priceText)
                if (priceMatch != null) {
                    val price = priceMatch.groupValues[1]
                    Log.d("BookTracker", "BetterWorldBooksParser: Found price: '$price'")
                    return price
                }
            }
        }
        
        // Try to extract from JSON-LD structured data
        val scripts = document.select("script[type='application/ld+json']")
        for (script in scripts) {
            val content = script.html()
            val priceMatch = Regex("\"price\"\\s*:\\s*\"([0-9]+\\.?[0-9]*)\"").find(content)
            if (priceMatch != null) {
                val price = priceMatch.groupValues[1]
                Log.d("BookTracker", "BetterWorldBooksParser: Found price from JSON-LD: '$price'")
                return price
            }
        }
        
        Log.w("BookTracker", "BetterWorldBooksParser: No price found")
        return null
    }
    
    private fun extractISBN(document: Document, url: String): Pair<String?, String?> {
        var isbn10: String? = null
        var isbn13: String? = null
        
        Log.d("BookTracker", "BetterWorldBooksParser: Starting ISBN extraction")
        
        // Look for ISBN in structured data (itemprop="isbn")
        val isbnElements = document.select("[itemprop='isbn']")
        for (element in isbnElements) {
            val isbnText = element.text().trim()
            Log.d("BookTracker", "BetterWorldBooksParser: Found ISBN element: '$isbnText'")
            
            if (isbnText.length == 10) {
                isbn10 = isbnText
                Log.d("BookTracker", "BetterWorldBooksParser: Set ISBN-10: '$isbn10'")
            } else if (isbnText.length == 13) {
                isbn13 = isbnText
                Log.d("BookTracker", "BetterWorldBooksParser: Set ISBN-13: '$isbn13'")
            }
        }
        
        // Look for ISBN-10 and ISBN-13 labels
        val labels = document.select("label")
        for (label in labels) {
            val labelText = label.text().trim()
            if (labelText.contains("ISBN-10", ignoreCase = true)) {
                val isbnElement = label.nextElementSibling()
                if (isbnElement != null) {
                    val isbnText = isbnElement.text().trim()
                    if (isbnText.length == 10) {
                        isbn10 = isbnText
                        Log.d("BookTracker", "BetterWorldBooksParser: Found ISBN-10 from label: '$isbn10'")
                    }
                }
            } else if (labelText.contains("ISBN-13", ignoreCase = true)) {
                val isbnElement = label.nextElementSibling()
                if (isbnElement != null) {
                    val isbnText = isbnElement.text().trim()
                    if (isbnText.length == 13) {
                        isbn13 = isbnText
                        Log.d("BookTracker", "BetterWorldBooksParser: Found ISBN-13 from label: '$isbn13'")
                    }
                }
            }
        }
        
        // Try to extract from URL if not found
        if (isbn13 == null) {
            val urlMatch = Regex("/(\\d{13})/?").find(url)
            if (urlMatch != null) {
                isbn13 = urlMatch.groupValues[1]
                Log.d("BookTracker", "BetterWorldBooksParser: Extracted ISBN-13 from URL: '$isbn13'")
            }
        }
        
        Log.d("BookTracker", "BetterWorldBooksParser: Final ISBNs - ISBN-10: '$isbn10', ISBN-13: '$isbn13'")
        return Pair(isbn10, isbn13)
    }
    
    private fun extractCoverImage(document: Document): String? {
        Log.d("BookTracker", "BetterWorldBooksParser: Starting image extraction")
        
        // Try Open Graph image first
        val ogImage = document.select("meta[property='og:image']").first()
        if (ogImage != null) {
            val imageUrl = ogImage.attr("content").trim()
            if (imageUrl.isNotBlank() && isValidImageUrl(imageUrl)) {
                Log.d("BookTracker", "BetterWorldBooksParser: Found image from og:image: '$imageUrl'")
                return imageUrl
            }
        }
        
        // Try product images
        val imageSelectors = listOf(
            ".product-image img",
            ".grid-book-image img",
            "[itemprop='image']",
            "img[alt*='book']",
            "img[src*='covers']"
        )
        
        for (selector in imageSelectors) {
            val elements = document.select(selector)
            for (element in elements) {
                val imageUrl = element.attr("src").trim()
                if (imageUrl.isNotBlank() && isValidImageUrl(imageUrl)) {
                    Log.d("BookTracker", "BetterWorldBooksParser: Found image from selector '$selector': '$imageUrl'")
                    return imageUrl
                }
                
                // Also check data-cfsrc attribute
                val dataSrc = element.attr("data-cfsrc").trim()
                if (dataSrc.isNotBlank() && isValidImageUrl(dataSrc)) {
                    Log.d("BookTracker", "BetterWorldBooksParser: Found image from data-cfsrc: '$dataSrc'")
                    return dataSrc
                }
            }
        }
        
        Log.w("BookTracker", "BetterWorldBooksParser: No cover image found")
        return null
    }
    

    
    private fun isValidImageUrl(url: String): Boolean {
        return url.startsWith("http") && 
               !url.contains("placeholder") &&
               !url.contains("imageless") &&
               (url.contains("jpg") || url.contains("jpeg") || url.contains("png") || url.contains("webp"))
    }
} 