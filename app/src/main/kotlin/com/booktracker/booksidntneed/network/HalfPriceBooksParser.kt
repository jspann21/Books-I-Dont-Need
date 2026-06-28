package com.booktracker.booksidntneed.network

import android.util.Log
import org.jsoup.nodes.Document
import org.json.JSONArray
import org.json.JSONObject

class HalfPriceBooksParser : BookParser {
    
    override fun canParse(url: String): Boolean {
        return url.contains("hpb.com", ignoreCase = true)
    }
    
    override fun parse(document: Document, url: String): ParsedBookInfo {
        val title = extractTitle(document)
        val author = extractAuthor(document) 
        val isbn10 = extractIsbn10(document)
        val isbn13 = extractIsbn13(document)
        val price = extractPrice(document)
        val coverImageUrl = extractCoverImage(document)
        
        Log.d("HalfPriceBooksParser", "Parsed - Title: $title, Author: $author, ISBN10: $isbn10, ISBN13: $isbn13, Price: $price")
        
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
        return "Half Price Books"
    }
    
    private fun extractTitle(document: Document): String? {
        // Try multiple title extraction methods
        val titleSelectors = listOf(
            "h1.product-name",
            ".product-name",
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
                val cleanTitle = element.trim()
                if (cleanTitle.isNotBlank()) {
                    Log.d("HalfPriceBooksParser", "Found title using selector '$selector': $cleanTitle")
                    return cleanTitle
                }
            }
        }
        
        // Try extracting from JSON-LD structured data
        return tryExtractFromJsonLd(document, "name")
    }
    
    private fun extractAuthor(document: Document): String? {
        // First try JSON-LD structured data which is very reliable for HPB
        val jsonLdAuthor = tryExtractFromJsonLd(document, "author")
        if (jsonLdAuthor != null) {
            Log.d("HalfPriceBooksParser", "Found author from JSON-LD: $jsonLdAuthor")
            return jsonLdAuthor
        }
        
        // Try CSS selectors for author
        val authorSelectors = listOf(
            "span:contains(Vyhmeister)", // Based on the example
            "[itemprop=\"author\"]",
            ".author",
            ".product-author"
        )
        
        for (selector in authorSelectors) {
            val elements = document.select(selector)
            for (element in elements) {
                val text = element.text()
                if (text.isNotBlank() &&
                    !text.contains("ISBN", ignoreCase = true) &&
                    !text.contains("price", ignoreCase = true) &&
                    text.length < 100) {
                    Log.d("HalfPriceBooksParser", "Found author using selector '$selector': $text")
                    return text.trim()
                }
            }
        }
        
        // Look for author near the title
        val titleElement = document.selectFirst("h1.product-name, .product-name")
        if (titleElement != null) {
            val nextElements = titleElement.nextElementSiblings()
            for (element in nextElements.take(3)) {
                val text = element.text()
                if (text.isNotBlank() &&
                    !text.contains("ISBN", ignoreCase = true) &&
                    !text.contains("$", ignoreCase = true) &&
                    !text.contains("price", ignoreCase = true) &&
                    text.length > 3 && text.length < 100) {
                    Log.d("HalfPriceBooksParser", "Found author after title: $text")
                    return text.trim()
                }
            }
        }
        
        return null
    }
    
    private fun extractIsbn10(document: Document): String? {
        // Extract all ISBNs and find 10-digit one
        val allIsbns = extractAllIsbns(document)
        
        for (isbn in allIsbns) {
            if (isbn.length == 10) {
                Log.d("HalfPriceBooksParser", "Found ISBN-10: $isbn")
                return isbn
            }
        }
        
        return null
    }
    
    private fun extractIsbn13(document: Document): String? {
        // Extract all ISBNs and find 13-digit one
        val allIsbns = extractAllIsbns(document)
        
        for (isbn in allIsbns) {
            if (isbn.length == 13) {
                Log.d("HalfPriceBooksParser", "Found ISBN-13: $isbn")
                return isbn
            }
        }
        
        return null
    }
    
    private fun extractAllIsbns(document: Document): List<String> {
        val isbns = mutableSetOf<String>()
        
        // Try JSON-LD structured data first
        val jsonLdIsbn = tryExtractFromJsonLd(document, "isbn")
        if (jsonLdIsbn != null) {
            isbns.add(jsonLdIsbn)
        }
        
        // Try CSS selectors for ISBN
        val isbnSelectors = listOf(
            ".product-id",
            "span:contains(ISBN)",
            "[class*=\"isbn\"]"
        )
        
        for (selector in isbnSelectors) {
            val elements = document.select(selector)
            for (element in elements) {
                val text = element.text()
                
                // Extract ISBN numbers from text
                val isbnRegex = "\\b(\\d{10}|\\d{13})\\b".toRegex()
                val matches = isbnRegex.findAll(text)
                for (match in matches) {
                    isbns.add(match.value)
                }
            }
        }
        
        // Look for ISBNs in variant data (HPB has multiple ISBNs for different editions)
        val scripts = document.select("script")
        for (script in scripts) {
            val content = script.html()
            if (content.contains("variant") || content.contains("ISBN")) {
                val isbnRegex = "\\b(\\d{10}|\\d{13})\\b".toRegex()
                val matches = isbnRegex.findAll(content)
                for (match in matches) {
                    isbns.add(match.value)
                }
            }
        }
        
        Log.d("HalfPriceBooksParser", "Found ISBNs: ${isbns.toList()}")
        return isbns.toList()
    }
    
    private fun extractPrice(document: Document): Double? {
        // Try JSON-LD structured data first
        val jsonLdPrice = tryExtractFromJsonLd(document, "price")
        if (jsonLdPrice != null) {
            val priceValue = jsonLdPrice.toDoubleOrNull()
            if (priceValue != null) {
                Log.d("HalfPriceBooksParser", "Found price from JSON-LD: $priceValue")
                return priceValue
            }
        }
        
        // Try CSS selectors for price
        val priceSelectors = listOf(
            ".prices.show",
            ".prices",
            "[class*=\"price\"]",
            ".price"
        )
        
        for (selector in priceSelectors) {
            val elements = document.select(selector)
            for (element in elements) {
                val text = element.text()
                if (text.isNotBlank()) {
                    // Extract price as number
                    val priceRegex = "\\$?([0-9]+(?:\\.[0-9]{2})?)".toRegex()
                    val match = priceRegex.find(text)
                    if (match != null) {
                        val priceValue = match.groupValues[1].toDoubleOrNull()
                        if (priceValue != null && priceValue > 0) {
                            Log.d("HalfPriceBooksParser", "Found price using selector '$selector': $priceValue")
                            return priceValue
                        }
                    }
                }
            }
        }
        
        return null
    }
    
    private fun extractCoverImage(document: Document): String? {
        val imageSelectors = listOf(
            "[itemprop=\"image\"]",
            "img[data-zoom-image]",
            ".product-image img",
            "img[alt*=\"image number\"]",
            "img[src*=\"hpb.com\"]"
        )
        
        for (selector in imageSelectors) {
            val element = document.selectFirst(selector)
            val imageUrl = element?.attr("src") 
                ?: element?.attr("data-src") 
                ?: element?.attr("data-zoom-image")
            
            if (!imageUrl.isNullOrBlank() && 
                !imageUrl.contains("placeholder", ignoreCase = true) &&
                !imageUrl.contains("icon", ignoreCase = true)) {
                
                // Prefer larger image sizes by replacing with higher resolution
                val largeImageUrl = imageUrl
                    .replace("/200.jpg", "/300.jpg")
                    .replace("/150.jpg", "/300.jpg")
                
                Log.d("HalfPriceBooksParser", "Found cover image using selector '$selector': $largeImageUrl")
                return largeImageUrl
            }
        }
        
        // Try JSON-LD structured data
        val jsonLdImage = tryExtractFromJsonLd(document, "image")
        if (jsonLdImage != null) {
            Log.d("HalfPriceBooksParser", "Found cover image from JSON-LD: $jsonLdImage")
            return jsonLdImage
        }
        
        return null
    }
    

    
    private fun tryExtractFromJsonLd(document: Document, key: String): String? {
        try {
            val scripts = document.select("script[type=\"application/ld+json\"]")
            for (script in scripts) {
                val jsonContent = script.html()
                if (jsonContent.isNotBlank()) {
                    // Handle both single objects and arrays
                    if (jsonContent.trim().startsWith("[")) {
                        val jsonArray = JSONArray(jsonContent)
                        for (i in 0 until jsonArray.length()) {
                            val jsonObject = jsonArray.getJSONObject(i)
                            val value = extractValueFromJsonObject(jsonObject, key)
                            if (value != null) return value
                        }
                    } else {
                        val jsonObject = JSONObject(jsonContent)
                        val value = extractValueFromJsonObject(jsonObject, key)
                        if (value != null) return value
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("HalfPriceBooksParser", "Error extracting from JSON-LD: ${e.message}")
        }
        return null
    }
    
    private fun extractValueFromJsonObject(jsonObject: JSONObject, key: String): String? {
        return when (key) {
            "author" -> {
                if (jsonObject.has("author")) {
                    when (val author = jsonObject.get("author")) {
                        is JSONObject -> author.optString("name")
                        is String -> author
                        else -> null
                    }
                } else null
            }
            "image" -> {
                if (jsonObject.has("image")) {
                    when (val image = jsonObject.get("image")) {
                        is JSONArray -> if (image.length() > 0) image.getString(0) else null
                        is String -> image
                        else -> null
                    }
                } else null
            }
            "price" -> {
                if (jsonObject.has("offers")) {
                    when (val offers = jsonObject.get("offers")) {
                        is JSONObject -> offers.optString("price")
                        is JSONArray -> if (offers.length() > 0) {
                            val firstOffer = offers.getJSONObject(0)
                            firstOffer.optString("price")
                        } else null
                        else -> null
                    }
                } else null
            }
            else -> jsonObject.optString(key).takeIf { it.isNotBlank() }
        }
    }
} 