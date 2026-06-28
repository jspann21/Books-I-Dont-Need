package com.booktracker.booksidntneed.network

import org.jsoup.nodes.Document

class GoogleBooksParser : BookParser {
    
    override fun canParse(url: String): Boolean {
        return url.contains("books.google.", ignoreCase = true) ||
               url.contains("play.google.com/books", ignoreCase = true)
    }
    
    override fun getStoreName(): String = "Google Books"
    
    override fun parse(document: Document, url: String): ParsedBookInfo? {
        try {
            val title = extractTitle(document)
            val author = extractAuthor(document)
            val price = extractPrice(document)
            val isbn = extractISBN(document)
            val coverImage = extractCoverImage(document)
            
            if (title.isNullOrBlank() || author.isNullOrBlank()) {
                return null
            }
            
            return ParsedBookInfo(
                title = title,
                author = author,
                isbn10 = isbn.first,
                isbn13 = isbn.second,
                price = price,
                storeName = getStoreName(),
                storeUrl = url,
                coverImageUrl = coverImage
            )
        } catch (_: Exception) {
            return null
        }
    }
    
    private fun extractTitle(document: Document): String? {
        return document.select(".bookinfo h1").first()?.text()?.trim()
            ?: document.select("h1[itemprop='name']").first()?.text()?.trim()
            ?: document.select(".gb-volume-title").first()?.text()?.trim()
    }
    
    private fun extractAuthor(document: Document): String? {
        val authorSelectors = listOf(
            ".bookinfo .addmd a",
            "[itemprop='author']",
            ".gb-volume-authors a",
            ".metadata_value a"
        )
        
        for (selector in authorSelectors) {
            val author = document.select(selector).first()?.text()?.trim()
            if (!author.isNullOrBlank()) {
                return author
            }
        }
        
        return null
    }
    
    private fun extractPrice(document: Document): Double? {
        val priceSelectors = listOf(
            ".gb-price",
            ".price-info .price",
            ".buy-button .price"
        )
        
        for (selector in priceSelectors) {
            val priceText = document.select(selector).first()?.text()
            if (!priceText.isNullOrBlank()) {
                val price = extractPriceFromText(priceText)
                if (price != null) return price
            }
        }
        
        return null
    }
    
    private fun extractPriceFromText(priceText: String): Double? {
        return try {
            val cleanPrice = priceText.replace("$", "")
                .replace(",", "")
                .replace("USD", "")
                .replace("Free", "0")
                .trim()
            cleanPrice.toDoubleOrNull()
        } catch (_: Exception) {
            null
        }
    }
    
    private fun extractISBN(document: Document): Pair<String?, String?> {
        var isbn10: String? = null
        var isbn13: String? = null
        
        // Look for ISBN in metadata
        val metadataRows = document.select(".metadata_item, .bibliographic_info")
        
        for (row in metadataRows) {
            val text = row.text()
            if (text.contains("ISBN", ignoreCase = true)) {
                val extractedISBN = extractISBNFromText(text)
                if (extractedISBN != null) {
                    if (extractedISBN.length == 10) {
                        isbn10 = extractedISBN
                    } else if (extractedISBN.length == 13) {
                        isbn13 = extractedISBN
                    }
                }
            }
        }
        
        return Pair(isbn10, isbn13)
    }
    
    private fun extractISBNFromText(text: String): String? {
        val isbnRegex = Regex("\\b\\d{9}[\\dX]\\b|\\b\\d{13}\\b")
        return isbnRegex.find(text)?.value
    }
    
    private fun extractCoverImage(document: Document): String? {
        val imageSelectors = listOf(
            ".cover img",
            ".gb-book-cover img",
            ".volumeinfo img"
        )
        
        for (selector in imageSelectors) {
            val imageUrl = document.select(selector).first()?.attr("src")
            if (!imageUrl.isNullOrBlank() && imageUrl.startsWith("http")) {
                return imageUrl
            }
        }
        
        return null
    }
    
} 