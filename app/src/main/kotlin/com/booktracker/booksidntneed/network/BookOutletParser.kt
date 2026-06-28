package com.booktracker.booksidntneed.network

import android.util.Log
import org.jsoup.nodes.Document

class BookOutletParser : BookParser {
    
    override fun canParse(url: String): Boolean {
        return url.contains("bookoutlet.com", ignoreCase = true)
    }
    
    override fun getStoreName(): String = "Book Outlet"
    
    override fun parse(document: Document, url: String): ParsedBookInfo? {
        try {
            Log.d("BookTracker", "BookOutletParser: Starting to parse URL: $url")
            
            val title = extractTitle(document)
            val author = extractAuthor(document)
            val price = extractPrice(document)
            val isbn = extractISBN(document)
            val coverImage = extractCoverImage(document)
            
            Log.d("BookTracker", "BookOutletParser: Extracted data - Title: '$title', Author: '$author', Price: $price")
            
            if (title.isNullOrBlank() || author.isNullOrBlank()) {
                Log.w("BookTracker", "BookOutletParser: Missing required fields - Title: '$title', Author: '$author'")
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
            
            Log.d("BookTracker", "BookOutletParser: Successfully created ParsedBookInfo")
            return bookInfo
        } catch (e: Exception) {
            Log.e("BookTracker", "BookOutletParser: Exception during parsing", e)
            return null
        }
    }
    
    private fun extractTitle(document: Document): String? {
        // Try the main h1 title on the page (primary method that works)
        val h1Title = document.select("h1").first()?.text()?.trim()
        if (!h1Title.isNullOrBlank() && h1Title.length > 3) {
            return cleanTitle(h1Title)
        }
        
        // Fallback: Try Open Graph title
        val ogTitle = document.select("meta[property='og:title']").first()?.attr("content")?.trim()
        if (!ogTitle.isNullOrBlank()) {
            return cleanTitle(ogTitle)
        }
        
        return null
    }
    
    private fun cleanTitle(title: String): String {
        return title.replace(Regex("\\s*\\|\\s*Book Outlet.*$"), "") // Remove " | Book Outlet" suffix
            .replace(Regex("\\(.*?\\)$"), "") // Remove trailing parentheses
            .trim()
    }
    
    private fun extractAuthor(document: Document): String? {
        // Try book:author meta tag (primary method that works)
        val bookAuthor = document.select("meta[property='book:author']").first()?.attr("content")?.trim()
        if (!bookAuthor.isNullOrBlank()) {
            return cleanAuthorName(bookAuthor)
        }
        
        // Fallback: Look for author links
        val authorSelectors = listOf(
            "a[aria-label*='author']",
            "a[href*='/author/']",
            ".author a"
        )
        
        for (selector in authorSelectors) {
            val authorElement = document.select(selector).first()
            if (authorElement != null) {
                val author = authorElement.text().trim()
                if (author.isNotBlank() && author.length > 2) {
                    return cleanAuthorName(author)
                }
            }
        }
        
        return null
    }
    
    private fun cleanAuthorName(author: String): String {
        return author.replace(Regex("\\(.*?\\)"), "") // Remove parentheses content
            .replace(Regex("\\[.*?]"), "")  // Remove bracket content
            .replace("By ", "", ignoreCase = true)
            .replace("Author: ", "", ignoreCase = true)
            .trim()
    }
    
    private fun extractPrice(document: Document): Double? {
        // Try JSON-LD structured data (primary method that works)
        val jsonLdScripts = document.select("script[type='application/ld+json']")
        for (script in jsonLdScripts) {
            try {
                val content = script.html()
                if (content.contains("\"price\"")) {
                    val priceMatch = Regex("\"price\"\\s*:\\s*(\\d+\\.\\d{2})").find(content)
                    if (priceMatch != null) {
                        val price = priceMatch.groupValues[1].toDoubleOrNull()
                        if (price != null && price > 0) {
                            return price
                        }
                    }
                }
            } catch (_: Exception) {
                // Continue to fallback methods
            }
        }
        
        // Fallback: Try to find price in visible text
        val priceSelectors = listOf(".price", "[data-testid='price']", "*:contains($)")
        for (selector in priceSelectors) {
            val elements = document.select(selector)
            for (element in elements) {
                val price = extractPriceFromText(element.text())
                if (price != null && price > 0) {
                    return price
                }
            }
        }
        
        return null
    }
    
    private fun extractPriceFromText(priceText: String): Double? {
        return try {
            val cleanPrice = priceText.replace("$", "")
                .replace(",", "")
                .replace("USD", "")
                .replace("Price:", "")
                .trim()
            
            val priceMatch = Regex("(\\d+\\.\\d{2})").find(cleanPrice)
            priceMatch?.value?.toDoubleOrNull()
        } catch (e: Exception) {
            Log.e("BookTracker", "BookOutletParser: Error parsing price from '$priceText'", e)
            null
        }
    }
    
    private fun extractISBN(document: Document): Pair<String?, String?> {
        var isbn10: String? = null
        var isbn13: String? = null
        
        // Try book:isbn meta tag (primary method that works)
        val bookIsbn = document.select("meta[property='book:isbn']").first()?.attr("content")?.trim()
        if (!bookIsbn.isNullOrBlank()) {
            if (bookIsbn.length == 10) {
                isbn10 = bookIsbn
            } else if (bookIsbn.length == 13) {
                isbn13 = bookIsbn
            }
        }
        
        // Fallback: Try to extract from image URL
        if (isbn10 == null && isbn13 == null) {
            val imageUrl = extractCoverImage(document)
            if (imageUrl != null) {
                val isbnFromImage = extractISBNFromImageUrl(imageUrl)
                if (isbnFromImage != null) {
                    if (isbnFromImage.length == 10) {
                        isbn10 = isbnFromImage
                    } else if (isbnFromImage.length == 13) {
                        isbn13 = isbnFromImage
                    }
                }
            }
        }
        
        return Pair(isbn10, isbn13)
    }
    
    private fun extractISBNFromImageUrl(imageUrl: String): String? {
        // Book Outlet image URLs typically contain ISBN, e.g., ".../isbn978006/9780060652944-l.jpg"
        val isbnPattern = Regex("isbn\\d{6}/(\\d{10,13})")
        val match = isbnPattern.find(imageUrl)
        return match?.groupValues?.get(1)
    }
    
    private fun extractCoverImage(document: Document): String? {
        // Try Open Graph image (primary method that works)
        val ogImage = document.select("meta[property='og:image']").first()?.attr("content")?.trim()
        if (!ogImage.isNullOrBlank() && isValidImageUrl(ogImage)) {
            return ogImage
        }
        
        // Fallback: Try Twitter image
        val twitterImage = document.select("meta[property='twitter:image']").first()?.attr("content")?.trim()
        if (!twitterImage.isNullOrBlank() && isValidImageUrl(twitterImage)) {
            return twitterImage
        }
        
        // Fallback: Try to find main product image
        val imageSelectors = listOf(
            "img[src*='bookoutlet.com/covers']",
            ".product-image img",
            ".book-cover img"
        )
        
        for (selector in imageSelectors) {
            val imageElement = document.select(selector).first()
            if (imageElement != null) {
                val imageUrl = imageElement.attr("src")
                if (imageUrl.isNotBlank() && isValidImageUrl(imageUrl)) {
                    return imageUrl
                }
            }
        }
        
        return null
    }
    
    private fun isValidImageUrl(url: String): Boolean {
        return url.startsWith("http") && 
               !url.contains("1x1") && 
               !url.contains("placeholder") &&
               (url.contains("jpg") || url.contains("jpeg") || url.contains("png") || url.contains("webp"))
    }
    
} 