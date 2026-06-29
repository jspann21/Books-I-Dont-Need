package com.booktracker.booksidntneed.network

import android.util.Log
import org.jsoup.nodes.Document

class EbayParser : BookParser {
    
    override fun canParse(url: String): Boolean {
        return url.contains("ebay.com", ignoreCase = true) ||
               url.contains("ebay.co.uk", ignoreCase = true) ||
               url.contains("ebay.ca", ignoreCase = true) ||
               url.contains("ebay.de", ignoreCase = true) ||
               url.contains("ebay.fr", ignoreCase = true) ||
               url.contains("ebay.it", ignoreCase = true) ||
               url.contains("ebay.es", ignoreCase = true) ||
               url.contains("ebay.com.au", ignoreCase = true)
    }
    
    override fun getStoreName(): String = "eBay"
    
    override fun parse(document: Document, url: String): ParsedBookInfo? {
        try {
            Log.d("BookTracker", "EbayParser: Starting to parse URL: $url")
            
            val title = extractTitle(document)
            val author = extractAuthor(document, title)
            val isbn = extractISBN(document)
            val price = extractPrice(document)
            val coverImage = extractCoverImage(document)
            
            Log.d("BookTracker", "EbayParser: Extracted data - Title: '$title', Author: '$author', Price: $price, ISBN-10: '${isbn.first}', ISBN-13: '${isbn.second}'")
            
            if (title.isNullOrBlank()) {
                Log.w("BookTracker", "EbayParser: Missing required title")
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
            
            Log.d("BookTracker", "EbayParser: Successfully created ParsedBookInfo")
            return bookInfo
            
        } catch (e: Exception) {
            Log.e("BookTracker", "EbayParser: Exception during parsing", e)
            return null
        }
    }
    
    private fun extractTitle(document: Document): String? {
        Log.d("BookTracker", "EbayParser: Starting title extraction")

        val itemSpecificsRows = document.select(".ux-labels-values")
        for (row in itemSpecificsRows) {
            val labelText = row.select(".ux-labels-values__labels .ux-textspans").text()
            val valueText = row.select(".ux-labels-values__values .ux-textspans").text()

            if (labelText.equals("Book Title", ignoreCase = true) && valueText.isNotBlank()) {
                Log.d("BookTracker", "EbayParser: Found title in item specifics: '$valueText'")
                return cleanTitle(valueText)
            }
        }
        
        // eBay title selectors (in order of priority)
        val titleSelectors = listOf(
            "h1[data-testid='x-item-title-label']", // Main item title
            "h1.x-item-title-label",
            "h1#x-item-title-label",
            "h1", // Fallback h1
            ".notranslate" // Sometimes title is in notranslate class
        )
        
        for (selector in titleSelectors) {
            val titleElement = document.select(selector).first()
            val title = titleElement?.text()?.trim()
            if (!title.isNullOrBlank() && title.length > 3 && !title.contains("eBay", ignoreCase = true)) {
                Log.d("BookTracker", "EbayParser: Found title with selector '$selector': '$title'")
                return cleanTitle(title)
            }
        }
        
        // Try meta tags as fallback
        val metaTitle = document.select("meta[property='og:title']").attr("content")
        if (metaTitle.isNotBlank() && !metaTitle.contains("eBay", ignoreCase = true)) {
            Log.d("BookTracker", "EbayParser: Found title in meta tag: '$metaTitle'")
            return cleanTitle(metaTitle)
        }
        
        // Try page title as final fallback
        val pageTitle = document.select("title").first()?.text()
        if (!pageTitle.isNullOrBlank()) {
            // eBay page titles typically end with "| eBay"
            val cleanPageTitle = pageTitle.replace(Regex("\\s*\\|\\s*eBay.*$"), "").trim()
            if (cleanPageTitle.isNotBlank() && cleanPageTitle.length > 3) {
                Log.d("BookTracker", "EbayParser: Extracted title from page title: '$cleanPageTitle'")
                return cleanTitle(cleanPageTitle)
            }
        }
        
        Log.w("BookTracker", "EbayParser: No title found")
        return null
    }
    
    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("\\s*\\|\\s*eBay.*$"), "") // Remove "| eBay" suffix
            .replace(Regex("\\s*-\\s*eBay.*$"), "") // Remove "- eBay" suffix
            .trim()
    }
    
    private fun extractAuthor(document: Document, title: String?): String? {
        Log.d("BookTracker", "EbayParser: Starting author extraction")
        
        // Try to extract author from title first (common pattern: "Title - Author - Subtitle")
        if (!title.isNullOrBlank()) {
            // Pattern: "Unoffendable - Brant Hansen - How Just One Change Can Make All of Life Better"
            val titleParts = title.split(" - ")
            if (titleParts.size >= 2) {
                // Look for the author part (usually the second part if it looks like a name)
                for (i in 1 until titleParts.size) {
                    val part = titleParts[i].trim()
                    if (isLikelyAuthorName(part)) {
                        Log.d("BookTracker", "EbayParser: Found author in title: '$part'")
                        return cleanAuthorName(part)
                    }
                }
            }
        }
        
        // Look for author in item specifics section
        val itemSpecificsRows = document.select(".ux-labels-values")
        for (row in itemSpecificsRows) {
            val labelText = row.select(".ux-labels-values__labels .ux-textspans").text()
            val valueText = row.select(".ux-labels-values__values .ux-textspans").text()
            
            if (labelText.contains("Author", ignoreCase = true) && valueText.isNotBlank()) {
                Log.d("BookTracker", "EbayParser: Found author in item specifics: '$valueText'")
                return cleanAuthorName(valueText)
            }
        }
        
        // Look for "by [Author]" pattern in description or other text
        val allText = document.text()
        val byPattern = Regex("(?i)\\bby\\s+([A-Za-z\\s.]+?)(?:\\s|$|,|\\.)")
        val byMatch = byPattern.find(allText)
        if (byMatch != null) {
            val author = byMatch.groupValues[1].trim()
            if (author.length > 2 && isLikelyAuthorName(author)) {
                Log.d("BookTracker", "EbayParser: Found author with 'by' pattern: '$author'")
                return cleanAuthorName(author)
            }
        }
        
        Log.w("BookTracker", "EbayParser: No author found")
        return null
    }
    
    private fun isLikelyAuthorName(text: String): Boolean {
        // Basic check if text looks like an author name
        return text.matches(Regex("^[A-Za-z\\s.]+$")) &&
               text.split("\\s+".toRegex()).size <= 4 && // Not too many words
               !text.contains("How", ignoreCase = true) && // Not part of title
               !text.contains("Just", ignoreCase = true) &&
               !text.contains("One", ignoreCase = true) &&
               !text.contains("Change", ignoreCase = true) &&
               !text.contains("Make", ignoreCase = true) &&
               !text.contains("Life", ignoreCase = true) &&
               !text.contains("Better", ignoreCase = true)
    }
    
    private fun cleanAuthorName(author: String): String {
        return author.replace("By:", "", ignoreCase = true)
            .replace("Author:", "", ignoreCase = true)
            .replace(Regex("\\(.*?\\)"), "") // Remove parentheses content
            .trim()
    }
    
    private fun extractPrice(document: Document): Double? {
        Log.d("BookTracker", "EbayParser: Starting price extraction")
        
        // eBay price selectors
        val priceSelectors = listOf(
            ".notranslate", // Price is often in notranslate spans
            "[data-testid='price']",
            ".price",
            ".current-price",
            ".notranslate span"
        )
        
        for (selector in priceSelectors) {
            val elements = document.select(selector)
            Log.d("BookTracker", "EbayParser: Trying price selector '$selector', found ${elements.size} elements")
            
            for (element in elements) {
                val priceText = element.text()
                if (priceText.isNotBlank() && priceText.contains("$")) {
                    Log.d("BookTracker", "EbayParser: Found potential price text: '$priceText'")
                    val price = extractPriceFromText(priceText)
                    if (price != null && price > 0) {
                        Log.d("BookTracker", "EbayParser: Successfully extracted price: $price")
                        return price
                    }
                }
            }
        }
        
        // Look for US $ pattern in the entire page
        val allText = document.text()
        val pricePattern = Regex("US\\s*\\$\\s*(\\d+\\.\\d{2})")
        val priceMatch = pricePattern.find(allText)
        if (priceMatch != null) {
            val priceValue = priceMatch.groupValues[1].toDoubleOrNull()
            if (priceValue != null && priceValue > 0) {
                Log.d("BookTracker", "EbayParser: Found price with US $ pattern: $priceValue")
                return priceValue
            }
        }
        
        Log.w("BookTracker", "EbayParser: No price found")
        return null
    }
    
    private fun extractPriceFromText(priceText: String): Double? {
        return try {
            // Remove currency symbols and clean the text
            val cleanPrice = priceText.replace("US", "")
                .replace("$", "")
                .replace(",", "")
                .replace("USD", "")
                .replace("Free", "0")
                .trim()
            
            // Handle various price formats
            val priceMatch = Regex("(\\d+\\.\\d{2})").find(cleanPrice)
            priceMatch?.value?.toDoubleOrNull()
        } catch (e: Exception) {
            Log.e("BookTracker", "EbayParser: Error parsing price from '$priceText'", e)
            null
        }
    }
    
    private fun extractISBN(document: Document): Pair<String?, String?> {
        var isbn10: String? = null
        var isbn13: String? = null
        
        Log.d("BookTracker", "EbayParser: Starting ISBN extraction")
        
        // Look for ISBN in item specifics section
        val itemSpecificsRows = document.select(".ux-labels-values")
        
        for (row in itemSpecificsRows) {
            val labelText = row.select(".ux-labels-values__labels .ux-textspans").text()
            val valueText = row.select(".ux-labels-values__values .ux-textspans").text()
            
            Log.d("BookTracker", "EbayParser: Item specific - Label: '$labelText', Value: '$valueText'")
            
            if (labelText.contains("ISBN", ignoreCase = true) && valueText.isNotBlank()) {
                if (labelText.contains("ISBN-10", ignoreCase = true)) {
                    isbn10 = cleanISBN(valueText)
                    Log.d("BookTracker", "EbayParser: Found ISBN-10: '$isbn10'")
                } else if (labelText.contains("ISBN-13", ignoreCase = true) || labelText.equals("ISBN", ignoreCase = true)) {
                    val cleanedISBN = cleanISBN(valueText)
                    if (cleanedISBN != null && cleanedISBN.length == 13) {
                        isbn13 = cleanedISBN
                        Log.d("BookTracker", "EbayParser: Found ISBN-13: '$isbn13'")
                    } else if (cleanedISBN != null && cleanedISBN.length == 10) {
                        isbn10 = cleanedISBN
                        Log.d("BookTracker", "EbayParser: Found ISBN-10: '$isbn10'")
                    }
                }
            }
        }
        
        // Also search for JSON data containing ISBN values.
        val scriptElements = document.select("script")
        for (script in scriptElements) {
            val scriptText = script.html()
            if (isbn13 == null) {
                val isbn13Match = Regex("\\b(97[89][\\s-]?\\d[\\s-]?\\d[\\s-]?\\d[\\s-]?\\d[\\s-]?\\d[\\s-]?\\d[\\s-]?\\d[\\s-]?\\d[\\s-]?\\d[\\s-]?\\d)\\b")
                    .find(scriptText)
                val cleanedISBN = isbn13Match?.groupValues?.get(1)?.let { cleanISBN(it) }
                if (cleanedISBN != null) {
                    isbn13 = cleanedISBN
                    Log.d("BookTracker", "EbayParser: Found ISBN-13 in script: '$isbn13'")
                }
            }

            if (isbn10 == null) {
                val isbn10Match = Regex("\\b(\\d[\\s-]?\\d[\\s-]?\\d[\\s-]?\\d[\\s-]?\\d[\\s-]?\\d[\\s-]?\\d[\\s-]?\\d[\\s-]?\\d[\\s-]?[\\dX])\\b", RegexOption.IGNORE_CASE)
                    .find(scriptText)
                val cleanedISBN = isbn10Match?.groupValues?.get(1)?.let { cleanISBN(it) }
                if (cleanedISBN != null) {
                    isbn10 = cleanedISBN
                    Log.d("BookTracker", "EbayParser: Found ISBN-10 in script: '$isbn10'")
                }
            }
        }
        
        // General ISBN search in all text as fallback
        if (isbn13 == null && isbn10 == null) {
            val allText = document.text()
            
            // Look for 13-digit ISBN
            val isbn13Pattern = Regex("\\b(97[89]\\d{10})\\b")
            val isbn13Match = isbn13Pattern.find(allText)
            if (isbn13Match != null) {
                isbn13 = isbn13Match.groupValues[1]
                Log.d("BookTracker", "EbayParser: Found ISBN-13 in text: '$isbn13'")
            }
            
            // Look for 10-digit ISBN
            val isbn10Pattern = Regex("\\b(\\d{9}[\\dX])\\b")
            val isbn10Match = isbn10Pattern.find(allText)
            if (isbn10Match != null) {
                isbn10 = isbn10Match.groupValues[1]
                Log.d("BookTracker", "EbayParser: Found ISBN-10 in text: '$isbn10'")
            }
        }
        
        Log.d("BookTracker", "EbayParser: Final ISBN results - ISBN-10: '$isbn10', ISBN-13: '$isbn13'")
        return Pair(isbn10, isbn13)
    }
    
    private fun cleanISBN(isbn: String): String? {
        val cleaned = isbn.replace(Regex("[\\s-]"), "") // Remove spaces and hyphens
        return if (cleaned.matches(Regex("\\d{9}[\\dX]")) || cleaned.matches(Regex("\\d{13}"))) {
            cleaned
        } else {
            null
        }
    }
    
    private fun extractCoverImage(document: Document): String? {
        Log.d("BookTracker", "EbayParser: Starting cover image extraction")
        
        // eBay image selectors
        val imageSelectors = listOf(
            ".ux-image-carousel img[loading='eager']", // Main product image
            ".ux-image-carousel img",
            "[data-testid='ux-image-carousel'] img",
            ".ux-image-carousel-item img",
            "img[alt*='Picture 1']",
            "img[data-zoom-src]"
        )
        
        for (selector in imageSelectors) {
            val imageElement = document.select(selector).first()
            var imageUrl = imageElement?.attr("src")
            
            // Try data-zoom-src if src is not available or is small
            if (imageUrl.isNullOrBlank() || imageUrl.contains("s-l96") || imageUrl.contains("s-l140")) {
                imageUrl = imageElement?.attr("data-zoom-src")
            }
            
            if (!imageUrl.isNullOrBlank() && imageUrl.startsWith("http")) {
                // Prefer larger image sizes
                if (imageUrl.contains("s-l500") || imageUrl.contains("s-l1600")) {
                    Log.d("BookTracker", "EbayParser: Found cover image: '$imageUrl'")
                    return imageUrl
                }
            }
        }
        
        // Try meta image as fallback
        val metaImage = document.select("meta[property='og:image']").attr("content")
        if (metaImage.isNotBlank() && metaImage.startsWith("http")) {
            Log.d("BookTracker", "EbayParser: Found cover image in meta tag: '$metaImage'")
            return metaImage
        }
        
        Log.w("BookTracker", "EbayParser: No cover image found")
        return null
    }
    
} 
