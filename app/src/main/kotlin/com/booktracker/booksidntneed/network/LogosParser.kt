package com.booktracker.booksidntneed.network

import android.util.Log
import org.jsoup.nodes.Document

class LogosParser : BookParser {
    companion object {
        private const val TAG = "LogosParser"
    }
    
    override fun canParse(url: String): Boolean {
        return url.contains("logos.com/product/", ignoreCase = true)
    }
    
    override fun getStoreName(): String = "Logos Bible Software"
    
    override fun parse(document: Document, url: String): ParsedBookInfo? {
        Log.d(TAG, "Starting to parse URL: $url")
        
        try {
            // Extract title from meta tag or h1
            val title = extractTitle(document)
            
            // Extract authors properly from the author section
            val author = extractAuthors(document)
            
            // Extract sale price and regular price
            val price = extractPrice(document)
            

            
            // Extract image URL from og:image meta tag
            val imageUrl = extractImageUrl(document)
            
            Log.d(TAG, "Extracted data - Title: '$title', Author: '$author', Price: '$price', Image: '$imageUrl'")
            
            return ParsedBookInfo(
                title = title,
                author = author,
                isbn10 = null,
                isbn13 = null,
                price = price?.replace("$", "")?.replace(",", "")?.toDoubleOrNull(),
                storeName = "Logos Bible Software",
                storeUrl = url,
                coverImageUrl = imageUrl
            ).also {
                Log.d(TAG, "Successfully created ParsedBookInfo")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Logos page", e)
            return null
        }
    }
    
    private fun extractTitle(document: Document): String {
        // Try og:title meta tag first
        val ogTitle = document.selectFirst("meta[property=og:title]")?.attr("content")
        if (!ogTitle.isNullOrBlank()) {
            return ogTitle.trim()
        }
        
        // Try title tag
        val titleTag = document.selectFirst("title")?.text()
        if (!titleTag.isNullOrBlank()) {
            return titleTag.replace(" | Logos Bible Software", "").trim()
        }
        
        // Try h1 with product title data attribute
        val h1Title = document.selectFirst("h1[data-testid=product-title]")?.text()
        if (!h1Title.isNullOrBlank()) {
            return h1Title.trim()
        }
        
        return "Unknown Title"
    }
    
    private fun extractAuthors(document: Document): String {
        // Look for the specific author section in the page structure
        val authorContainer = document.selectFirst("div.index--authors--SVlym")
        if (authorContainer != null) {
            val authorElements = mutableListOf<String>()
            
            // Extract the primary author link
            val primaryAuthor = authorContainer.selectFirst("a[href*='/authors/']")?.text()
            if (!primaryAuthor.isNullOrBlank()) {
                authorElements.add(primaryAuthor.trim())
            }
            
            if (authorElements.isNotEmpty()) {
                return authorElements.joinToString("; ")
            }
        }
        
        // Fallback: try to extract from product details section
        val productDetails = document.selectFirst("div.product-details")
        if (productDetails != null) {
            val editorLine = productDetails.select("li").find { 
                it.text().startsWith("Editors:", ignoreCase = true) 
            }?.text()
            
            if (!editorLine.isNullOrBlank()) {
                return editorLine.replace("Editors:", "").trim()
            }
        }
        
        return "Unknown Author"
    }
    
    private fun extractPrice(document: Document): String? {
        // Try to find the sale price first
        val salePriceElements = document.select("span.index--displayPriceLarge--sMu2k, h3.index--price--lAlmq")
        for (element in salePriceElements) {
            val parent = element.parent()
            if (parent != null) {
                val fullPriceText = parent.text()
                val priceMatch = Regex("\\$([0-9,]+(?:\\.[0-9]{2})?)").find(fullPriceText)
                if (priceMatch != null) {
                    return "$${priceMatch.groupValues[1]}"
                }
            }
        }
        
        // Try structured data
        val scriptTags = document.select("script[type=application/ld+json]")
        for (script in scriptTags) {
            val jsonText = script.html()
            if (jsonText.contains("\"price\"") || jsonText.contains("\"@type\":\"Product\"")) {
                val priceMatch = Regex("\"price\"\\s*:\\s*\"?([0-9.]+)\"?").find(jsonText)
                if (priceMatch != null) {
                    return "$${priceMatch.groupValues[1]}"
                }
            }
        }
        
        // Try dataLayer for ecommerce data
        val dataLayerScripts = document.select("script").filter { 
            it.html().contains("dataLayer.push") && it.html().contains("\"value\"")
        }
        for (script in dataLayerScripts) {
            val priceMatch = Regex("\"value\"\\s*:\\s*([0-9.]+)").find(script.html())
            if (priceMatch != null) {
                return "$${priceMatch.groupValues[1]}"
            }
        }
        
        return null
    }
    

    
    private fun extractImageUrl(document: Document): String? {
        // Try og:image meta tag first (most reliable)
        val ogImage = document.selectFirst("meta[property=og:image]")?.attr("content")
        if (!ogImage.isNullOrBlank()) {
            return ogImage.trim()
        }
        
        // Try product image with srcset
        val productImage = document.selectFirst("img.index--productImage--V0jSW")
        if (productImage != null) {
            val srcset = productImage.attr("srcset")
            if (srcset.isNotBlank()) {
                // Extract the largest image URL from srcset
                val srcsetPattern = Regex("(https://\\S+)\\s+\\d+w")
                val matches = srcsetPattern.findAll(srcset)
                val urls = matches.map { it.groupValues[1] }.toList()
                if (urls.isNotEmpty()) {
                    return urls.last() // Return the largest image
                }
            }
        }
        
        return null
    }
} 
