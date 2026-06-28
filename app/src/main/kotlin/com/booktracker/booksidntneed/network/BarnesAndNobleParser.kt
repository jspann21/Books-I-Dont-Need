package com.booktracker.booksidntneed.network

import android.util.Log
import org.jsoup.nodes.Document

class BarnesAndNobleParser : BookParser {
    
    override fun canParse(url: String): Boolean {
        return url.contains("barnesandnoble.com", ignoreCase = true) ||
               url.contains("bn.com", ignoreCase = true)
    }
    
    override fun getStoreName(): String = "Barnes & Noble"
    
    override fun parse(document: Document, url: String): ParsedBookInfo? {
        try {
            Log.d("BookTracker", "BarnesAndNobleParser: Starting to parse URL: $url")
            
            val title = extractTitle(document)
            val author = extractAuthor(document)
            val price = extractPrice(document)
            val isbn = extractISBN(document)
            val coverImage = extractCoverImage(document)
            
            Log.d("BookTracker", "BarnesAndNobleParser: Extracted data - Title: '$title', Author: '$author', Price: $price")
            
            if (title.isNullOrBlank() || author.isNullOrBlank()) {
                Log.w("BookTracker", "BarnesAndNobleParser: Missing required fields - Title: '$title', Author: '$author'")
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
            
            Log.d("BookTracker", "BarnesAndNobleParser: Successfully created ParsedBookInfo")
            return bookInfo
        } catch (e: Exception) {
            Log.e("BookTracker", "BarnesAndNobleParser: Exception during parsing", e)
            return null
        }
    }
    
    private fun extractTitle(document: Document): String? {
        // Try multiple selectors for title with more comprehensive fallbacks
        val titleSelectors = listOf(
            "h1.pdp-product-title",
            "[data-testid='product-title']",
            ".product-title h1",
            "h1[data-testid*='title']",
            ".pdp-product-name h1",
            ".product-info h1",
            ".book-title",
            "h1.product-name",
            "h1",
            "[data-automation-id='product-title']"
        )
        
        for (selector in titleSelectors) {
            val title = document.select(selector).first()?.text()?.trim()
            if (!title.isNullOrBlank() && title.length > 3) {
                Log.d("BookTracker", "BarnesAndNobleParser: Found title with selector '$selector': '$title'")
                return cleanTitle(title)
            }
        }
        
        // Fallback: try to extract from page title
        val pageTitle = document.select("title").first()?.text()
        if (!pageTitle.isNullOrBlank()) {
            Log.d("BookTracker", "BarnesAndNobleParser: Trying to extract title from page title: '$pageTitle'")
            
            // Barnes & Noble typically uses format: "Book Title by Author | Barnes & Noble"
            val titlePattern = Regex("^([^|]+?)(?:\\s*by\\s+[^|]+)?\\s*\\|\\s*Barnes")
            val match = titlePattern.find(pageTitle)
            if (match != null) {
                val extractedTitle = match.groupValues[1].trim()
                if (extractedTitle.isNotBlank()) {
                    Log.d("BookTracker", "BarnesAndNobleParser: Extracted title from page title: '$extractedTitle'")
                    return cleanTitle(extractedTitle)
                }
            }
        }
        
        Log.w("BookTracker", "BarnesAndNobleParser: No title found")
        return null
    }
    
    private fun cleanTitle(title: String): String {
        return title.replace(Regex("\\(.*?\\)$"), "") // Remove trailing parentheses
            .trim()
    }
    
    private fun extractAuthor(document: Document): String? {
        val authorSelectors = listOf(
            "[data-testid='author-list'] a",
            ".contributors a",
            ".product-details .author",
            ".pdp-contributor-list a",
            "[data-testid='contributors'] a",
            ".author-link",
            ".book-contributors a",
            ".contributor-name",
            "[data-automation-id='author'] a",
            "a[href*='/author/']",
            ".by-author a"
        )
        
        for (selector in authorSelectors) {
            val elements = document.select(selector)
            for (element in elements) {
                val author = element.text().trim()
                if (author.isNotBlank() && author.length > 2 && !author.contains("(")) {
                    Log.d("BookTracker", "BarnesAndNobleParser: Found author with selector '$selector': '$author'")
                    return cleanAuthorName(author)
                }
            }
        }
        
        // Fallback: look for author in text patterns
        val authorTextSelectors = listOf(
            ".contributors",
            ".pdp-contributor-list",
            ".product-details",
            ".book-details"
        )
        
        for (selector in authorTextSelectors) {
            val text = document.select(selector).first()?.text()
            if (!text.isNullOrBlank()) {
                val byRegex = Regex("(?i)by\\s+([^,()]+?)(?:,|\\(|$)")
                val match = byRegex.find(text)
                if (match != null) {
                    val author = match.groupValues[1].trim()
                    if (author.length > 2) {
                        Log.d("BookTracker", "BarnesAndNobleParser: Found author with 'by' pattern: '$author'")
                        return cleanAuthorName(author)
                    }
                }
            }
        }
        
        // Final fallback: extract from page title
        val pageTitle = document.select("title").first()?.text()
        if (!pageTitle.isNullOrBlank()) {
            val authorPattern = Regex("by\\s+([^|]+?)\\s*\\|")
            val match = authorPattern.find(pageTitle)
            if (match != null) {
                val author = match.groupValues[1].trim()
                if (author.isNotBlank()) {
                    Log.d("BookTracker", "BarnesAndNobleParser: Extracted author from page title: '$author'")
                    return cleanAuthorName(author)
                }
            }
        }
        
        Log.w("BookTracker", "BarnesAndNobleParser: No author found")
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
        Log.d("BookTracker", "BarnesAndNobleParser: Starting price extraction")
        
        val priceSelectors = listOf(
            "[data-testid='current-price']",
            ".current-price",
            ".price-current",
            ".pdp-price .current",
            ".price .current",
            "[data-testid='price']",
            ".product-price .current",
            ".price-info .price",
            ".book-price",
            "[data-automation-id='price']"
        )
        
        for (selector in priceSelectors) {
            val elements = document.select(selector)
            Log.d("BookTracker", "BarnesAndNobleParser: Trying price selector '$selector', found ${elements.size} elements")
            
            for (element in elements) {
                val priceText = element.text()
                if (priceText.isNotBlank()) {
                    Log.d("BookTracker", "BarnesAndNobleParser: Found price text: '$priceText'")
                    val price = extractPriceFromText(priceText)
                    if (price != null && price > 0) {
                        Log.d("BookTracker", "BarnesAndNobleParser: Successfully extracted price: $price")
                        return price
                    }
                }
            }
        }
        
        Log.w("BookTracker", "BarnesAndNobleParser: No price found")
        return null
    }
    
    private fun extractPriceFromText(priceText: String): Double? {
        return try {
            // Remove currency symbols and clean the text
            val cleanPrice = priceText.replace("$", "")
                .replace(",", "")
                .replace("USD", "")
                .replace("Free", "0")
                .trim()
            
            // Handle various price formats
            val priceMatch = Regex("(\\d+\\.\\d{2})").find(cleanPrice)
            priceMatch?.value?.toDoubleOrNull()
        } catch (e: Exception) {
            Log.e("BookTracker", "BarnesAndNobleParser: Error parsing price from '$priceText'", e)
            null
        }
    }
    
    private fun extractISBN(document: Document): Pair<String?, String?> {
        var isbn10: String? = null
        var isbn13: String? = null
        
        Log.d("BookTracker", "BarnesAndNobleParser: Starting ISBN extraction")
        
        // First try to get ISBN from structured data
        val isbnElement = document.select("[itemprop='isbn']").first()
        if (isbnElement != null) {
            val isbnValue = isbnElement.text().trim()
            if (isbnValue.isNotBlank()) {
                if (isbnValue.length == 10) {
                    isbn10 = isbnValue
                    Log.d("BookTracker", "BarnesAndNobleParser: Found ISBN-10 from itemprop: '$isbn10'")
                } else if (isbnValue.length == 13) {
                    isbn13 = isbnValue
                    Log.d("BookTracker", "BarnesAndNobleParser: Found ISBN-13 from itemprop: '$isbn13'")
                }
            }
        }
        
        // Try to extract missing ISBNs from image URL
        if (isbn10 == null || isbn13 == null) {
            Log.d("BookTracker", "BarnesAndNobleParser: Attempting to extract ISBN from image URL")
            
            // Get image URL without HTTPS conversion for ISBN extraction
            val imageUrl = extractCoverImageForISBN(document)
            if (imageUrl != null) {
                Log.d("BookTracker", "BarnesAndNobleParser: Using image URL for ISBN extraction: '$imageUrl'")
                val isbnFromImage = extractISBNFromImageUrl(imageUrl)
                Log.d("BookTracker", "BarnesAndNobleParser: Extracted ISBN from image URL: '$isbnFromImage'")
                
                if (isbnFromImage != null) {
                    if (isbnFromImage.length == 10 && isbn10 == null) {
                        isbn10 = isbnFromImage
                        Log.d("BookTracker", "BarnesAndNobleParser: Set ISBN-10 from image URL: '$isbn10'")
                    } else if (isbnFromImage.length == 13 && isbn13 == null) {
                        isbn13 = isbnFromImage
                        Log.d("BookTracker", "BarnesAndNobleParser: Set ISBN-13 from image URL: '$isbn13'")
                    }
                }
            }
        }
        
        // Fallback: Look for ISBN in product details tables
        if (isbn10 == null && isbn13 == null) {
            val detailSelectors = listOf(
                ".product-details-tabs",
                ".specifications",
                ".product-info",
                ".book-details",
                ".pdp-details",
                "[data-testid='product-details']",
                "table"
            )
            
            for (selector in detailSelectors) {
                val sections = document.select(selector)
                for (section in sections) {
                    val text = section.text()
                    if (text.contains("ISBN", ignoreCase = true)) {
                        Log.d("BookTracker", "BarnesAndNobleParser: Found ISBN section: '$text'")
                        
                        if (text.contains("ISBN-10", ignoreCase = true) && isbn10 == null) {
                            isbn10 = extractISBNFromText(text)
                            Log.d("BookTracker", "BarnesAndNobleParser: Extracted ISBN-10: '$isbn10'")
                        }
                        if (text.contains("ISBN-13", ignoreCase = true) && isbn13 == null) {
                            isbn13 = extractISBNFromText(text)
                            Log.d("BookTracker", "BarnesAndNobleParser: Extracted ISBN-13: '$isbn13'")
                        }
                    }
                }
            }
        }
        
        Log.d("BookTracker", "BarnesAndNobleParser: Final ISBN results - ISBN-10: '$isbn10', ISBN-13: '$isbn13'")
        return Pair(isbn10, isbn13)
    }
    
    private fun extractCoverImageForISBN(document: Document): String? {
        // Extract image URL without HTTPS conversion for ISBN extraction
        
        // Try structured data first
        val structuredImage = document.select("img[itemprop='image']").first()
        if (structuredImage != null) {
            val imageUrl = structuredImage.attr("src")
            if (imageUrl.isNotBlank() && isValidImageUrl(imageUrl)) {
                return imageUrl
            }
        }
        
        // Try Open Graph meta tag
        val ogImage = document.select("meta[property='og:image']").first()
        if (ogImage != null) {
            val imageUrl = ogImage.attr("content")
            if (imageUrl.isNotBlank() && isValidImageUrl(imageUrl)) {
                return imageUrl
            }
        }
        
        return null
    }
    
    private fun extractISBNFromImageUrl(imageUrl: String): String? {
        // B&N image URLs typically contain ISBN, e.g., "/pimages/9781433572852_p0_v2_s500x550.jpg"
        val isbnPattern = Regex("/(\\d{10}|\\d{13})[_./]")
        val match = isbnPattern.find(imageUrl)
        return match?.groupValues?.get(1)
    }
    
    private fun extractISBNFromText(text: String): String? {
        // Extract ISBN numbers from text
        val isbnWithLabelRegex = Regex("ISBN-?(?:10|13)?[:\\s]+([\\d\\sX-]+)")
        val labelMatch = isbnWithLabelRegex.find(text)
        if (labelMatch != null) {
            val isbnCandidate = labelMatch.groupValues[1].replace(Regex("[\\s-]"), "")
            if (isbnCandidate.matches(Regex("\\d{9}[\\dX]")) || isbnCandidate.matches(Regex("\\d{13}"))) {
                return isbnCandidate
            }
        }
        
        // Fallback: extract any ISBN-like numbers
        val isbnRegex = Regex("\\b\\d{9}[\\dX]\\b|\\b\\d{13}\\b")
        return isbnRegex.find(text)?.value
    }
    
    private fun extractCoverImage(document: Document): String? {
        Log.d("BookTracker", "BarnesAndNobleParser: Starting image extraction")
        
        // First try structured data
        val structuredImage = document.select("img[itemprop='image']").first()
        if (structuredImage != null) {
            val imageUrl = structuredImage.attr("src")
            if (imageUrl.isNotBlank() && isValidImageUrl(imageUrl)) {
                val httpsUrl = convertToHttps(imageUrl)
                Log.d("BookTracker", "BarnesAndNobleParser: Found image from itemprop: $httpsUrl")
                return httpsUrl
            }
        }
        
        // Try Open Graph meta tag
        val ogImage = document.select("meta[property='og:image']").first()
        if (ogImage != null) {
            val imageUrl = ogImage.attr("content")
            Log.d("BookTracker", "BarnesAndNobleParser: Original og:image URL: '$imageUrl'")
            if (imageUrl.isNotBlank() && isValidImageUrl(imageUrl)) {
                val httpsUrl = convertToHttps(imageUrl)
                Log.d("BookTracker", "BarnesAndNobleParser: Found image from og:image: $httpsUrl")
                return httpsUrl
            }
        }
        
        // Fallback to regular selectors
        val imageSelectors = listOf(
            "[data-testid='product-image'] img",
            ".product-image img",
            ".pdp-image img",
            ".pdp-product-image img",
            ".cover-image img",
            ".book-cover img",
            ".product-photo img",
            "[data-automation-id='product-image'] img",
            "img[alt*='cover']",
            "img[alt*='book']"
        )
        
        for (selector in imageSelectors) {
            val elements = document.select(selector)
            Log.d("BookTracker", "BarnesAndNobleParser: Trying image selector '$selector', found ${elements.size} elements")
            
            for (element in elements) {
                val imageUrl = element.attr("src")
                val dataSrc = element.attr("data-src")
                
                val urls = listOf(imageUrl, dataSrc).filter { it.isNotBlank() }
                for (url in urls) {
                    if (isValidImageUrl(url)) {
                        val httpsUrl = convertToHttps(url)
                        Log.d("BookTracker", "BarnesAndNobleParser: Found valid image URL: $httpsUrl")
                        return httpsUrl
                    }
                }
            }
        }
        
        Log.w("BookTracker", "BarnesAndNobleParser: No valid image found")
        return null
    }
    
    private fun isValidImageUrl(url: String): Boolean {
        return url.startsWith("http") && 
               !url.contains("1x1") && 
               !url.contains("placeholder") &&
               !url.contains("grey-box.png") &&
               (url.contains("jpg") || url.contains("jpeg") || url.contains("png") || url.contains("webp"))
    }
    
    private fun convertToHttps(url: String): String {
        val httpsUrl = if (url.startsWith("http://")) {
            url.replace("http://", "https://")
        } else {
            url
        }
        Log.d("BookTracker", "BarnesAndNobleParser: Converting '$url' to '$httpsUrl'")
        return httpsUrl
    }
    
} 