package com.booktracker.booksidntneed.network

import android.util.Log
import org.jsoup.nodes.Document

class CrosswayParser : BookParser {
    
    override fun canParse(url: String): Boolean {
        return url.contains("crossway.org", ignoreCase = true)
    }
    
    override fun getStoreName(): String = "Crossway"
    
    override fun parse(document: Document, url: String): ParsedBookInfo? {
        try {
            Log.d("BookTracker", "CrosswayParser: Starting to parse URL: $url")
            
            val title = extractTitle(document)
            val author = extractAuthor(document)
            val price = extractPrice(document)
            val isbn = extractISBN(document)
            val coverImage = extractCoverImage(document)
            
            Log.d("BookTracker", "CrosswayParser: Extracted data - Title: '$title', Author: '$author', Price: $price")
            
            if (title.isNullOrBlank()) {
                Log.w("BookTracker", "CrosswayParser: Missing required field - Title: '$title'")
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
            
            Log.d("BookTracker", "CrosswayParser: Successfully created ParsedBookInfo")
            return bookInfo
        } catch (e: Exception) {
            Log.e("BookTracker", "CrosswayParser: Exception during parsing", e)
            return null
        }
    }
    
    private fun extractTitle(document: Document): String? {
        Log.d("BookTracker", "CrosswayParser: Starting title extraction")
        
        // Try main h1 title
        val h1Elements = document.select("h1")
        for (h1 in h1Elements) {
            val title = h1.text().trim()
            if (title.isNotBlank() && title.length > 3 && !title.contains("Crossway")) {
                Log.d("BookTracker", "CrosswayParser: Found title from h1: '$title'")
                return cleanTitle(title)
            }
        }
        
        // Try page title
        val pageTitle = document.select("title").first()?.text()?.trim()
        if (!pageTitle.isNullOrBlank()) {
            // Crossway typically uses "Title | Crossway" format
            val titleMatch = Regex("^([^|]+?)\\s*\\|").find(pageTitle)
            if (titleMatch != null) {
                val title = titleMatch.groupValues[1].trim()
                if (title.isNotBlank()) {
                    Log.d("BookTracker", "CrosswayParser: Found title from page title: '$title'")
                    return cleanTitle(title)
                }
            }
        }
        
        // Try Open Graph title
        val ogTitle = document.select("meta[property='og:title']").first()?.attr("content")?.trim()
        if (!ogTitle.isNullOrBlank()) {
            Log.d("BookTracker", "CrosswayParser: Found title from og:title: '$ogTitle'")
            return cleanTitle(ogTitle)
        }
        
        Log.w("BookTracker", "CrosswayParser: No title found")
        return null
    }
    
    private fun cleanTitle(title: String): String {
        return title.replace(Regex("\\s*\\|\\s*Crossway.*$"), "") // Remove " | Crossway" suffix
            .replace(Regex("\\(.*?\\)$"), "") // Remove trailing parentheses
            .trim()
    }
    
    private fun extractAuthor(document: Document): String {
        Log.d("BookTracker", "CrosswayParser: Starting author extraction")
        
        // Look for series editors or authors in various patterns
        val authorPatterns = listOf(
            "Series edited by",
            "Edited by",
            "By",
            "Author:",
            "Written by"
        )
        
        for (pattern in authorPatterns) {
            val elements = document.select("*:contains($pattern)")
            for (element in elements) {
                val text = element.text()
                val regex = Regex("$pattern\\s+([^,\n]+?)(?:,|$|\n|\\.|and)")
                val match = regex.find(text)
                if (match != null) {
                    val author = match.groupValues[1].trim()
                    if (author.length > 2 && !author.contains("Crossway")) {
                        Log.d("BookTracker", "CrosswayParser: Found author with pattern '$pattern': '$author'")
                        return cleanAuthorName(author)
                    }
                }
            }
        }
        
        // Try to find author links
        val authorSelectors = listOf(
            "a[href*='/authors/']",
            ".author a",
            ".by-author a",
            "*[class*='author'] a"
        )
        
        for (selector in authorSelectors) {
            val authorElement = document.select(selector).first()
            if (authorElement != null) {
                val author = authorElement.text().trim()
                if (author.isNotBlank() && author.length > 2) {
                    Log.d("BookTracker", "CrosswayParser: Found author with selector '$selector': '$author'")
                    return cleanAuthorName(author)
                }
            }
        }
        
        // Look for italicized text which often contains author info
        val italicElements = document.select("em, i")
        for (element in italicElements) {
            val text = element.text().trim()
            if (text.length in 6..<100 && !text.contains("Series") && !text.contains("Volume")) {
                // Check if it looks like an author name (has capital letters, reasonable length)
                if (text.matches(Regex("^[A-Z][a-zA-Z\\s.,]+$")) && text.count { it == ' ' } <= 5) {
                    Log.d("BookTracker", "CrosswayParser: Found potential author from italics: '$text'")
                    return cleanAuthorName(text)
                }
            }
        }
        
        Log.d("BookTracker", "CrosswayParser: No author found - this may be an edited collection")
        return "Various Authors" // Default for collections/series
    }
    
    private fun cleanAuthorName(author: String): String {
        return author.replace(Regex("\\(.*?\\)"), "") // Remove parentheses content
            .replace(Regex("\\[.*?]"), "")  // Remove bracket content
            .replace("Series edited by ", "", ignoreCase = true)
            .replace("Edited by ", "", ignoreCase = true)
            .replace("By ", "", ignoreCase = true)
            .replace("Author: ", "", ignoreCase = true)
            .replace("Written by ", "", ignoreCase = true)
            .trim()
    }
    
    private fun extractPrice(document: Document): Double? {
        Log.d("BookTracker", "CrosswayParser: Starting price extraction")
        
        // Look for retail price in product details table
        val priceElements = document.select("*:contains(Retail Price)")
        for (element in priceElements) {
            val text = element.text()
            val priceMatch = Regex("Retail Price:?\\s*\\$([\\d,]+\\.\\d{2})").find(text)
            if (priceMatch != null) {
                val priceStr = priceMatch.groupValues[1].replace(",", "")
                val price = priceStr.toDoubleOrNull()
                if (price != null && price > 0) {
                    Log.d("BookTracker", "CrosswayParser: Found price from Retail Price: $price")
                    return price
                }
            }
        }
        
        // Look for any price patterns
        val priceSelectors = listOf(
            ".price",
            "[data-testid='price']",
            "*:contains($)"
        )
        
        for (selector in priceSelectors) {
            val elements = document.select(selector)
            for (element in elements) {
                val priceText = element.text()
                val price = extractPriceFromText(priceText)
                if (price != null && price > 0) {
                    Log.d("BookTracker", "CrosswayParser: Found price with selector '$selector': $price")
                    return price
                }
            }
        }
        
        Log.w("BookTracker", "CrosswayParser: No price found")
        return null
    }
    
    private fun extractPriceFromText(priceText: String): Double? {
        return try {
            val cleanPrice = priceText.replace("$", "")
                .replace(",", "")
                .replace("USD", "")
                .replace("Retail Price:", "")
                .trim()
            
            val priceMatch = Regex("(\\d+\\.\\d{2})").find(cleanPrice)
            priceMatch?.value?.toDoubleOrNull()
        } catch (e: Exception) {
            Log.e("BookTracker", "CrosswayParser: Error parsing price from '$priceText'", e)
            null
        }
    }
    
    private fun extractISBN(document: Document): Pair<String?, String?> {
        var isbn10: String? = null
        var isbn13: String? = null
        
        Log.d("BookTracker", "CrosswayParser: Starting ISBN extraction")
        
        // Look for ISBN in product details table
        val isbnElements = document.select("*:contains(ISBN)")
        for (element in isbnElements) {
            val text = element.text()
            
            // Look for ISBN-10
            val isbn10Match = Regex("ISBN-10:?\\s*([\\d-]+)").find(text)
            if (isbn10Match != null && isbn10 == null) {
                val isbnValue = isbn10Match.groupValues[1].replace("-", "")
                if (isbnValue.length == 10) {
                    isbn10 = isbnValue
                    Log.d("BookTracker", "CrosswayParser: Found ISBN-10: '$isbn10'")
                }
            }
            
            // Look for ISBN-13
            val isbn13Match = Regex("ISBN-13:?\\s*([\\d-]+)").find(text)
            if (isbn13Match != null && isbn13 == null) {
                val isbnValue = isbn13Match.groupValues[1].replace("-", "")
                if (isbnValue.length == 13) {
                    isbn13 = isbnValue
                    Log.d("BookTracker", "CrosswayParser: Found ISBN-13: '$isbn13'")
                }
            }
            
            // Look for ISBN-UPC (usually same as ISBN-13)
            if (isbn13 == null) {
                val isbnUpcMatch = Regex("ISBN-UPC:?\\s*([\\d-]+)").find(text)
                if (isbnUpcMatch != null) {
                    val isbnValue = isbnUpcMatch.groupValues[1].replace("-", "")
                    if (isbnValue.length == 13) {
                        isbn13 = isbnValue
                        Log.d("BookTracker", "CrosswayParser: Found ISBN-13 from ISBN-UPC: '$isbn13'")
                    }
                }
            }
        }
        
        Log.d("BookTracker", "CrosswayParser: Final ISBN results - ISBN-10: '$isbn10', ISBN-13: '$isbn13'")
        return Pair(isbn10, isbn13)
    }
    
    private fun extractCoverImage(document: Document): String? {
        Log.d("BookTracker", "CrosswayParser: Starting image extraction")
        
        // Try Open Graph image
        val ogImage = document.select("meta[property='og:image']").first()?.attr("content")?.trim()
        if (!ogImage.isNullOrBlank() && isValidImageUrl(ogImage)) {
            Log.d("BookTracker", "CrosswayParser: Found image from og:image: '$ogImage'")
            return ogImage
        }
        
        // Try to find main product image
        val imageSelectors = listOf(
            ".product-image img",
            ".book-cover img", 
            ".cover-image img",
            "img[src*='cover']",
            "img[alt*='cover']",
            "img[src*='crossway']",
            ".image img",
            "main img"
        )
        
        for (selector in imageSelectors) {
            val imageElement = document.select(selector).first()
            if (imageElement != null) {
                val imageUrl = imageElement.attr("src")
                val dataSrc = imageElement.attr("data-src")
                
                val urls = listOf(imageUrl, dataSrc).filter { it.isNotBlank() }
                for (url in urls) {
                    if (isValidImageUrl(url)) {
                        // Convert relative URLs to absolute
                        val absoluteUrl = if (url.startsWith("/")) {
                            "https://www.crossway.org$url"
                        } else {
                            url
                        }
                        Log.d("BookTracker", "CrosswayParser: Found image with selector '$selector': '$absoluteUrl'")
                        return absoluteUrl
                    }
                }
            }
        }
        
        Log.w("BookTracker", "CrosswayParser: No valid image found")
        return null
    }
    
    private fun isValidImageUrl(url: String): Boolean {
        return (url.startsWith("http") || url.startsWith("/")) && 
               !url.contains("1x1") && 
               !url.contains("placeholder") &&
               (url.contains("jpg") || url.contains("jpeg") || url.contains("png") || url.contains("webp"))
    }
    
} 