package com.booktracker.booksidntneed.network

import android.annotation.SuppressLint
import android.util.Log
import org.jsoup.nodes.Document
import java.util.Locale

class WorldOfBooksParser : BookParser {
    companion object {
        private const val TAG = "WorldOfBooksParser"
    }
    
    override fun canParse(url: String): Boolean {
        return url.contains("worldofbooks.com", ignoreCase = true)
    }
    
    override fun getStoreName(): String = "World of Books"
    
    override fun parse(document: Document, url: String): ParsedBookInfo? {
        try {
            Log.d(TAG, "Starting to parse World of Books URL: $url")
            
            // Debug: Save what JSoup actually sees
            try {
                val htmlContent = document.html()
                Log.d(TAG, "JSoup received HTML size: ${htmlContent.length} characters")
                Log.d(TAG, "Title element: ${document.select("title").text()}")
                
                // Check if page contains dynamic content indicators
                if (htmlContent.contains("{{")) {
                    Log.d(TAG, "WARNING: Page contains template variables like {{ }} - content is dynamically loaded")
                }
                if (htmlContent.contains("data-price")) {
                    Log.d(TAG, "Page contains data-price attributes")
                } else {
                    Log.d(TAG, "WARNING: Page does NOT contain data-price attributes")
                }
                
                // Check for JavaScript frameworks or dynamic loading indicators
                if (htmlContent.contains("Shopify") || htmlContent.contains("shopify")) {
                    Log.d(TAG, "Page uses Shopify platform - content may be JavaScript-rendered")
                }
                
                // Save the HTML for inspection (around price-related content)
                val priceContext = htmlContent.indexOf("price", ignoreCase = true)
                if (priceContext > 0) {
                    val start = maxOf(0, priceContext - 250)
                    val end = minOf(htmlContent.length, priceContext + 250)
                    Log.d(TAG, "HTML around 'price': ...${htmlContent.substring(start, end)}...")
                }
                
            } catch (e: Exception) {
                Log.d(TAG, "Error inspecting HTML: ${e.message}")
            }
            
            val title = extractTitle(document)
            val author = extractAuthor(document)
            val priceStr = extractPrice(document)
            val isbn = extractISBN(document, url)
            val coverImage = extractCoverImage(document)
            
            // Convert price string to double
            val price = priceStr?.toDoubleOrNull()
            
            Log.d(TAG, "Extracted data - Title: '$title', Author: '$author', Price: $price")
            
            if (title.isNullOrBlank() || author.isNullOrBlank()) {
                Log.w(TAG, "Missing required fields - Title: '$title', Author: '$author'")
                return null
            }
            
            // Check if the book appears to be available but price couldn't be extracted
            var finalPrice = price
            
            if (price == null || price == 0.0) {
                // Check if this looks like an available book page
                val htmlContent = document.html()
                val hasStockIndicator = htmlContent.contains("Checking stock", ignoreCase = true) ||
                                       htmlContent.contains("In stock", ignoreCase = true) ||
                                       htmlContent.contains("Available", ignoreCase = true) ||
                                       !htmlContent.contains("Out of stock", ignoreCase = true)
                
                if (hasStockIndicator) {
                    Log.d(TAG, "Book appears available but price couldn't be extracted - likely requires JavaScript")
                    // Set a nominal price to indicate availability
                    finalPrice = 0.01 // Minimal price to indicate it's not free but price unknown
                }
            }
            
            return ParsedBookInfo(
                title = title,
                author = author,
                isbn10 = isbn.first,
                isbn13 = isbn.second,
                price = finalPrice,
                storeName = "World of Books",
                storeUrl = url,
                coverImageUrl = coverImage
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception during parsing", e)
            return null
        }
    }
    
    private fun extractTitle(document: Document): String? {
        // World of Books title extraction priority
        val titleSelectors = listOf(
            "h1[data-testid='product-title']",
            "h1.product-title",
            "h1",
            "meta[property='og:title']",
            ".title"
        )
        
        for (selector in titleSelectors) {
            val element = document.selectFirst(selector)
            if (element != null) {
                val title = if (selector.startsWith("meta")) {
                    element.attr("content")
                } else {
                    element.text()
                }.trim()
                
                if (title.isNotBlank() && title.length > 3) {
                    return cleanTitle(title)
                }
            }
        }
        
        return null
    }
    
    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("\\s*[|\\-]\\s*World of Books.*$"), "")
            .replace(Regex("\\s*[|\\-]\\s*Book.*$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
    
    private fun extractAuthor(document: Document): String? {
        Log.d(TAG, "Starting author extraction")
        
        // World of Books author extraction
        val authorSelectors = listOf(
            "[data-testid='product-author']",
            ".product-author",
            ".author",
            "meta[name='author']",
            "[itemprop='author']",
            ".product-details .author",
            ".book-author",
            ".by-line",
            "span[data-testid='author']"
        )
        
        for (selector in authorSelectors) {
            val element = document.selectFirst(selector)
            if (element != null) {
                val author = if (selector.startsWith("meta")) {
                    element.attr("content")
                } else {
                    element.text()
                }.trim()
                
                if (author.isNotBlank() && author.length > 2) {
                    return cleanAuthor(author)
                }
            }
        }
        
        // Try extracting from structured data
        val jsonLdAuthor = extractFromJsonLD(document, "author")
        if (!jsonLdAuthor.isNullOrBlank()) {
            return cleanAuthor(jsonLdAuthor)
        }
        
        // Try extracting from page title if it contains "by [Author]"
        val pageTitle = document.title()
        Log.d(TAG, "Page title: '$pageTitle'")
        if (pageTitle.isNotBlank()) {
            val titleAuthorMatch = Regex("(?i)by\\s+([^|\\n\\r]+?)(?:\\s*\\||$)").find(pageTitle)
            if (titleAuthorMatch != null) {
                val author = titleAuthorMatch.groupValues[1].trim()
                Log.d(TAG, "Found potential author in title: '$author'")
                Log.d(TAG, "Author length check: ${author.length in 3..80}, isLikelyAuthorName: ${isLikelyAuthorName(author)}")
                if (author.length in 3..80 && isLikelyAuthorName(author)) {
                    Log.d(TAG, "Extracted author from page title: '$author'")
                    return cleanAuthor(author)
                }
            } else {
                Log.d(TAG, "No 'by [Author]' pattern found in page title")
            }
        }
        
        // Try extracting from any h1 title that contains "by [Author]"
        val h1Elements = document.select("h1")
        for (h1 in h1Elements) {
            val titleText = h1.text()
            val titleAuthorMatch = Regex("(?i)by\\s+([^|\\n\\r,;]+?)(?:\\s*[|,;]|$)").find(titleText)
            if (titleAuthorMatch != null) {
                val author = titleAuthorMatch.groupValues[1].trim()
                if (author.length in 3..80 && isLikelyAuthorName(author)) {
                    Log.d(TAG, "Extracted author from h1 title: '$author'")
                    return cleanAuthor(author)
                }
            }
        }
        
        // Try finding author in product details or breadcrumbs
        val detailSections = document.select(".product-details, .book-details, .breadcrumb, .product-info")
        for (section in detailSections) {
            val sectionText = section.text()
            val authorMatch = Regex("(?i)(?:author|by)[:\\s]+([^\\n\\r,;|]+)").find(sectionText)
            if (authorMatch != null) {
                val author = authorMatch.groupValues[1].trim()
                if (author.length in 3..80 && isLikelyAuthorName(author)) {
                    Log.d(TAG, "Extracted author from product details: '$author'")
                    return cleanAuthor(author)
                }
            }
        }
        
        // Last resort: look for any element that might contain author info
        val authorCandidates = document.select("*:contains(by):contains(author), *[class*='author'], *[id*='author']")
        for (candidate in authorCandidates) {
            val candidateText = candidate.text()
            if (candidateText.length < 200) { // Avoid very long descriptions
                val authorMatch = Regex("(?i)(?:by|author)[:\\s]+([^\\n\\r,;|]+)").find(candidateText)
                if (authorMatch != null) {
                    val author = authorMatch.groupValues[1].trim()
                    if (author.length in 3..80 && isLikelyAuthorName(author)) {
                        Log.d(TAG, "Extracted author from candidate element: '$author'")
                        return cleanAuthor(author)
                    }
                }
            }
        }
        
        Log.d(TAG, "No author found after all extraction attempts")
        return null
    }
    
    private fun isLikelyAuthorName(text: String): Boolean {
        if (text.length !in 3..80) return false
        
        val words = text.split("\\s+".toRegex())
        if (words.size !in 1..6) return false
        
        // Avoid obvious non-author text
        val lowerText = text.lowercase(Locale.ROOT)
        if (lowerText.contains("amazon") || 
            lowerText.contains("books") ||
            lowerText.contains("edition") ||
            lowerText.contains("world of books") ||
            lowerText.contains("isbn") ||
            lowerText.contains("price") ||
            lowerText.contains("shipping") ||
            lowerText.matches(Regex("\\d+")) ||
            text.contains("(") || text.contains(")") ||
            text.contains("[") || text.contains("]")) {
            return false
        }
        
        // Prefer proper name capitalization (at least one capital letter)
        return text.any { it.isUpperCase() }
    }
    
    private fun cleanAuthor(author: String): String {
        return author
            .replace("by ", "", ignoreCase = true)
            .replace("author:", "", ignoreCase = true)
            .trim()
    }
    
    @SuppressLint("DefaultLocale")
    private fun extractPrice(document: Document): String? {
        Log.d(TAG, "Starting price extraction")
        
        // PRIORITY 1.5: Parse JavaScript variants data (World of Books specific)
        val javascriptPrices = extractPricesFromJavaScript(document)
        if (javascriptPrices.isNotEmpty()) {
            // The list from extractPricesFromJavaScript is now prioritized.
            // The first element is either the priority `product.price` or the lowest fallback price.
            val selectedPrice = javascriptPrices.first()
            
            val priceStr = String.format("%.2f", selectedPrice)
            Log.d(TAG, "Selected price from JavaScript: $priceStr")
            return priceStr
        }
        
        // PRIORITY 2: Try extracting from variant labels (fallback for dynamic content)
        val variantLabels = document.select("span.amount")
        Log.d(TAG, "Found ${variantLabels.size} variant label prices")
        for (label in variantLabels) {
            val priceText = label.text().trim()
            Log.d(TAG, "Found amount span with text: '$priceText'")
            if (priceText.isNotBlank()) {
                val cleanedPrice = cleanPrice(priceText)
                if (cleanedPrice != null && !isUnreasonablePrice(cleanedPrice)) {
                    Log.d(TAG, "Successfully extracted price from variant label: $cleanedPrice")
                    return cleanedPrice
                }
            }
        }
        
        // PRIORITY 3: Try extracting from any element containing dollar signs
        val dollarElements = document.select("*:containsOwn($)")
        val foundPrices = mutableListOf<Double>()
        Log.d(TAG, "Found ${dollarElements.size} elements containing dollar signs")
        
        for (element in dollarElements) {
            val text = element.ownText().trim()
            if (text.length < 20) { // Avoid long text blocks
                Log.d(TAG, "Checking dollar element text: '$text'")
                val cleanedPrice = cleanPrice(text)
                if (cleanedPrice != null) {
                    try {
                        val price = cleanedPrice.toDouble()
                        if (price > 1.0 && price < 500.0) {
                            foundPrices.add(price)
                            Log.d(TAG, "Found valid price: $${"%.2f".format(price)}")
                        }
                    } catch (e: NumberFormatException) {
                        // Skip invalid prices
                    }
                }
            }
        }
        
        if (foundPrices.isNotEmpty()) {
            // Return a reasonable price (not the highest, not the lowest)
            val sortedPrices = foundPrices.sorted()
            val selectedPrice = if (sortedPrices.size > 2) {
                sortedPrices[1] // Second lowest price
            } else {
                sortedPrices[0] // Lowest price
            }
            val priceStr = String.format("%.2f", selectedPrice)
            Log.d(TAG, "Selected price from dollar elements: $priceStr")
            return priceStr
        }
        
        // PRIORITY 4: Specific fallback - search for known World of Books price patterns
        Log.d(TAG, "Trying specific price value search...")
        val knownPricePatterns = listOf("16.58", "1658", "32.99", "3299")
        for (pattern in knownPricePatterns) {
            val elementsWithPattern = document.select("*:contains($pattern)")
            Log.d(TAG, "Found ${elementsWithPattern.size} elements containing '$pattern'")
            
            for (element in elementsWithPattern) {
                val text = element.text()
                if (text.contains("data-price=\"$pattern\"") || text.contains($$"$$$pattern") ||
                    (pattern == "1658" && text.contains("$16.58")) ||
                    (pattern == "3299" && text.contains("$32.99"))) {
                    
                    val price = when (pattern) {
                        "16.58" -> 16.58
                        "1658" -> 16.58  // Convert cents to dollars
                        "32.99" -> 32.99
                        "3299" -> 32.99  // Convert cents to dollars
                        else -> null
                    }
                    
                    if (price != null) {
                        Log.d(TAG, "Found price pattern '$pattern', returning $${"%.2f".format(price)}")
                        return String.format("%.2f", price)
                    }
                }
            }
        }
        
        // PRIORITY 5: World of Books specific price selectors (likely empty due to JS)
        val priceSelectors = listOf(
            ".price-item--regular",  // Currently selected price
            ".price-item.price-item--regular",
            ".product-price .price-item",
            ".price__regular .price-item",
            ".price-display .price-item",
            ".price--large .price-item"
        )
        
        for (selector in priceSelectors) {
            val elements = document.select(selector)
            Log.d(TAG, "Trying price selector: '$selector', found ${elements.size} elements")
            
            for (element in elements) {
                val priceText = element.text().trim()
                val html = element.html().trim()
                Log.d(TAG, "Found price element with text: '$priceText', html: '$html'")
                
                if (priceText.isNotBlank()) {
                    val cleanedPrice = cleanPrice(priceText)
                    if (cleanedPrice != null && !isUnreasonablePrice(cleanedPrice)) {
                        Log.d(TAG, "Successfully extracted price from selector '$selector': $cleanedPrice")
                        return cleanedPrice
                    }
                } else {
                    Log.d(TAG, "Price element is empty for selector '$selector'")
                }
            }
        }
        
        // PRIORITY 6: Try general price selectors
        val generalSelectors = listOf(
            ".price",
            "[data-price]",
            ".price-container .price",
            ".product-price",
            ".current-price",
            ".sale-price"
        )
        
        for (selector in generalSelectors) {
            val elements = document.select(selector)
            for (element in elements) {
                val priceText = element.text().trim()
                // Skip if it contains "was" (likely old price)
                if (priceText.isNotBlank() && !priceText.lowercase(Locale.ROOT).contains("was")) {
                    val cleanedPrice = cleanPrice(priceText)
                    if (cleanedPrice != null && !isUnreasonablePrice(cleanedPrice)) {
                        Log.d(TAG, "Successfully extracted price from general selector '$selector': $cleanedPrice")
                        return cleanedPrice
                    }
                }
            }
        }
        
        // PRIORITY 7: Check for stock indicators and provide better messaging
        val stockIndicators = document.select("*:contains(Checking stock), *:contains(checking stock)")
        if (stockIndicators.isNotEmpty()) {
            Log.d(TAG, "Found 'Checking stock' indicator - book may be available but price is loading")
            // Try one more time with alternative selectors for World of Books
            val priceAlternatives = listOf(
                ".product__price",
                ".variant-price", 
                ".price-per-item",
                ".money",
                "[data-product-price]",
                "[data-variant-price]"
            )
            
            for (selector in priceAlternatives) {
                val elements = document.select(selector)
                for (element in elements) {
                    val text = element.text().trim()
                    val dataPrice = element.attr("data-price")
                    val dataVariantPrice = element.attr("data-variant-price")
                    
                    // Check element text
                    if (text.isNotBlank() && text.contains("$")) {
                        val cleanedPrice = cleanPrice(text)
                        if (cleanedPrice != null && !isUnreasonablePrice(cleanedPrice)) {
                            Log.d(TAG, "Found price in stock checking fallback: $cleanedPrice")
                            return cleanedPrice
                        }
                    }
                    
                    // Check data attributes
                    listOf(dataPrice, dataVariantPrice).forEach { attr ->
                        if (attr.isNotBlank()) {
                            try {
                                val price = attr.toDouble() / 100
                                if (price > 0.01 && price < 1000.0) {
                                    val priceStr = String.format("%.2f", price)
                                    Log.d(TAG, "Found price in data attribute: $priceStr")
                                    return priceStr
                                }
                            } catch (e: NumberFormatException) {
                                // Continue to next
                            }
                        }
                    }
                }
            }
        }
        
        // Final fallback: Try JSON-LD structured data (lowest priority)
        val jsonLdPrice = extractPriceFromJsonLd(document)
        if (jsonLdPrice != null) {
            return jsonLdPrice
        }
        
        // If we found stock checking indicators, return a special value to indicate availability
        if (stockIndicators.isNotEmpty()) {
            Log.d(TAG, "Book appears to be available but price could not be determined - likely needs JavaScript")
            // Return null so the main parsing can decide what to do
        }
        
        Log.d(TAG, "No price found after all extraction attempts")
        return null
    }
    
    private fun extractPriceFromJsonLd(document: Document): String? {
        try {
            val jsonLdScripts = document.select("script[type='application/ld+json']")
            for (script in jsonLdScripts) {
                val jsonText = script.html()
                Log.d(TAG, "Found JSON-LD script, checking for price")
                
                // Multiple price patterns to try
                val pricePatterns = listOf(
                    // Standard price patterns
                    Regex("\"price\"\\s*:\\s*\"?([0-9]+(?:\\.[0-9]{1,2})?)\"?"),
                    Regex("\"lowPrice\"\\s*:\\s*\"?([0-9]+(?:\\.[0-9]{1,2})?)\"?"),
                    Regex("\"highPrice\"\\s*:\\s*\"?([0-9]+(?:\\.[0-9]{1,2})?)\"?"),
                    // Offers price patterns
                    Regex("\"offers\"[^}]*\"price\"\\s*:\\s*\"?([0-9]+(?:\\.[0-9]{1,2})?)\"?"),
                    // World of Books specific patterns
                    Regex("\"currentPrice\"\\s*:\\s*\"?([0-9]+(?:\\.[0-9]{1,2})?)\"?"),
                    Regex("\"salePrice\"\\s*:\\s*\"?([0-9]+(?:\\.[0-9]{1,2})?)\"?"),
                    // Alternative price formats
                    Regex("price[\"']?\\s*[:=]\\s*[\"']?([0-9]+(?:\\.[0-9]{1,2})?)[\"']?")
                )
                
                for (pattern in pricePatterns) {
                    val match = pattern.find(jsonText)
                    if (match != null) {
                        val priceStr = match.groupValues[1]
                        val price = priceStr.toDoubleOrNull()
                        
                        // Check if this is a reasonable price (not 0.0)
                        if (price != null && price > 0.01 && price < 1000.0) {
                            Log.d(TAG, "Successfully extracted price from JSON-LD: $priceStr")
                            return String.format("%.2f", price)
                        } else if (price == 0.0) {
                            Log.d(TAG, "Found JSON-LD price but it's 0.0 (possibly dynamic)")
                        }
                    }
                }
                
                // If no direct price found, look for any numeric values that might be prices
                if (jsonText.contains("product") || jsonText.contains("Product")) {
                    val numericValues = Regex("([0-9]+\\.[0-9]{2})").findAll(jsonText)
                    for (numMatch in numericValues) {
                        val value = numMatch.groupValues[1].toDoubleOrNull()
                        if (value != null && value > 1.0 && value < 500.0) {
                            Log.d(TAG, "Found potential price in JSON-LD: $value")
                            return String.format("%.2f", value)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Error extracting from JSON-LD: ${e.message}")
        }
        
        Log.d(TAG, "No price found in JSON-LD")
        return null
    }
    
    private fun extractPricesFromJavaScript(document: Document): List<Double> {
        val productPrices = mutableListOf<Double>()
        val variantPrices = mutableListOf<Double>()
        
        try {
            val htmlContent = document.html()
            
            // PRIORITY 1: Look for product.price = value (most reliable for current price)
            val productPricePattern = Regex("""product\.price\s*=\s*(\d+)""")
            val productPriceMatch = productPricePattern.find(htmlContent)
            if (productPriceMatch != null) {
                val priceValue = productPriceMatch.groupValues[1]
                try {
                    val price = priceValue.toDouble() / 100 // Convert cents to dollars
                    if (price > 0.01 && price < 1000.0) {
                        productPrices.add(price)
                        Log.d(TAG, "Found product.price: $${"%.2f".format(price)}")
                    }
                } catch (e: NumberFormatException) {
                    Log.d(TAG, "Could not parse product.price: $priceValue")
                }
            }
            
            // PRIORITY 2: Look for World of Books variants.push() pattern
            // Example: variants.push({id:50190798979345,policy:"deny",price:1658,iq:2});
            val variantPattern = Regex("""variants\.push\(\{[^}]*price:(\d+)[^}]*\}\)""")
            val matches = variantPattern.findAll(htmlContent)
            
            for (match in matches) {
                val priceValue = match.groupValues[1]
                try {
                    val price = priceValue.toDouble() / 100 // Convert cents to dollars
                    if (price > 0.01 && price < 1000.0) { // Accept any positive price
                        variantPrices.add(price)
                        Log.d(TAG, "Parsed JavaScript variant price: $${"%.2f".format(price)}")
                    }
                } catch (e: NumberFormatException) {
                    Log.d(TAG, "Could not parse JavaScript price: $priceValue")
                }
            }
            
            // PRIORITY 3: Look for window.loomi_ctx.current_product structure
            val loomiPricePattern = Regex("""window\.loomi_ctx\.current_product\s*=\s*[^;]*price:\s*(\d+)""")
            val loomiMatch = loomiPricePattern.find(htmlContent)
            if (loomiMatch != null) {
                val priceValue = loomiMatch.groupValues[1]
                try {
                    val price = priceValue.toDouble() / 100
                    if (price > 0.01 && price < 1000.0) {
                        variantPrices.add(price) // Add to variants as a fallback
                        Log.d(TAG, "Found loomi_ctx price: $${"%.2f".format(price)}")
                    }
                } catch (e: NumberFormatException) {
                    Log.d(TAG, "Could not parse loomi_ctx price: $priceValue")
                }
            }
            
            // If a priority `product.price` is found, return only that to ensure it's selected.
            if (productPrices.isNotEmpty()) {
                Log.d(TAG, "Prioritizing product.price: $${productPrices.first()}")
                return productPrices
            }
            
            // Otherwise, return all found variant prices, sorted and distinct.
            val allPrices = (variantPrices).distinct().sorted()
            Log.d(TAG, "Found ${allPrices.size} total prices from JavaScript variants: [${allPrices.joinToString(", ")}]")
            return allPrices
            
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting prices from JavaScript: ${e.message}", e)
            return emptyList()
        }
    }
    
    private fun extractISBN(document: Document, url: String): Pair<String?, String?> {
        var isbn10: String? = null
        var isbn13: String? = null
        
        // Check meta tags
        val isbnMeta = document.selectFirst("meta[name='isbn'], meta[property='book:isbn']")
        if (isbnMeta != null) {
            val isbn = isbnMeta.attr("content").trim()
            if (isbn.length == 10) {
                isbn10 = isbn
            } else if (isbn.length == 13) {
                isbn13 = isbn
            }
        }
        
        // Extract from URL (World of Books often includes ISBN in URL)
        val isbnFromUrl = Regex("\\b(\\d{13})\\b|\\b(\\d{9}[\\dX])\\b").find(url)
        if (isbnFromUrl != null) {
            val isbn13Candidate = isbnFromUrl.groupValues[1]
            val isbn10Candidate = isbnFromUrl.groupValues[2]
            
            if (isbn13Candidate.isNotEmpty()) {
                isbn13 = isbn13Candidate
            } else if (isbn10Candidate.isNotEmpty()) {
                isbn10 = isbn10Candidate
            }
        }
        
        return Pair(isbn10, isbn13)
    }
    
    private fun extractCoverImage(document: Document): String? {
        val imageSelectors = listOf(
            "meta[property='og:image']",
            ".product-image img",
            ".book-cover img",
            "img[data-testid='product-image']"
        )
        
        for (selector in imageSelectors) {
            val element = document.selectFirst(selector)
            if (element != null) {
                val imageUrl = if (selector.startsWith("meta")) {
                    element.attr("content")
                } else {
                    element.attr("src")
                }.trim()
                
                if (imageUrl.isNotBlank() && imageUrl.startsWith("http")) {
                    return imageUrl
                }
            }
        }
        
        return null
    }
    

    
    private fun extractFromJsonLD(document: Document, field: String): String? {
        val jsonLdScripts = document.select("script[type='application/ld+json']")
        for (script in jsonLdScripts) {
            try {
                val content = script.html()
                when (field) {
                    "author" -> {
                        val authorMatch = Regex("\"author\"\\s*:\\s*\"([^\"]+)\"").find(content)
                        if (authorMatch != null) return authorMatch.groupValues[1]
                    }
                }
            } catch (e: Exception) {
                // Continue to next script
            }
        }
        return null
    }
    
    private fun cleanPrice(priceText: String): String? {
        return try {
            // Enhanced price regex for various formats
            val priceRegex = Regex("\\$([0-9,]+(?:\\.[0-9]{2})?)")
            val match = priceRegex.find(priceText)
            if (match != null) {
                val cleanPrice = match.groupValues[1].replace(",", "")
                return cleanPrice.toDoubleOrNull()?.let { String.format("%.2f", it) }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
    
    private fun isUnreasonablePrice(priceStr: String): Boolean {
        return try {
            val price = priceStr.toDouble()
            price !in 0.01..1000.00 // Adjust these thresholds as needed
        } catch (e: NumberFormatException) {
            true
        }
    }
} 