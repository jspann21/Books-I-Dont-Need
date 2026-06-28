package com.booktracker.booksidntneed.network

import android.util.Log
import org.jsoup.nodes.Document

class ThriftBooksParser : BookParser {
    
    override fun canParse(url: String): Boolean {
        return url.contains("thriftbooks.com", ignoreCase = true)
    }
    
    override fun parse(document: Document, url: String): ParsedBookInfo {
        val title = extractTitle(document)
        val author = extractAuthor(document) 
        val isbn10 = extractIsbn10(document)
        val isbn13 = extractIsbn13(document)
        val price = extractPrice(document)
        val coverImageUrl = extractCoverImage(document)
        
        Log.d("ThriftBooksParser", "Parsed - Title: $title, Author: $author, ISBN10: $isbn10, ISBN13: $isbn13, Price: $price")
        
        return ParsedBookInfo(
            title = title,
            author = author,
            isbn10 = isbn10,
            isbn13 = isbn13,
            price = price,
            storeName = getStoreName(),
            storeUrl = url,
            coverImageUrl = coverImageUrl
        )
    }
    
    override fun getStoreName(): String {
        return "ThriftBooks"
    }
    
    private fun extractTitle(document: Document): String? {
        // Try multiple title extraction methods
        val titleSelectors = listOf(
            "h1.WorkMeta-title",
            ".WorkDetailsMobile-title",
            "[itemprop=\"name\"]",
            "meta[property=\"og:title\"]",
            "title"
        )
        
        for (selector in titleSelectors) {
            val element = if (selector.startsWith("meta")) {
                document.selectFirst(selector)?.attr("content")
            } else {
                document.selectFirst(selector)?.text()
            }
            
            if (!element.isNullOrBlank()) {
                // Clean up ThriftBooks specific suffixes
                val cleanTitle = element
                    .replace(" book", "", ignoreCase = true)
                    .replace("Ancient Near Eastern Texts Relating to...", "Ancient Near Eastern Texts Relating to the Old Testament with Supplement")
                    .trim()
                
                if (cleanTitle.isNotBlank()) {
                    Log.d("ThriftBooksParser", "Found title using selector '$selector': $cleanTitle")
                    return cleanTitle
                }
            }
        }
        
        // Try extracting from structured data
        return tryExtractFromStructuredData(document, "page_name")
    }
    
    private fun extractAuthor(document: Document): String? {
        // Try multiple author extraction methods
        val authorSelectors = listOf(
            ".WorkMeta-byline",
            ".WorkDetailsMobile-author",
            "[itemprop=\"author\"]",
            ".WorkMeta-detailValue:contains(Author)",
            ".BookSlide-Author"
        )
        
        for (selector in authorSelectors) {
            val element = document.selectFirst(selector)?.text()
            if (!element.isNullOrBlank()) {
                val cleanAuthor = element
                    .replace("by ", "", ignoreCase = true)
                    .replace("By: ", "", ignoreCase = true)
                    .trim()
                
                if (cleanAuthor.isNotBlank() && 
                    !cleanAuthor.equals("unknown", ignoreCase = true) &&
                    !cleanAuthor.contains("Customer Reviews", ignoreCase = true) &&
                    !cleanAuthor.contains("No reviews", ignoreCase = true)) {
                    Log.d("ThriftBooksParser", "Found author using selector '$selector': $cleanAuthor")
                    return cleanAuthor
                }
            }
        }
        
        // Try to find "By [Author Name]" pattern in the page text
        val bodyText = document.body().text()
        val byAuthorRegex = "By\\s+([A-Za-z\\s.,'-]+?)(?:\\s*Empty Star|\\s*No Customer Reviews|\\s*\\d+|\\$|\\n|$)".toRegex()
        val match = byAuthorRegex.find(bodyText)
        if (match != null) {
            val author = match.groupValues[1].trim()
            if (author.isNotBlank() && 
                !author.contains("Customer Reviews", ignoreCase = true) &&
                !author.contains("reviews", ignoreCase = true) &&
                !author.contains("star", ignoreCase = true) &&
                author.length < 100) { // Reasonable author name length
                Log.d("ThriftBooksParser", "Found author using regex pattern: $author")
                return author
            }
        }
        
        // Look for text after title that might contain author
        val titleElement = document.selectFirst("h1.WorkMeta-title, .WorkDetailsMobile-title, [itemprop=\"name\"]")
        if (titleElement != null) {
            val nextElements = titleElement.nextElementSiblings()
            for (element in nextElements.take(5)) { // Check next few elements
                val text = element.text()
                if (text.startsWith("By ", ignoreCase = true)) {
                    val author = text.replace("By ", "", ignoreCase = true).trim()
                    if (author.isNotBlank() && 
                        !author.contains("Customer Reviews", ignoreCase = true) &&
                        !author.contains("reviews", ignoreCase = true) &&
                        author.length < 100) {
                        Log.d("ThriftBooksParser", "Found author after title: $author")
                        return author
                    }
                }
            }
        }
        
        // ThriftBooks sometimes doesn't display author information prominently
        return null
    }
    
    private fun extractIsbn10(document: Document): String? {
        // Look for ISBN-10 in specific elements
        val isbnSelectors = listOf(
            ".WorkSelector-bold",
            ".WorkMeta-detailValue",
            ".WorkCoverSidebar-isbns .WorkSelector-bold"
        )
        
        // Try to find "ISBN: " followed by a 10-digit number
        for (selector in isbnSelectors) {
            val elements = document.select(selector)
            for (element in elements) {
                val text = element.text()
                
                // Look for 10-digit ISBN (without hyphens)
                val isbn10Regex = "\\b(\\d{10})\\b".toRegex()
                val match = isbn10Regex.find(text)
                
                if (match != null) {
                    val isbn10 = match.groupValues[1]
                    Log.d("ThriftBooksParser", "Found ISBN-10: $isbn10")
                    return isbn10
                }
            }
        }
        
        // Try extracting from ISBN: label specifically
        val isbnElement = document.selectFirst("*:contains(ISBN:)")
        if (isbnElement != null) {
            val isbnText = isbnElement.text()
            val isbn10Regex = "ISBN:\\s*(\\d{10})".toRegex()
            val match = isbn10Regex.find(isbnText)
            if (match != null) {
                val isbn10 = match.groupValues[1]
                Log.d("ThriftBooksParser", "Found ISBN-10 from label: $isbn10")
                return isbn10
            }
        }
        
        return null
    }
    
    private fun extractIsbn13(document: Document): String? {
        // Look for ISBN-13 in specific elements
        val isbnSelectors = listOf(
            ".WorkSelector-bold",
            ".WorkMeta-detailValue",
            ".WorkCoverSidebar-isbns .WorkSelector-bold"
        )
        
        // Try to find 13-digit ISBN
        for (selector in isbnSelectors) {
            val elements = document.select(selector)
            for (element in elements) {
                val text = element.text()
                
                // Look for 13-digit ISBN (without hyphens)
                val isbn13Regex = "\\b(\\d{13})\\b".toRegex()
                val match = isbn13Regex.find(text)
                
                if (match != null) {
                    val isbn13 = match.groupValues[1]
                    Log.d("ThriftBooksParser", "Found ISBN-13: $isbn13")
                    return isbn13
                }
            }
        }
        
        // Try extracting from ISBN13: label specifically
        val isbn13Element = document.selectFirst("*:contains(ISBN13:)")
        if (isbn13Element != null) {
            val isbn13Text = isbn13Element.text()
            val isbn13Regex = "ISBN13:\\s*(\\d{13})".toRegex()
            val match = isbn13Regex.find(isbn13Text)
            if (match != null) {
                val isbn13 = match.groupValues[1]
                Log.d("ThriftBooksParser", "Found ISBN-13 from label: $isbn13")
                return isbn13
            }
        }
        
        return null
    }
    
    private fun extractPrice(document: Document): Double? {
        val priceSelectors = listOf(
            ".WorkDetailsMobile-price",
            ".WorkSelector-price .price",
            ".price",
            "[class*=\"price\"]"
        )
        
        for (selector in priceSelectors) {
            val element = document.selectFirst(selector)?.text()
            if (!element.isNullOrBlank()) {
                // Extract price as number
                val priceRegex = "\\$?([0-9]+(?:\\.[0-9]{2})?)".toRegex()
                val match = priceRegex.find(element)
                if (match != null) {
                    val priceValue = match.groupValues[1].toDoubleOrNull()
                    if (priceValue != null) {
                        Log.d("ThriftBooksParser", "Found price using selector '$selector': $priceValue")
                        return priceValue
                    }
                }
            }
        }
        
        // Try extracting from structured data
        val structuredPrice = tryExtractFromStructuredData(document, "product_price")
        if (structuredPrice != null) {
            val priceValue = structuredPrice.toDoubleOrNull()
            if (priceValue != null) {
                Log.d("ThriftBooksParser", "Found price from structured data: $priceValue")
                return priceValue
            }
        }
        
        return null
    }
    
    private fun extractCoverImage(document: Document): String? {
        val imageSelectors = listOf(
            "[itemprop=\"image\"]",
            ".WorkCover img",
            ".WorkDetailsMobile-thumbnail img",
            "img[alt*=\"Cover\"]",
            "img[src*=\"thriftbooks.com\"]"
        )
        
        for (selector in imageSelectors) {
            val element = document.selectFirst(selector)
            val imageUrl = element?.attr("src") ?: element?.attr("data-src")
            
            if (!imageUrl.isNullOrBlank() && !imageUrl.contains("placeholder", ignoreCase = true)) {
                // Prefer larger image sizes
                val largeImageUrl = imageUrl
                    .replace("/s/", "/m/")
                    .replace("/xs/", "/m/")
                
                Log.d("ThriftBooksParser", "Found cover image using selector '$selector': $largeImageUrl")
                return largeImageUrl
            }
        }
        
        return null
    }
    

    
    private fun tryExtractFromStructuredData(document: Document, key: String): String? {
        try {
            // Look for utag_data script
            val scripts = document.select("script")
            for (script in scripts) {
                val scriptContent = script.html()
                if (scriptContent.contains("utag_data")) {
                    // Try to extract the value for the given key
                    val regex = "\"$key\":\\s*\\[?\"([^\"]+)\"]?".toRegex()
                    val match = regex.find(scriptContent)
                    if (match != null) {
                        val value = match.groupValues[1]
                        Log.d("ThriftBooksParser", "Found $key from structured data: $value")
                        return value
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ThriftBooksParser", "Error extracting from structured data: ${e.message}")
        }
        return null
    }
} 