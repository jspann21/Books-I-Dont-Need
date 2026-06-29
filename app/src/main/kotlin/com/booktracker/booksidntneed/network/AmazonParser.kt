package com.booktracker.booksidntneed.network

import android.util.Log
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Locale

class AmazonParser : BookParser {
    
    override fun canParse(url: String): Boolean {
        return url.contains("amazon.com", ignoreCase = true) ||
               url.contains("amazon.ca", ignoreCase = true) ||
               url.contains("amazon.co.uk", ignoreCase = true) ||
               url.contains("amazon.de", ignoreCase = true) ||
               url.contains("amazon.fr", ignoreCase = true) ||
               url.contains("amazon.it", ignoreCase = true) ||
               url.contains("amazon.es", ignoreCase = true) ||
               url.contains("amazon.co.jp", ignoreCase = true) ||
               url.contains("a.co/d/", ignoreCase = true)  // Amazon shortened URLs
    }
    
    override fun getStoreName(): String = "Amazon"
    
    override fun parse(document: Document, url: String): ParsedBookInfo? {
        try {
            Log.d("BookTracker", "AmazonParser: Starting to parse URL: $url")
            
            // Always try normal extraction first, regardless of page type
            val title = extractTitle(document)
            val author = extractAuthor(document)
            val price = extractPrice(document)
            val isbn = extractISBN(document)
            val coverImage = extractCoverImage(document)
            val canonicalStoreUrl = AmazonRequestStrategy().canonicalizeUrl(url)
            
            Log.d("BookTracker", "AmazonParser: Extracted data - Title: '$title', Author: '$author', Price: $price, Cover: '$coverImage', ISBN-10: '${isbn.first}', ISBN-13: '${isbn.second}'")
            
            // Check if we got basic info from normal extraction
            if (!title.isNullOrBlank() && !author.isNullOrBlank()) {
                // Normal extraction worked - use it
                val bookInfo = ParsedBookInfo(
                    title = title,
                    author = author,
                    isbn10 = isbn.first,
                    isbn13 = isbn.second,
                    price = price,
                    storeName = getStoreName(),
                    storeUrl = canonicalStoreUrl,
                    coverImageUrl = coverImage
                )
                
                Log.d("BookTracker", "AmazonParser: Successfully created ParsedBookInfo from normal extraction")
                return bookInfo
            }
            
            // Normal extraction failed - check if this might be an intermediate page
            if (isIntermediatePage(document)) {
                Log.w("BookTracker", "AmazonParser: Detected intermediate page, trying page title fallback")
                
                // Try page title fallback
                val pageTitleInfo = extractFromPageTitle(document)
                if (pageTitleInfo != null) {
                    Log.d("BookTracker", "AmazonParser: Page title fallback successful")
                    
                    // Try to enhance page title info with any extracted data we did get
                    val enhancedInfo = pageTitleInfo.copy(
                        storeUrl = canonicalStoreUrl,
                        storeName = getStoreName(),
                        price = price, // Use extracted price if available
                        coverImageUrl = coverImage ?: pageTitleInfo.coverImageUrl // Use extracted cover if available
                    )
                    
                    return enhancedInfo
                }
                
                Log.w("BookTracker", "AmazonParser: Page title fallback also failed, skipping parse")
                return null
            }
            
            Log.w("BookTracker", "AmazonParser: Missing required fields and not an intermediate page")
            return null
        } catch (e: Exception) {
            Log.e("BookTracker", "AmazonParser: Exception during parsing", e)
            return null
        }
    }
    
    private fun isIntermediatePage(document: Document): Boolean {
        val pageText = document.text().lowercase(Locale.ROOT)
        val pageTitle = document.select("title").text()
        val pageTitleLower = pageTitle.lowercase(Locale.ROOT)
        
        // Check for explicit intermediate page indicators (only the most obvious ones)
        if (pageText.contains("sorry, we just need to make sure you're not a robot") ||
            pageText.contains("enter the characters you see below") ||
            pageText.contains("page not found") ||
            pageText.contains("something went wrong") ||
            pageText.contains("access denied") ||
            pageText.contains("captcha") ||
            pageText.contains("blocked")) {
            Log.d("BookTracker", "AmazonParser: Found explicit intermediate page indicator")
            return true
        }
        
        // If page title contains book information pattern, definitely not intermediate
        if (hasBookInfoInTitle(pageTitle)) {
            Log.d("BookTracker", "AmazonParser: Page title contains book info, not considering intermediate")
            return false
        }
        
        // Check for very obvious error pages only
        if (pageTitleLower.contains("error") && pageTitle.length < 30) {
            Log.d("BookTracker", "AmazonParser: Short error page detected")
            return true
        }
        
        // Much more conservative - only consider it intermediate if it's clearly not a product page
        val hasAnyProductIndicators = document.select("#productTitle, h1[id*='title'], [data-automation-id='title'], .product-title, #bylineInfo, .author, [data-automation-id='author'], [data-asin]:not([data-asin=''])").any() ||
                                      pageTitle.contains(":") && pageTitle.length > 30 // Likely book title format
        
        if (!hasAnyProductIndicators && pageTitle.length < 20) {
            Log.d("BookTracker", "AmazonParser: No product indicators and very short title - likely intermediate")
            return true
        }
        
        return false
    }
    
    private fun hasBookInfoInTitle(pageTitle: String): Boolean {
        if (pageTitle.isBlank()) return false
        
        // Check if title follows Amazon book page pattern
        val parts = pageTitle.split(":").map { it.trim() }
        
        // Should have at least 3-4 parts for a book page
        if (parts.size < 3) return false
        
        // Should contain "Books" or "Amazon.com" 
        val hasAmazonIndicator = parts.any { 
            it.contains("amazon", ignoreCase = true) || 
            it.equals("books", ignoreCase = true) 
        }
        
        // Should have potential ISBN (10 or 13 digits)
        val hasISBN = parts.any { part ->
            val cleanPart = part.replace(Regex("[\\s-]"), "")
            cleanPart.matches(Regex("\\d{10}")) || cleanPart.matches(Regex("\\d{13}"))
        }
        
        // Should have reasonable title length (not just error messages)
        val hasReasonableTitle = parts.any { it.length > 10 && !it.contains("error", ignoreCase = true) }
        
        val result = hasAmazonIndicator && hasReasonableTitle && parts.size >= 3
        Log.d("BookTracker", "AmazonParser: Title '$pageTitle' book info check - Amazon: $hasAmazonIndicator, Title: $hasReasonableTitle, Parts: ${parts.size}, ISBN: $hasISBN, Result: $result")
        
        return result
    }
    
    private fun extractTitle(document: Document): String? {
        // Try multiple selectors for title
        val titleSelectors = listOf(
            "#productTitle",
            "h1.a-size-large",
            "[data-automation-id='title']",
            "h1[id*='title']",
            ".product-title",
            "h1.a-size-large.a-spacing-none.a-color-base",
            "span#productTitle"
        )
        
        for (selector in titleSelectors) {
            val title = document.select(selector).first()?.text()?.trim()
            if (!title.isNullOrBlank() && title.length > 3) {  // Ensure it's not just whitespace or very short
                return title
            }
        }
        
        return null
    }
    
    private fun extractAuthor(document: Document): String? {
        // Try multiple selectors for author
        val authorSelectors = listOf(
            ".author .contributorNameID",
            ".author a",
            "[data-automation-id='author-strip'] a",
            ".a-section .author a",
            "#bylineInfo .author a",
            "#bylineInfo_feature_div .author a",
            ".a-link-normal[href*='/author/']",
            "span.author a",
            "#apex_desktop .author a",
            ".by-author a[href*='/author/']"
        )
        
        for (selector in authorSelectors) {
            val author = document.select(selector).first()?.text()?.trim()
            if (!author.isNullOrBlank() && author.length > 2) {
                return cleanAuthorName(author)
            }
        }
        
        // Fallback: look for "by" followed by text that might be an author
        val bylineText = document.select("#bylineInfo, .bylineInfo").first()?.text()
        if (!bylineText.isNullOrBlank()) {
            val byRegex = Regex("(?i)by\\s+([^,()]+)")
            val match = byRegex.find(bylineText)
            if (match != null) {
                val author = match.groupValues[1].trim()
                if (author.length > 2) {
                    return cleanAuthorName(author)
                }
            }
        }
        
        // Advanced fallback: extract author from page title
        // Pattern: "Book Title: Author Name: ISBN: Amazon.com: Books"
        val pageTitle = document.select("title").first()?.text()
        if (!pageTitle.isNullOrBlank()) {
            Log.d("BookTracker", "AmazonParser: Trying to extract author from page title: $pageTitle")
            
            // Split by colons and look for author pattern
            val titleParts = pageTitle.split(":")
            if (titleParts.size >= 3) {
                // Look for author patterns, prioritizing shorter, name-like strings
                var bestAuthorCandidate: String? = null
                var bestScore = -1
                
                for (i in 1 until titleParts.size) {
                    val potentialAuthor = titleParts[i].trim()
                    
                    // Skip obvious non-author parts
                    if (potentialAuthor.contains("amazon", ignoreCase = true) ||
                        potentialAuthor.contains("books", ignoreCase = true) ||
                        potentialAuthor.matches(Regex("\\d+")) || // Just numbers (ISBN)
                        potentialAuthor.matches(Regex("\\d{10,13}")) || // ISBN pattern
                        potentialAuthor.length < 3 || potentialAuthor.length > 100) {
                        continue
                    }
                    
                    // Score potential authors - prefer shorter, name-like strings
                    var score = 0
                    
                    // Higher score for comma-separated names (Last, First format)
                    if (potentialAuthor.contains(",") && potentialAuthor.split(",").size == 2) {
                        score += 50
                    }
                    
                    // Higher score for typical name length (5-30 characters)
                    if (potentialAuthor.length in 5..30) {
                        score += 30
                    }
                    
                    // Lower score for very long strings (likely subtitles)
                    if (potentialAuthor.length > 50) {
                        score -= 40
                    }
                    
                    // Higher score for strings with 2-4 words (typical for names)
                    val wordCount = potentialAuthor.split("\\s+".toRegex()).size
                    if (wordCount in 2..4) {
                        score += 20
                    } else if (wordCount > 6) {
                        score -= 30 // Likely a subtitle
                    }
                    
                    // Higher score for proper capitalization
                    if (potentialAuthor.matches(Regex("^[A-Z][a-z]+(,?\\s+[A-Z][a-z]+)*$"))) {
                        score += 25
                    }
                    
                    // Lower score for parentheses (likely publication info)
                    if (potentialAuthor.contains("(") || potentialAuthor.contains(")")) {
                        score -= 20
                    }
                    
                    Log.d("BookTracker", "AmazonParser: Potential author '$potentialAuthor' scored $score")
                    
                    if (score > bestScore && score > 30) { // Minimum threshold
                        bestScore = score
                        bestAuthorCandidate = potentialAuthor
                    }
                }
                
                if (bestAuthorCandidate != null) {
                    Log.d("BookTracker", "AmazonParser: Selected best author candidate: '$bestAuthorCandidate' (score: $bestScore)")
                    return cleanAuthorName(bestAuthorCandidate)
                }
            }
            
            // Alternative pattern: "Title by Author"
            val byMatch = Regex("(?i)(.+?)\\s+by\\s+([^:]+)").find(pageTitle)
            if (byMatch != null) {
                val author = byMatch.groupValues[2].trim()
                if (author.length > 2) {
                    Log.d("BookTracker", "AmazonParser: Found author with 'by' pattern: '$author'")
                    return cleanAuthorName(author)
                }
            }
        }
        
        return null
    }
    
    private fun extractFromPageTitle(document: Document): ParsedBookInfo? {
        val pageTitle = document.select("title").first()?.text()
        if (pageTitle.isNullOrBlank()) {
            Log.w("BookTracker", "AmazonParser: No page title found")
            return null
        }
        
        Log.d("BookTracker", "AmazonParser: Extracting from page title: '$pageTitle'")
        
        // Amazon page titles typically follow the pattern:
        // "Title: Subtitle: Author: ISBN: Amazon.com: Books"
        // or "Title: Author: ISBN: Amazon.com: Books"
        
        val parts = pageTitle.split(":").map { it.trim() }
        if (parts.size < 4) {
            Log.w("BookTracker", "AmazonParser: Page title doesn't have enough parts (${parts.size})")
            return null
        }
        
        // Filter out obvious non-content parts
        val contentParts = parts.filter { part ->
            !part.contains("amazon", ignoreCase = true) && 
            !part.equals("books", ignoreCase = true) &&
            part.isNotBlank()
        }
        
        if (contentParts.size < 2) {
            Log.w("BookTracker", "AmazonParser: Not enough content parts after filtering")
            return null
        }
        
        Log.d("BookTracker", "AmazonParser: Content parts: $contentParts")
        
        var title: String?
        var author: String? = null
        var isbn10: String? = null
        var isbn13: String? = null
        
        // Identify ISBN (look for 10 or 13 digit numbers)
        var isbnIndex = -1
        for (i in contentParts.indices) {
            val part = contentParts[i].replace(Regex("[\\s-]"), "")
            if (part.matches(Regex("\\d{10}")) || part.matches(Regex("\\d{13}"))) {
                if (part.length == 10) {
                    isbn10 = part
                } else {
                    isbn13 = part
                }
                isbnIndex = i
                Log.d("BookTracker", "AmazonParser: Found ISBN at index $i: $part")
                break
            }
        }
        
        // Determine title and author based on structure
        if (isbnIndex != -1 && isbnIndex >= 2) {
            // If we found ISBN and it's at position 2 or later
            // Author should be just before ISBN
            author = contentParts[isbnIndex - 1]
            
            // Title is everything before the author (may include subtitle)
            val titleParts = contentParts.subList(0, isbnIndex - 1)
            title = titleParts.joinToString(": ")
            
            Log.d("BookTracker", "AmazonParser: Identified with ISBN - Title: '$title', Author: '$author'")
        } else {
            // Fallback: assume first part is title, second is author
            title = contentParts[0]
            
            // Try to find the best author candidate
            for (i in 1 until contentParts.size) {
                val candidate = contentParts[i]
                
                // Score potential authors
                var score = 0
                val wordCount = candidate.split("\\s+".toRegex()).size
                
                // Prefer reasonable name lengths
                if (candidate.length in 5..50) score += 30
                if (wordCount in 1..4) score += 20
                
                // Prefer proper name capitalization
                if (candidate.matches(Regex("^[A-Z][a-z]+(,?\\s+[A-Z][a-z]+)*$"))) score += 25
                
                // Avoid subtitles (usually longer)
                if (candidate.length > 80 || wordCount > 8) score -= 40
                
                // Avoid publication info
                if (candidate.contains("(") || candidate.contains("updated") || candidate.contains("edition")) {
                    score -= 30
                }
                
                Log.d("BookTracker", "AmazonParser: Author candidate '$candidate' scored $score")
                
                if (score > 30 && author == null) {
                    author = candidate
                    
                    // If this author was found, and there are parts between title and author,
                    // include them in the title
                    if (i > 1) {
                        val allTitleParts = contentParts.subList(0, i)
                        title = allTitleParts.joinToString(": ")
                    }
                    
                    Log.d("BookTracker", "AmazonParser: Selected author: '$author'")
                    break
                }
            }
            
            // Final fallback: just use first two parts
            if (author == null && contentParts.size >= 2) {
                author = contentParts[1]
                Log.d("BookTracker", "AmazonParser: Using fallback author: '$author'")
            }
        }
        
        // Clean up extracted data
        title = title?.trim()
        author = author?.let { cleanAuthorName(it) }
        
        if (title.isNullOrBlank() || author.isNullOrBlank()) {
            Log.w("BookTracker", "AmazonParser: Page title extraction failed - Title: '$title', Author: '$author'")
            return null
        }
        
        Log.d("BookTracker", "AmazonParser: Successfully extracted from page title - Title: '$title', Author: '$author', ISBN-10: '$isbn10', ISBN-13: '$isbn13'")
        
        return ParsedBookInfo(
            title = title,
            author = author,
            isbn10 = isbn10,
            isbn13 = isbn13,
            price = null, // Can't reliably extract price from title
            storeName = getStoreName(),
            storeUrl = "", // Will be set by caller
            coverImageUrl = null // Can't extract from title
        )
    }
    
    private fun cleanAuthorName(author: String): String {
        return author.replace(Regex("\\(.*?\\)"), "") // Remove parentheses content
            .replace(Regex("\\[.*?]"), "")  // Remove bracket content
            .trim()
    }
    
    private fun extractPrice(document: Document): Double {
        Log.d("BookTracker", "AmazonParser: Starting price extraction")
        
        /*
         * AMAZON PRICING STRUCTURE EXPLANATION:
         * 
         * Amazon displays two main types of prices:
         * 1. CURRENT PRICE (what we want): The actual selling price
         *    - Has class "priceToPay" or "reinventPricePriceToPayMargin"
         *    - data-a-color="base" (primary color)
         *    - Usually larger font size (xl, xxl)
         *    - NOT crossed out
         * 
         * 2. LIST PRICE (what we DON'T want): The original/retail price  
         *    - Has data-a-strike="true" (crossed out)
         *    - data-a-color="secondary" (grayed out)
         *    - Usually accompanied by "List Price:" text
         *    - Smaller font size
         * 
         * Priority order:
         * 1. Elements with "priceToPay" class (current sale price)
         * 2. Elements with data-a-color="base" and no strike-through
         * 3. Main product area prices without list price indicators
         * 4. Fallback to any price not marked as list/struck-through
         */
        
        // PRIORITY 0: Used-only listing price (highest precedence when page is only offering used copies)
        // This covers both desktop (#usedBuySection) and mobile (#corePriceDisplay_mobile_feature_div) layouts.
        val usedPriceSelectors = listOf(
            "#usedBuySection .aok-offscreen",                        // desktop hidden price span
            "#usedBuySection .a-price .a-offscreen",                // desktop a-price structure
            "#corePriceDisplay_mobile_feature_div .aok-offscreen",  // mobile hidden price span
            "#corePriceDisplay_mobile_feature_div .priceToPay .a-offscreen" // mobile a-price structure
        )

        for (selector in usedPriceSelectors) {
            val elements = document.select(selector)
            Log.d("BookTracker", "AmazonParser: Trying USED price selector '$selector', found ${elements.size} elements")

            for (element in elements) {
                val priceText = element.text()
                if (priceText.isNotBlank() && !priceText.equals("$0.00", ignoreCase = true)) {
                    Log.d("BookTracker", "AmazonParser: Found used-only price text: '$priceText'")

                    val price = extractPriceFromText(priceText)
                    if (price != null && price > 0) {
                        Log.d("BookTracker", "AmazonParser: ✅ SUCCESS - Extracted USED-ONLY price: $price")
                        return price
                    }
                }
            }
        }

        extractBuyingOptionPrice(document)?.let { price ->
            Log.d("BookTracker", "AmazonParser: ✅ SUCCESS - Extracted buying-option price: $price")
            return price
        }

        // PRIORITY 1: Current/Sale price selectors (highest confidence)
        // These target Amazon's primary price display for the actual selling price
        val currentPriceSelectors = listOf(
            // Primary current price indicator - Amazon's "price to pay" 
            ".priceToPay .a-offscreen",
            ".reinventPricePriceToPayMargin .a-offscreen",
            
            // Main product area prices (avoid recommendations/carousels)
            "#centerCol .a-price[data-a-color='base']:not([data-a-strike='true']) .a-offscreen",
            "#rightCol .a-price[data-a-color='base']:not([data-a-strike='true']) .a-offscreen",
            "#dp-container .a-price[data-a-color='base']:not([data-a-strike='true']) .a-offscreen",
            
            // Buybox current prices (deal/our price sections)
            "#priceblock_dealprice",
            "#priceblock_ourprice", 
            "#price_inside_buybox .a-offscreen",
            
            // Modern Amazon current price selectors
            "[data-automation-id='current-price'] .a-offscreen",
            "[data-automation-id='price'] .a-offscreen",
            
            // Specific main product price containers (avoid carousels)
            "#apex_desktop .a-price[data-a-color='base']:not([data-a-strike='true']) .a-offscreen",
            ".centerColAlign .a-price[data-a-color='base']:not([data-a-strike='true']) .a-offscreen"
        )
        
        // Try current price selectors first
        for (selector in currentPriceSelectors) {
            val elements = document.select(selector)
            Log.d("BookTracker", "AmazonParser: Trying CURRENT price selector '$selector', found ${elements.size} elements")
            
            for (element in elements) {
                val priceText = element.text()
                if (priceText.isNotBlank() && !priceText.equals("$0.00", ignoreCase = true)) {
                    Log.d("BookTracker", "AmazonParser: Found current price text: '$priceText'")
                    
                    // Additional validation: skip if parent has list price indicators
                    val parent = element.parent()
                    val isListPrice = parent?.hasAttr("data-a-strike") == true ||
                                     parent?.attr("data-a-color") == "secondary" ||
                                     parent?.text()?.contains("List Price", ignoreCase = true) == true
                    
                    if (isListPrice) {
                        Log.d("BookTracker", "AmazonParser: Skipping price - detected as list price: '$priceText'")
                        continue
                    }
                    
                    val price = extractPriceFromText(priceText)
                    if (price != null && price > 0) {
                        Log.d("BookTracker", "AmazonParser: ✅ SUCCESS - Extracted CURRENT price: $price")
                        return price
                    }
                }
            }
        }
        
        // PRIORITY 2: General buybox/main product prices (medium confidence)
        // These target main product areas but avoid recommendation sections
        val generalPriceSelectors = listOf(
            "#buybox .a-price .a-offscreen",
            "#dp-container .a-price .a-offscreen",
            "#main-content .a-price .a-offscreen:not([data-testid*='recommended'])",
            ".centerCol .a-price .a-offscreen",
            
            // Book-specific main product selectors
            "#mediaTab_heading .a-price .a-offscreen",
            ".mediaTab_heading .a-price .a-offscreen",
            
            // Modern Amazon buybox selectors  
            "#apex_desktop .a-price .a-offscreen"
        )
        
        for (selector in generalPriceSelectors) {
            val elements = document.select(selector)
            Log.d("BookTracker", "AmazonParser: Trying GENERAL price selector '$selector', found ${elements.size} elements")
            
            for (element in elements) {
                val priceText = element.text()
                if (priceText.isNotBlank() && !priceText.equals("$0.00", ignoreCase = true)) {
                    Log.d("BookTracker", "AmazonParser: Found general price text: '$priceText'")
                    
                    // Skip obvious list prices
                    val parent = element.parent()
                    val grandParent = parent?.parent()
                    val isListPrice = parent?.hasAttr("data-a-strike") == true ||
                                     parent?.attr("data-a-color") == "secondary" ||
                                     grandParent?.text()?.contains("List Price", ignoreCase = true) == true ||
                                     grandParent?.text()?.contains("Was:", ignoreCase = true) == true
                    
                    if (isListPrice) {
                        Log.d("BookTracker", "AmazonParser: Skipping price - detected as list price: '$priceText'")
                        continue
                    }
                    
                    val price = extractPriceFromText(priceText)
                    if (price != null && price > 0) {
                        Log.d("BookTracker", "AmazonParser: ✅ SUCCESS - Extracted general price: $price")
                        return price
                    }
                }
            }
        }
        
        // PRIORITY 3: Broader selectors with strict list price filtering (lower confidence)
        val fallbackSelectors = listOf(
            ".a-price:not([data-a-strike='true']):not([data-a-color='secondary']) .a-offscreen:not([data-testid*='recommended']):not([data-testid*='carousel'])",
            ".a-price[data-a-size] .a-offscreen"
        )
        
        for (selector in fallbackSelectors) {
            val elements = document.select(selector)
            Log.d("BookTracker", "AmazonParser: Trying FALLBACK price selector '$selector', found ${elements.size} elements")
            
            // Only try first few elements to avoid getting random prices from page
            for (element in elements.take(3)) {
                val priceText = element.text()
                if (priceText.isNotBlank() && !priceText.equals("$0.00", ignoreCase = true)) {
                    Log.d("BookTracker", "AmazonParser: Found fallback price text: '$priceText'")
                    
                    // Strict list price filtering
                    val contextText = element.parent()?.parent()?.text() ?: ""
                    val isListPrice = contextText.contains("List Price", ignoreCase = true) ||
                                     contextText.contains("Was:", ignoreCase = true) ||
                                     contextText.contains("MSRP", ignoreCase = true) ||
                                     element.parent()?.hasAttr("data-a-strike") == true
                    
                    if (isListPrice) {
                        Log.d("BookTracker", "AmazonParser: Skipping price - context indicates list price: '$priceText'")
                        continue
                    }
                    
                    val price = extractPriceFromText(priceText)
                    if (price != null && price > 0) {
                        Log.d("BookTracker", "AmazonParser: ✅ SUCCESS - Extracted fallback price: $price")
                        return price
                    }
                }
            }
        }
        
        // PRIORITY 4: Final content-based search (lowest confidence)
        // Look for price patterns in main content but avoid list price sections
        val mainContentArea = document.select("#dp-container, #centerCol, #rightCol").first()
        if (mainContentArea != null) {
            Log.d("BookTracker", "AmazonParser: Trying CONTENT-BASED price extraction")
            val priceElements = mainContentArea.select("*:contains('$')")
            
            for (element in priceElements.take(5)) {
                val text = element.ownText()
                val contextText = element.text()
                
                if (text.matches(Regex(".*\\$\\d+\\.\\d{2}.*")) && 
                    !contextText.contains("was", ignoreCase = true) &&
                    !contextText.contains("list", ignoreCase = true) &&
                    !contextText.contains("MSRP", ignoreCase = true) &&
                    !contextText.contains("Save", ignoreCase = true) &&
                    !element.hasClass("a-text-strike")) {
                    
                    Log.d("BookTracker", "AmazonParser: Found price pattern in main content: '$text'")
                    val price = extractPriceFromText(text)
                    if (price != null && price > 0) {
                        Log.d("BookTracker", "AmazonParser: ✅ SUCCESS - Extracted content-based price: $price")
                        return price
                    }
                }
            }
        }
        
        Log.w("BookTracker", "AmazonParser: ❌ FAILED - No valid current price found (may have only found list prices)")
        return 0.0
    }

    private fun extractBuyingOptionPrice(document: Document): Double? {
        val optionSelectors = listOf(
            "#tmmSwatches li",
            "#tmmSwatches .swatchElement",
            "#mediaTab_heading",
            ".mediaTab_heading",
            "[id^=tmm-grid-swatch-]",
            "[data-a-button-group*='tmm'] .a-button",
            ".slot-price",
            ".audible_mm_grid_swatch"
        )

        for (selector in optionSelectors) {
            val elements = document.select(selector)
            Log.d("BookTracker", "AmazonParser: Trying BUYING OPTION selector '$selector', found ${elements.size} elements")

            for (element in elements) {
                val contextText = buyingOptionContextText(element)
                if (!looksLikeBuyingOption(contextText)) {
                    continue
                }

                val price = extractPriceFromText(contextText)
                if (price != null && price > 0) {
                    Log.d("BookTracker", "AmazonParser: Found buying-option price '$price' from '$contextText'")
                    return price
                }
            }
        }

        return null
    }

    private fun buyingOptionContextText(element: Element): String {
        val candidates = sequenceOf(
            element,
            element.parent(),
            element.parent()?.parent(),
            element.parent()?.parent()?.parent()
        ).filterNotNull()

        return candidates
            .map { it.text().replace(Regex("\\s+"), " ").trim() }
            .firstOrNull { text ->
                text.contains("$") && text.contains("option", ignoreCase = true) && hasBookFormat(text)
            }
            ?: element.text().replace(Regex("\\s+"), " ").trim()
    }

    private fun looksLikeBuyingOption(text: String): Boolean {
        if (text.isBlank() || !text.contains("$")) {
            return false
        }

        val lowerText = text.lowercase(Locale.ROOT)
        val hasBuyingOptionPhrase = Regex("\\b\\d*\\s*options?\\s+from\\b").containsMatchIn(lowerText) ||
            Regex("\\bfrom\\s+\\$").containsMatchIn(lowerText)
        val hasBookFormat = hasBookFormat(text)
        val excluded = listOf(
            "list price",
            "was:",
            "save ",
            "coupon",
            "sponsored",
            "frequently bought",
            "customers also",
            "related products"
        ).any { lowerText.contains(it) }

        return hasBuyingOptionPhrase && hasBookFormat && !excluded
    }

    private fun hasBookFormat(text: String): Boolean {
        val lowerText = text.lowercase(Locale.ROOT)
        return listOf(
            "paperback",
            "hardcover",
            "spiral-bound",
            "mass market paperback",
            "kindle",
            "audio",
            "board book",
            "textbook binding"
        ).any { lowerText.contains(it) }
    }
    
    private fun extractPriceFromText(priceText: String): Double? {
        return try {
            val currencyPriceMatch = Regex("""[$£€]\s*([0-9,]+\.\d{2})""").find(priceText)
            if (currencyPriceMatch != null) {
                return currencyPriceMatch.groupValues[1].replace(",", "").toDoubleOrNull()
            }

            // Remove currency symbols and clean the text
            val cleanPrice = priceText.replace(Regex("[^\\d.,]"), "")
                .replace(",", "")
                .trim()
            
            // Handle various price formats
            val priceMatch = Regex("(\\d+\\.\\d{2})").find(cleanPrice)
            priceMatch?.value?.toDoubleOrNull()
        } catch (e: Exception) {
            Log.e("BookTracker", "AmazonParser: Error parsing price from '$priceText'", e)
            null
        }
    }
    
    private fun extractISBN(document: Document): Pair<String?, String?> {
        var isbn10: String? = null
        var isbn13: String? = null
        
        Log.d("BookTracker", "AmazonParser: Starting ISBN extraction")
        
        // Try multiple selectors for ISBN information
        val isbnSelectors = listOf(
            "#detailBullets_feature_div ul li",
            "#detail-bullets td", 
            "#productDetails_detailBullets_sections1 td",
            "#productDetails_techSpec_section_1 td",
            ".detail-bullet-list td",
            ".a-section.a-spacing-small"
        )
        
        for (selector in isbnSelectors) {
            val elements = document.select(selector)
            Log.d("BookTracker", "AmazonParser: Trying ISBN selector '$selector', found ${elements.size} elements")
            
            for (row in elements) {
                val text = row.text()
                if (text.contains("ISBN", ignoreCase = true)) {
                    Log.d("BookTracker", "AmazonParser: Found ISBN text: '$text'")
                    
                    if (text.contains("ISBN-10", ignoreCase = true) && isbn10 == null) {
                        isbn10 = extractISBNFromText(text)
                        Log.d("BookTracker", "AmazonParser: Extracted ISBN-10: '$isbn10'")
                    } else if (text.contains("ISBN-13", ignoreCase = true) && isbn13 == null) {
                        isbn13 = extractISBNFromText(text)
                        Log.d("BookTracker", "AmazonParser: Extracted ISBN-13: '$isbn13'")
                    }
                }
            }
        }
        
        // Fallback: extract from page title if available  
        if (isbn10 == null && isbn13 == null) {
            val pageTitle = document.select("title").first()?.text()
            if (!pageTitle.isNullOrBlank()) {
                val titleParts = pageTitle.split(":")
                for (part in titleParts) {
                    val cleanPart = part.trim()
                    if (cleanPart.matches(Regex("\\d{10}"))) {
                        isbn10 = cleanPart
                        Log.d("BookTracker", "AmazonParser: Found ISBN-10 in title: '$isbn10'")
                    } else if (cleanPart.matches(Regex("\\d{13}"))) {
                        isbn13 = cleanPart
                        Log.d("BookTracker", "AmazonParser: Found ISBN-13 in title: '$isbn13'")
                    }
                }
            }
        }
        
        Log.d("BookTracker", "AmazonParser: Final ISBN results - ISBN-10: '$isbn10', ISBN-13: '$isbn13'")
        return Pair(isbn10, isbn13)
    }
    
    private fun extractISBNFromText(text: String): String? {
        /*
         * AMAZON ISBN EXTRACTION LOGIC:
         * 
         * Amazon displays ISBNs in various formats:
         * 1. 'ISBN-10 ‏ : ‎ 1400333598' (with Unicode RTL marks)
         * 2. 'ISBN-13 ‏ : ‎ 978-1400333592' (with Unicode RTL marks)
         * 3. 'ISBN-10: 1400333598' (simple format)
         * 4. 'ISBN-13: 978-1400333592' (simple format)
         * 
         * Expected outputs:
         * - ISBN-10: '1400333598' (10 digits, possibly ending with X)
         * - ISBN-13: '9781400333592' (13 digits, no dashes)
         * 
         * The key issue is handling Unicode directional marks and ensuring
         * we capture the FULL number including the 978 prefix for ISBN-13.
         */
        
        Log.d("BookTracker", "AmazonParser: Extracting ISBN from text: '$text'")
        
        // First, clean the text of Unicode directional marks and extra whitespace
        val cleanText = text.replace(Regex("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]"), "") // Remove Unicode marks
                            .replace(Regex("\\s+"), " ") // Normalize whitespace
                            .trim()
        
        Log.d("BookTracker", "AmazonParser: Cleaned text: '$cleanText'")
        
        // PRIORITY 1: Direct number extraction after ISBN label
        // Look for ISBN-10 or ISBN-13 followed by colon/space and capture the full number
        val isbnLabelRegex = Regex("ISBN-?(?:10|13)?[:\\s]+([\\d\\-X]+)")
        val labelMatch = isbnLabelRegex.find(cleanText)
        
        if (labelMatch != null) {
            val rawIsbn = labelMatch.groupValues[1].replace(Regex("[\\s\\-]"), "") // Remove spaces and hyphens
            Log.d("BookTracker", "AmazonParser: Found ISBN with label - raw: '${labelMatch.groupValues[1]}', cleaned: '$rawIsbn'")
            
            // Validate the extracted ISBN format
            if (rawIsbn.matches(Regex("\\d{9}[\\dX]"))) { // ISBN-10: 9 digits + check digit
                Log.d("BookTracker", "AmazonParser: Valid ISBN-10 format: '$rawIsbn'")
                return rawIsbn
            } else if (rawIsbn.matches(Regex("\\d{13}"))) { // ISBN-13: 13 digits
                Log.d("BookTracker", "AmazonParser: Valid ISBN-13 format: '$rawIsbn'")
                return rawIsbn
            } else {
                Log.w("BookTracker", "AmazonParser: Invalid ISBN format after label extraction: '$rawIsbn'")
            }
        }
        
        // PRIORITY 2: Broader number extraction
        // Look for any 10 or 13 digit numbers that could be ISBNs
        val numberRegex = Regex("\\b(\\d{13}|\\d{9}[\\dX])\\b")
        val numberMatch = numberRegex.find(cleanText)
        
        if (numberMatch != null) {
            val isbnCandidate = numberMatch.value
            Log.d("BookTracker", "AmazonParser: Found ISBN candidate number: '$isbnCandidate'")
            
            // Additional validation for ISBN-13 (should start with 978 or 979)
            if (isbnCandidate.length == 13) {
                if (isbnCandidate.startsWith("978") || isbnCandidate.startsWith("979")) {
                    Log.d("BookTracker", "AmazonParser: Valid ISBN-13 candidate: '$isbnCandidate'")
                    return isbnCandidate
                } else {
                    Log.w("BookTracker", "AmazonParser: Invalid ISBN-13 candidate (doesn't start with 978/979): '$isbnCandidate'")
                }
            } else if (isbnCandidate.length == 10) {
                Log.d("BookTracker", "AmazonParser: Valid ISBN-10 candidate: '$isbnCandidate'")
                return isbnCandidate
            }
        }
        
        // PRIORITY 3: Fallback - extract any digit sequences
        // Sometimes Amazon might have extra formatting
        val allDigits = cleanText.replace(Regex("[^\\dX]"), "") // Keep only digits and X
        Log.d("BookTracker", "AmazonParser: All digits extracted: '$allDigits'")
        
        if (allDigits.length == 10 && allDigits.matches(Regex("\\d{9}[\\dX]"))) {
            Log.d("BookTracker", "AmazonParser: Fallback ISBN-10: '$allDigits'")
            return allDigits
        } else if (allDigits.length == 13 && allDigits.matches(Regex("\\d{13}"))) {
            if (allDigits.startsWith("978") || allDigits.startsWith("979")) {
                Log.d("BookTracker", "AmazonParser: Fallback ISBN-13: '$allDigits'")
                return allDigits
            }
        }
        
        Log.w("BookTracker", "AmazonParser: No valid ISBN extracted from: '$text'")
        return null
    }
    
    private fun extractCoverImage(document: Document): String? {
        Log.d("BookTracker", "AmazonParser: Starting image extraction")
        
        val imageSelectors = listOf(
            // Primary Amazon image selectors (highest priority)
            "#landingImage",
            "#imgBlkFront",
            "#ebooksImgBlkFront",
            
            // Modern Amazon selectors
            "[data-a-image-name='main-image']",
            ".a-dynamic-image[data-a-image-name]",
            ".a-dynamic-image",
            
            // Book-specific selectors
            "[data-automation-id='hero-image'] img",
            "#imgTagWrapperId img",
            ".book-image img",
            
            // Container-based selectors
            "#main-image-container img",
            "#main-image img",
            
            // Generic Amazon image selectors
            "img[data-a-image-name='landingImage']",
            "img[alt*='book']",
            "img[alt*='cover']",
            ".centerCol img",
            "#centerCol img",
            
            // Fallback selectors
            "img[src*='images-amazon']",
            "img[src*='ssl-images-amazon']"
        )
        
        var bestImageUrl: String? = null
        var bestImageScore = -1
        
        for (selector in imageSelectors) {
            val elements = document.select(selector)
            Log.d("BookTracker", "AmazonParser: Trying selector '$selector', found ${elements.size} elements")
            
            for (element in elements) {
                val imageUrl = element.attr("src")
                val dataSrc = element.attr("data-src")
                val dataOldHires = element.attr("data-old-hires")
                val dataA2100 = element.attr("data-a-image-source")
                
                Log.d("BookTracker", "AmazonParser: Element attributes - src: '$imageUrl', data-src: '$dataSrc', data-old-hires: '$dataOldHires', data-a-image-source: '$dataA2100'")
                
                // Try different image URL sources, prioritizing high-res versions
                val urlCandidates = listOf(
                    Pair(dataOldHires, 100), // Highest priority - hi-res version
                    Pair(dataA2100, 90),     // Second priority
                    Pair(imageUrl, 80),      // Third priority - current src
                    Pair(dataSrc, 70)        // Lowest priority - lazy load src
                ).filter { it.first.isNotBlank() }
                
                for ((url, baseScore) in urlCandidates) {
                    if (isValidBookCoverUrl(url)) {
                        val score = calculateImageScore(url, baseScore)
                        Log.d("BookTracker", "AmazonParser: Valid image URL '$url' scored $score")
                        
                        if (score > bestImageScore) {
                            bestImageScore = score
                            bestImageUrl = url
                            Log.d("BookTracker", "AmazonParser: New best image: $url (score: $score)")
                        }
                        
                        // If we found a really good image, return it immediately
                        if (score >= 150) {
                            Log.d("BookTracker", "AmazonParser: Found excellent image, returning immediately: $url")
                            return url
                        }
                    } else {
                        Log.d("BookTracker", "AmazonParser: Rejected invalid image URL: $url")
                    }
                }
            }
        }
        
        if (bestImageUrl != null) {
            Log.d("BookTracker", "AmazonParser: Returning best image found: $bestImageUrl (score: $bestImageScore)")
            return bestImageUrl
        }
        
        Log.w("BookTracker", "AmazonParser: No valid image found")
        return null
    }
    
    private fun isValidBookCoverUrl(url: String): Boolean {
        if (url.isBlank() || !url.startsWith("http")) return false
        
        // Must be from Amazon's image servers
        if (!url.contains("images-amazon") && !url.contains("ssl-images-amazon") && !url.contains("media-amazon")) {
            return false
        }
        
        // Reject known placeholder/tracking images
        val rejectPatterns = listOf(
            "grey-pixel.gif",
            "transparent.gif", 
            "1x1",
            "pixel.gif",
            "spacer.gif",
            "blank.gif",
            "clear.gif"
        )
        
        for (pattern in rejectPatterns) {
            if (url.contains(pattern, ignoreCase = true)) {
                return false
            }
        }
        
        return true
    }
    
    private fun calculateImageScore(url: String, baseScore: Int): Int {
        var score = baseScore
        
        // Prefer higher resolution images
        if (url.contains("SL1500") || url.contains("SL1200")) score += 50 // Very high res
        else if (url.contains("SL800") || url.contains("SL600")) score += 30 // High res
        else if (url.contains("SY445") || url.contains("SX342")) score += 20 // Medium res
        
        // Prefer book cover dimensions (roughly 3:4 ratio indicators)
        if (url.contains("SY") && url.contains("SX")) score += 25 // Has both dimensions
        
        // Prefer media-amazon domain (newer, more reliable)
        if (url.contains("media-amazon")) score += 15
        
        // Penalty for very small images
        if (url.contains("40_") || url.contains("75_") || url.contains("100_")) score -= 30
        
        return score
    }
    
} 
