package com.booktracker.booksidntneed.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jsoup.Connection
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

// Progress callback interface for granular progress tracking
interface ScrapingProgressCallback {
    fun onTaskStarted(task: String, progress: Int = 0)
    fun onTaskProgress(task: String, progress: Int)
    fun onTaskCompleted(task: String)
    fun onError(task: String, error: String)
}

class WebScrapingService {
    
    // Simple session storage to simulate browser cookies
    private val sessionCookies = ConcurrentHashMap<String, String>()
    
    companion object {
        private const val DEFAULT_CONNECTION_TIMEOUT = 10000 // 10 seconds
        private const val EXTENDED_CONNECTION_TIMEOUT = 30000 // 30 seconds
        
        // Barnes & Noble specific timeouts - tuned for more resilient scraping
        private const val BN_CONNECTION_TIMEOUT = 18000 // 18 seconds
        private const val BN_READ_TIMEOUT = 28000 // 28 seconds
        
        // More realistic desktop User-Agent that's less likely to be blocked
        private const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
        private const val ALTERNATIVE_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
        private const val FIREFOX_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:122.0) Gecko/20100101 Firefox/122.0"
        private const val EDGE_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36 Edg/121.0.0.0"
        
        // Sites that need special handling
        private val PROBLEMATIC_SITES = setOf(
            "amazon.com",
            "amazon.ca",
            "amazon.co.uk", 
            "amazon.de",
            "amazon.fr",
            "amazon.it",
            "amazon.es",
            "amazon.co.jp",
            "a.co",
            "barnesandnoble.com",
            "bn.com",
            "bookoutlet.com",
            "betterworldbooks.com",
            "worldofbooks.com"
        )
        
        private const val MAX_RETRIES = 3
        private const val BN_MAX_RETRIES = 3 // Allow multiple attempts for Barnes & Noble
        private const val BASE_RETRY_DELAY_MS = 5000L
        private const val BN_RETRY_DELAY_MS = 4000L // Balanced delay for B&N retries
        private const val MAX_RANDOM_DELAY_MS = 3000L
        private const val BN_MAX_RANDOM_DELAY_MS = 2000L // Slightly longer random delay for B&N
        private val BARNES_NOBLE_ATTEMPT_ORDER = listOf(3, 1, 2) // Try successful strategy first
    }
    
    suspend fun scrapeBookInfo(url: String, progressCallback: ScrapingProgressCallback? = null): ScrapingResult = withContext(Dispatchers.IO) {
        try {
            Log.d("BookTracker", "WebScraping: Starting to scrape URL: $url")
            
            progressCallback?.onTaskStarted("Validating URL", 0)
            if (!isValidUrl(url)) {
                Log.e("BookTracker", "WebScraping: Invalid URL format: $url")
                progressCallback?.onError("Validating URL", "Invalid URL format")
                return@withContext ScrapingResult.Error("Invalid URL format")
            }
            progressCallback?.onTaskCompleted("Validating URL")
            
            progressCallback?.onTaskStarted("Establishing Session", 0)
            var document = fetchDocumentWithRetry(url, progressCallback)
            var finalUrl = document.location() // Get the final URL after redirects
            
            Log.d("BookTracker", "WebScraping: Original URL: $url")
            Log.d("BookTracker", "WebScraping: Final URL after redirects: $finalUrl")
            
            // Check if Amazon served an intermediate page and retry with cleaned URL
            var retriedWithCleanUrl = false
            if (finalUrl.contains("amazon.com", ignoreCase = true)) {
                val pageTitle = document.select("title").first()?.text() ?: ""
                val isIntermediatePage = pageTitle.equals("Amazon.com", ignoreCase = true) || 
                                       (pageTitle.length < 20 && !document.select("#productTitle, h1[id*='title']").any())
                
                if (isIntermediatePage) {
                    val cleanedFinalUrl = cleanAmazonUrl(finalUrl)
                    if (cleanedFinalUrl != finalUrl) {
                        Log.d("BookTracker", "WebScraping: Detected Amazon intermediate page, retrying with cleaned URL")
                        Log.d("BookTracker", "WebScraping: Retrying with cleaned URL: '$cleanedFinalUrl'")
                        
                        try {
                            // Fetch again with the cleaned URL
                            document = fetchDocumentWithRetry(cleanedFinalUrl)
                            finalUrl = cleanedFinalUrl
                            retriedWithCleanUrl = true
                            Log.d("BookTracker", "WebScraping: Successfully fetched with cleaned URL")
                        } catch (e: Exception) {
                            Log.w("BookTracker", "WebScraping: Failed to fetch with cleaned URL: ${e.message}")
                            // Continue with original document
                        }
                    }
                }
            }
            
            progressCallback?.onTaskCompleted("Fetching Document")
            progressCallback?.onTaskStarted("Parsing Content", 0)
            
            val parser = BookParserFactory.getParser(finalUrl)
            Log.d("BookTracker", "WebScraping: Using parser: ${parser.javaClass.simpleName}")
            
            progressCallback?.onTaskProgress("Parsing Content", 50)
            val bookInfo = parser.parse(document, finalUrl)
            progressCallback?.onTaskProgress("Parsing Content", 100)
            
            progressCallback?.onTaskCompleted("Parsing Content")
            progressCallback?.onTaskStarted("Validating Data", 0)
            
            if (bookInfo != null && bookInfo.isValid()) {
                Log.d("BookTracker", "WebScraping: Successfully parsed book info - Title: ${bookInfo.title}, Author: ${bookInfo.author}")
                progressCallback?.onTaskProgress("Validating Data", 100)
                progressCallback?.onTaskCompleted("Validating Data")
                ScrapingResult.Success(bookInfo)
            } else {
                // Provide more specific error messages
                val pageTitle = document.select("title").first()?.text() ?: ""
                val pageText = document.text().lowercase(Locale.ROOT)
                
                Log.w("BookTracker", "WebScraping: Failed to parse book info")
                Log.w("BookTracker", "WebScraping: Page title: $pageTitle")
                Log.w("BookTracker", "WebScraping: BookInfo was null: ${bookInfo == null}")
                if (bookInfo != null) {
                    Log.w("BookTracker", "WebScraping: BookInfo.isValid() = ${bookInfo.isValid()}, Title: '${bookInfo.title}', Author: '${bookInfo.author}'")
                }
                
                val errorMessage = when {
                    pageText.contains("continue shopping") -> 
                        "This appears to be an Amazon intermediate page. The link may be expired or require manual navigation."
                    pageText.contains("page not found") || pageText.contains("404") -> 
                        "The page was not found. The book may no longer be available at this URL."
                    pageText.contains("robot") || pageText.contains("captcha") -> 
                        "The website is asking for verification. Please try again later or use a different URL."
                    pageText.contains("access denied") || pageText.contains("blocked") ->
                        "Access was denied by the website. This may be due to bot detection. Please try again later."
                    pageTitle.contains("amazon", ignoreCase = true) && !document.select("#productTitle, h1[id*='title']").any() -> {
                        if (retriedWithCleanUrl) {
                            "Amazon served an intermediate page even after URL cleaning. The link may be invalid or temporarily unavailable."
                        } else {
                            "This doesn't appear to be a book product page. Please ensure the URL points to a specific book."
                        }
                    }
                    finalUrl.contains("amazon") && (finalUrl.contains("/gp/") || finalUrl.contains("/ref=") || !finalUrl.contains("/dp/")) -> {
                        if (retriedWithCleanUrl) {
                            "Amazon link appears to be invalid or expired, even after cleaning tracking parameters."
                        } else {
                            "This Amazon URL doesn't point to a specific product. Try using a direct product link (amazon.com/dp/PRODUCTID)."
                        }
                    }
                    bookInfo == null -> 
                        "Could not parse the page content. Final URL: $finalUrl - The website structure may not be supported."
                    else -> 
                        "Could not find required book information (title and author) on this page."
                }
                
                Log.e("BookTracker", "WebScraping: Error - $errorMessage")
                progressCallback?.onError("Validating Data", errorMessage)
                ScrapingResult.Error(errorMessage)
            }
        } catch (e: AbeBooksParser.MultipleSellerException) {
            Log.d("BookTracker", "WebScraping: Multiple sellers found, returning options for user selection")
            progressCallback?.onError("Parsing Content", "Multiple sellers found")
            ScrapingResult.MultipleSellerOptions(
                sellerOptions = e.sellerOptions,
                bookTitle = e.bookTitle,
                bookAuthor = e.bookAuthor,
                bookIsbn = e.bookIsbn,
                coverImageUrl = e.coverImageUrl,
                originalUrl = url
            )
        } catch (e: UnknownHostException) {
            Log.e("BookTracker", "WebScraping: Network error", e)
            progressCallback?.onError("Fetching Document", "Network error: Unable to connect to the website")
            ScrapingResult.Error("Network error: Unable to connect to the website")
        } catch (e: SocketTimeoutException) {
            val host = runCatching { URL(url).host }.getOrDefault("unknown host")
            Log.w("BookTracker", "WebScraping: Timeout error for $host. Message: ${e.message}")
            
            // Provide specific guidance for Barnes & Noble
            val errorMessage = if (isBarnesAndNoble(url)) {
                "Timeout: Barnes & Noble took too long to respond. Please try again later."
            } else {
                "Timeout: The website took too long to respond. Please try again later."
            }
            
            progressCallback?.onError("Fetching Document", errorMessage)
            ScrapingResult.Error(errorMessage)
        } catch (e: SSLException) {
            Log.e("BookTracker", "WebScraping: SSL error", e)
            progressCallback?.onError("Fetching Document", "Security error: Could not establish secure connection")
            ScrapingResult.Error("Security error: Could not establish secure connection")
        } catch (e: HttpStatusException) {
            val host = runCatching { URL(url).host }.getOrDefault("unknown host")
            Log.w("BookTracker", "WebScraping: HTTP error fetching document from $host. Status=${e.statusCode}, URL=${e.url}", e)
            val errorMessage = when (e.statusCode) {
                404 -> "The page was not found (404). The book may no longer be available at this URL."
                403 -> "Access was denied (403). This may be due to bot detection. Please try again later."
                503 -> "The website is temporarily unavailable (503). Please try again later."
                else -> "Received an unexpected HTTP error: ${e.statusCode}."
            }
            progressCallback?.onError("Fetching Document", errorMessage)
            ScrapingResult.Error(errorMessage)
        } catch (e: Exception) {
            Log.e("BookTracker", "WebScraping: Unexpected error", e)
            progressCallback?.onError("Unknown", "Unexpected error: ${e.message ?: "Unknown error occurred"}")
            ScrapingResult.Error("Unexpected error: ${e.message ?: "Unknown error occurred"}")
        }
    }
    
    private fun isValidUrl(url: String): Boolean {
        return try {
            val normalizedUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                "https://$url"
            } else {
                url
            }
            
            Log.d("BookTracker", "WebScraping: Validating URL: $normalizedUrl")
            
            // Use Java's built-in URL class for proper validation
            val javaUrl = URL(normalizedUrl)
            
            // Basic sanity checks
            val isValidScheme = javaUrl.protocol in listOf("http", "https")
            val hasValidHost = !javaUrl.host.isNullOrBlank() && javaUrl.host.contains(".")
            
            val isValid = isValidScheme && hasValidHost
            
            Log.d("BookTracker", "WebScraping: URL validation result: $isValid (scheme: ${javaUrl.protocol}, host: ${javaUrl.host})")
            
            isValid
        } catch (e: Exception) {
            Log.e("BookTracker", "WebScraping: URL validation failed: ${e.message}")
            false
        }
    }
    
    private suspend fun fetchDocumentWithRetry(url: String, progressCallback: ScrapingProgressCallback? = null): Document {
        var lastException: Exception? = null
        
        val isBarnesNoble = isBarnesAndNoble(url)
        val isBookOutlet = isBookOutletUrl(url)
        val isBetterWorldBooks = isBetterWorldBooksUrl(url)
        val isAmazon = isAmazonUrl(url)
        val maxRetries = when {
            isBarnesNoble -> BN_MAX_RETRIES
            isBetterWorldBooks -> 2 // Allow 2 attempts for BWB
            isAmazon -> 2 // Allow 2 attempts for Amazon
            else -> MAX_RETRIES
        }
        val baseDelay = when {
            isBarnesNoble -> BN_RETRY_DELAY_MS
            isBookOutlet -> 2000L // Reduced base delay for Book Outlet
            isBetterWorldBooks -> 3000L // Longer delay for BWB
            isAmazon -> 1000L // Short delay for Amazon
            else -> BASE_RETRY_DELAY_MS
        }
        val maxRandomDelay = when {
            isBarnesNoble -> BN_MAX_RANDOM_DELAY_MS
            isBookOutlet -> 1000L // Reduced random delay for Book Outlet
            isBetterWorldBooks -> 2000L // Higher random delay for BWB
            isAmazon -> 500L // Short random delay for Amazon
            else -> MAX_RANDOM_DELAY_MS
        }
        
        // For Barnes & Noble, establish session first to warm cookies and headers
        if (isBarnesNoble) {
            try {
                Log.d("BookTracker", "WebScraping: Establishing Barnes & Noble session first...")
                progressCallback?.onTaskProgress("Establishing Session", 20)
                establishBarnesNobleSession()
                progressCallback?.onTaskProgress("Establishing Session", 40)
            } catch (e: Exception) {
                Log.w("BookTracker", "WebScraping: Failed to establish Barnes & Noble session, continuing anyway: ${e.message}")
            }
        }

        // For Book Outlet, establish session first
        if (isBookOutlet) {
            try {
                Log.d("BookTracker", "WebScraping: Establishing Book Outlet session first...")
                progressCallback?.onTaskProgress("Establishing Session", 45)
                establishBookOutletSession()
                progressCallback?.onTaskProgress("Establishing Session", 60)
            } catch (e: Exception) {
                Log.w("BookTracker", "WebScraping: Failed to establish session, continuing anyway: ${e.message}")
            }
        }
        
        // For Better World Books, establish session first
        if (isBetterWorldBooks) {
            try {
                Log.d("BookTracker", "WebScraping: Establishing Better World Books session first...")
                progressCallback?.onTaskProgress("Establishing Session", 60)
                establishBetterWorldBooksSession()
                progressCallback?.onTaskProgress("Establishing Session", 80)
            } catch (e: Exception) {
                Log.w("BookTracker", "WebScraping: Failed to establish BWB session, continuing anyway: ${e.message}")
            }
        }
        
        // For World of Books, establish session first
        val isWorldOfBooks = isWorldOfBooksUrl(url)
        if (isWorldOfBooks) {
            try {
                Log.d("BookTracker", "WebScraping: Establishing World of Books session first...")
                progressCallback?.onTaskProgress("Establishing Session", 75)
                establishWorldOfBooksSession()
                progressCallback?.onTaskProgress("Establishing Session", 85)
            } catch (e: Exception) {
                Log.w("BookTracker", "WebScraping: Failed to establish WOB session, continuing anyway: ${e.message}")
            }
        }
        
        // For Amazon, establish session first
        if (isAmazon) {
            try {
                Log.d("BookTracker", "WebScraping: Establishing Amazon session first...")
                progressCallback?.onTaskProgress("Establishing Session", 85)
                establishAmazonSession()
                progressCallback?.onTaskProgress("Establishing Session", 95)
            } catch (e: Exception) {
                Log.w("BookTracker", "WebScraping: Failed to establish Amazon session, continuing anyway: ${e.message}")
            }
        }
        
        // Clean up URLs that might have formatting issues
        val cleanedUrl = when {
            isBarnesNoble -> cleanBarnesNobleUrl(url)
            isBookOutlet -> cleanBookOutletUrl(url)
            isBetterWorldBooks -> cleanBetterWorldBooksUrl(url)
            isWorldOfBooks -> cleanWorldOfBooksUrl(url)
            url.contains("amazon.com", ignoreCase = true) || url.contains("a.co/d/", ignoreCase = true) -> cleanAmazonUrl(url)
            else -> url
        }
        if (cleanedUrl != url) {
            Log.d("BookTracker", "WebScraping: Cleaned URL from '$url' to '$cleanedUrl'")
        }
        
        // For Book Outlet, skip attempt 1 since it always fails with 403
        // Start directly with attempt 2 configuration which works
        // For Better World Books, start with attempt 1 but use enhanced measures
        val startAttempt = if (isBookOutlet) 2 else 1
        val effectiveMaxRetries = when {
            isBookOutlet -> 2
            isBetterWorldBooks -> 2
            isWorldOfBooks -> 3  // Allow 3 attempts for World of Books
            isAmazon -> 2  // Allow 2 attempts for Amazon
            else -> maxRetries
        }
        
        progressCallback?.onTaskCompleted("Establishing Session")
        progressCallback?.onTaskStarted("Fetching Document", 0)
        
        for (attempt in startAttempt..effectiveMaxRetries) {
            try {
                Log.d("BookTracker", "WebScraping: Fetch attempt $attempt of $effectiveMaxRetries for URL: $cleanedUrl")
                
                val attemptProgress = ((attempt - startAttempt + 1) * 100) / (effectiveMaxRetries - startAttempt + 1)
                progressCallback?.onTaskProgress("Fetching Document", attemptProgress)
                
                if (attempt > startAttempt) {
                    // Only add delay for actual retries
                    val randomDelay = baseDelay + Random.nextLong(0, maxRandomDelay)
                    Log.d("BookTracker", "WebScraping: Waiting ${randomDelay}ms before retry...")
                    delay(randomDelay.milliseconds)
                }
                
                return fetchDocument(cleanedUrl, attempt)
            } catch (e: SocketTimeoutException) {
                Log.w("BookTracker", "WebScraping: Timeout on attempt $attempt: ${e.message}")
                lastException = e
                if (attempt == effectiveMaxRetries) {
                    throw e
                }
            } catch (e: Exception) {
                Log.w("BookTracker", "WebScraping: Exception on attempt $attempt: ${e.message}")
                lastException = e
                // For Book Outlet, if we're already at attempt 2 and still get 403, give up
                if (isBookOutlet && e.message?.contains("403") == true) {
                    throw e
                }
                // For Better World Books, allow one retry for 403 errors
                if (isBetterWorldBooks && e.message?.contains("403") == true && attempt >= effectiveMaxRetries) {
                    throw e
                }
                // For World of Books, allow retries for 403 errors
                if (isWorldOfBooks && e.message?.contains("403") == true && attempt >= effectiveMaxRetries) {
                    throw e
                }
                // For Amazon, allow retries for various errors
                if (isAmazon && attempt >= effectiveMaxRetries) {
                    throw e
                }
                // For other exceptions, don't retry unless it's a timeout or specific 403 for BWB/WOB/Amazon
                if (!isBookOutlet && !(isBetterWorldBooks && e.message?.contains("403") == true) && 
                    !(isWorldOfBooks && e.message?.contains("403") == true) && !isAmazon) {
                    throw e
                }
            }
        }
        
        throw lastException ?: SocketTimeoutException("All retry attempts failed")
    }
    
    private suspend fun fetchDocument(url: String, attempt: Int = 1): Document = withContext(Dispatchers.IO) {
        val normalizedUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
            "https://$url"
        } else {
            url
        }
        
        val isBarnesNobleUrl = isBarnesAndNoble(normalizedUrl)
        val isProblematicSite = isProblematicSite(normalizedUrl)
        val isBookOutletDomain = isBookOutletUrl(normalizedUrl)
        val isBetterWorldBooksDomain = isBetterWorldBooksUrl(normalizedUrl)
        val isWorldOfBooksDomain = isWorldOfBooksUrl(normalizedUrl)
        val isAmazonDomain = isAmazonUrl(normalizedUrl)
        val barnesNobleStrategyAttempt = if (isBarnesNobleUrl) {
            val index = (attempt - 1).coerceIn(0, BARNES_NOBLE_ATTEMPT_ORDER.lastIndex)
            BARNES_NOBLE_ATTEMPT_ORDER[index]
        } else {
            attempt
        }
        
        if (isBarnesNobleUrl) {
            Log.d(
                "BookTracker",
                "WebScraping: Barnes & Noble strategy $barnesNobleStrategyAttempt selected for attempt $attempt"
            )
        }
        
        Log.d("BookTracker", "WebScraping: Fetching document from: $normalizedUrl")
        Log.d("BookTracker", "WebScraping: Is problematic site: $isProblematicSite")
        
        val connection = Jsoup.connect(normalizedUrl)
            .followRedirects(true)
            .ignoreHttpErrors(false)
            .ignoreContentType(false) // Be more strict about content types
            .maxBodySize(2048 * 1024) // 2MB max (increased for larger pages)
        
        // Apply site-specific configurations
        if (isProblematicSite) {
            Log.d("BookTracker", "WebScraping: Applying enhanced anti-bot measures for attempt $attempt")
            
            
            // Rotate user agents to make detection harder
            val userAgent = when {
                isBarnesNobleUrl -> when (barnesNobleStrategyAttempt % 3) {
                    0 -> FIREFOX_USER_AGENT
                    1 -> EDGE_USER_AGENT
                    else -> DESKTOP_USER_AGENT
                }
                isBookOutletDomain -> when (attempt % 4) {
                    0 -> DESKTOP_USER_AGENT
                    1 -> ALTERNATIVE_USER_AGENT
                    2 -> FIREFOX_USER_AGENT
                    else -> EDGE_USER_AGENT
                }
                isBetterWorldBooksDomain -> when (attempt % 3) {
                    0 -> FIREFOX_USER_AGENT
                    1 -> EDGE_USER_AGENT  
                    else -> ALTERNATIVE_USER_AGENT
                }
                isWorldOfBooksDomain -> when (attempt % 3) {
                    0 -> FIREFOX_USER_AGENT
                    1 -> ALTERNATIVE_USER_AGENT
                    else -> EDGE_USER_AGENT
                }
                isAmazonDomain -> when (attempt % 3) {
                    0 -> DESKTOP_USER_AGENT
                    1 -> FIREFOX_USER_AGENT
                    else -> EDGE_USER_AGENT
                }
                attempt % 2 == 0 -> ALTERNATIVE_USER_AGENT
                else -> DESKTOP_USER_AGENT
            }
            
            // Use shorter timeouts for Barnes & Noble to fail faster
            val requestTimeout = if (isBarnesNobleUrl) BN_CONNECTION_TIMEOUT else EXTENDED_CONNECTION_TIMEOUT
            
            connection
                .userAgent(userAgent)
                .timeout(requestTimeout)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                .header("Accept-Language", "en-US,en;q=0.9,en-GB;q=0.8")
                .header("Accept-Encoding", "gzip, deflate")
                .header("DNT", "1")
                .header("Connection", "keep-alive")
                .header("Upgrade-Insecure-Requests", "1")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", if (attempt == 1) "none" else "same-origin")
                .header("Sec-Fetch-User", "?1")
                .header("Cache-Control", "max-age=0")
                
            // Enhanced headers for Book Outlet
            if (isBarnesNobleUrl) {
                connection
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Accept-Encoding", "gzip, deflate, br")
                    .header("sec-ch-ua", "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"121\", \"Google Chrome\";v=\"121\"")
                    .header("sec-ch-ua-mobile", "?0")
                    .header("sec-ch-ua-platform", "\"Windows\"")
                    .header("Sec-Ch-Ua-Full-Version-List", "\"Not_A Brand\";v=\"8.0.0.0\", \"Chromium\";v=\"121.0.6167.140\", \"Google Chrome\";v=\"121.0.6167.140\"")
                    .header("Sec-Fetch-Site", if (barnesNobleStrategyAttempt == 1) "cross-site" else "same-origin")
                    .header("Purpose", if (barnesNobleStrategyAttempt == 1) "prefetch" else "navigate")
                    .referrer(
                        when (barnesNobleStrategyAttempt) {
                            1 -> "https://www.google.com/search?q=barnes+noble+books"
                            2 -> "https://www.barnesandnoble.com/"
                            else -> "https://www.barnesandnoble.com/b/books/_/N-29Z8q8"
                        }
                    )
                simulateBarnesNobleCookies(connection, barnesNobleStrategyAttempt == 1)
            } else if (isBookOutletDomain) {
                when (userAgent) {
                    FIREFOX_USER_AGENT -> {
                        connection.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                        connection.header("Accept-Language", "en-US,en;q=0.5")
                        connection.header("Accept-Encoding", "gzip, deflate")
                    }
                    EDGE_USER_AGENT -> {
                        connection.header("sec-ch-ua", "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"121\", \"Microsoft Edge\";v=\"121\"")
                        connection.header("sec-ch-ua-mobile", "?0")
                        connection.header("sec-ch-ua-platform", "\"Windows\"")
                    }
                    else -> {
                        connection.header("sec-ch-ua", "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"121\", \"Google Chrome\";v=\"121\"")
                        connection.header("sec-ch-ua-mobile", "?0")
                        connection.header("sec-ch-ua-platform", if (attempt % 2 == 0) "\"macOS\"" else "\"Windows\"")
                    }
                }

                when (attempt) {
                    1 -> {
                        connection.referrer("https://www.google.com/search?q=book+outlet+books")
                        connection.header("Sec-Fetch-Site", "cross-site")
                        connection.header("Purpose", "prefetch")
                        simulateBookOutletCookies(connection, isFirstVisit = true)
                    }
                    2 -> {
                        connection.referrer("https://bookoutlet.com/")
                        connection.header("Sec-Fetch-Site", "same-origin")
                        simulateBookOutletCookies(connection, isFirstVisit = false)
                    }
                    else -> {
                        connection.referrer("https://bookoutlet.com/books")
                        connection.header("Sec-Fetch-Site", "same-origin")
                        simulateBookOutletCookies(connection, isFirstVisit = false)
                    }
                }
            }
            
            // Enhanced headers for Better World Books
            if (isBetterWorldBooksDomain) {
                when (userAgent) {
                    FIREFOX_USER_AGENT -> {
                        connection.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                        connection.header("Accept-Language", "en-US,en;q=0.5")
                        connection.header("Accept-Encoding", "gzip, deflate, br")
                    }
                    EDGE_USER_AGENT -> {
                        connection.header("sec-ch-ua", "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"121\", \"Microsoft Edge\";v=\"121\"")
                        connection.header("sec-ch-ua-mobile", "?0")
                        connection.header("sec-ch-ua-platform", "\"Windows\"")
                    }
                    else -> {
                        connection.header("sec-ch-ua", "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"121\", \"Google Chrome\";v=\"121\"")
                        connection.header("sec-ch-ua-mobile", "?0")
                        connection.header("sec-ch-ua-platform", "\"macOS\"")
                    }
                }
                
                // Add more realistic browser headers for Better World Books
                connection
                    .header("Sec-Ch-Ua-Full-Version-List", "\"Not_A Brand\";v=\"8.0.0.0\", \"Chromium\";v=\"121.0.6167.139\", \"Google Chrome\";v=\"121.0.6167.139\"")
                    .header("Sec-Ch-Viewport-Width", "1920")
                    .header("Sec-Ch-Viewport-Height", "1080")
                    .header("Viewport-Width", "1920")
                    .header("Device-Memory", "8")
                    .header("Downlink", "10")
                    .header("ECT", "4g")
                    .header("RTT", "50")
                    .header("Save-Data", "off")
                
                // Simulate Better World Books session state and cookies
                when (attempt) {
                    1 -> {
                        connection.referrer("https://www.google.com/search?q=better+world+books")
                        connection.header("Sec-Fetch-Site", "cross-site")
                        connection.header("Purpose", "prefetch")
                        simulateBetterWorldBooksCookies(connection, isFirstVisit = true)
                    }
                    2 -> {
                        connection.referrer("https://www.betterworldbooks.com/")
                        connection.header("Sec-Fetch-Site", "same-origin")
                        simulateBetterWorldBooksCookies(connection, isFirstVisit = false)
                    }
                    else -> {
                        connection.referrer("https://www.betterworldbooks.com/books")
                        connection.header("Sec-Fetch-Site", "same-origin")
                        simulateBetterWorldBooksCookies(connection, isFirstVisit = false)
                    }
                }
            } else if (isWorldOfBooksDomain) {
                // Simulate World of Books session state and cookies
                when (attempt) {
                    1 -> {
                        connection.referrer("https://www.google.com/search?q=world+of+books")
                        connection.header("Sec-Fetch-Site", "cross-site")
                        simulateWorldOfBooksCookies(connection, isFirstVisit = true)
                    }
                    2 -> {
                        connection.referrer("https://www.worldofbooks.com/")
                        connection.header("Sec-Fetch-Site", "same-origin")
                        simulateWorldOfBooksCookies(connection, isFirstVisit = false)
                    }
                    else -> {
                        connection.referrer("https://www.worldofbooks.com/products")
                        connection.header("Sec-Fetch-Site", "same-origin")
                        simulateWorldOfBooksCookies(connection, isFirstVisit = false)
                    }
                }
            } else if (isAmazonDomain) {
                // Enhanced headers for Amazon
                when (userAgent) {
                    FIREFOX_USER_AGENT -> {
                        connection.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                        connection.header("Accept-Language", "en-US,en;q=0.5")
                        connection.header("Accept-Encoding", "gzip, deflate, br")
                    }
                    EDGE_USER_AGENT -> {
                        connection.header("sec-ch-ua", "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"121\", \"Microsoft Edge\";v=\"121\"")
                        connection.header("sec-ch-ua-mobile", "?0")
                        connection.header("sec-ch-ua-platform", "\"Windows\"")
                    }
                    else -> {
                        connection.header("sec-ch-ua", "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"121\", \"Google Chrome\";v=\"121\"")
                        connection.header("sec-ch-ua-mobile", "?0")
                        connection.header("sec-ch-ua-platform", "\"Windows\"")
                    }
                }
                
                // Simulate Amazon session state and cookies
                when (attempt) {
                    1 -> {
                        connection.referrer("https://www.google.com/search?q=books")
                        connection.header("Sec-Fetch-Site", "cross-site")
                        simulateAmazonCookies(connection, isFirstVisit = true)
                    }
                    2 -> {
                        connection.referrer("https://www.amazon.com/")
                        connection.header("Sec-Fetch-Site", "same-origin")
                        simulateAmazonCookies(connection, isFirstVisit = false)
                    }
                    else -> {
                        connection.referrer("https://www.amazon.com/books")
                        connection.header("Sec-Fetch-Site", "same-origin")
                        simulateAmazonCookies(connection, isFirstVisit = false)
                    }
                }
            } else {
                connection.header("sec-ch-ua", "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"121\", \"Google Chrome\";v=\"121\"")
                connection.header("sec-ch-ua-mobile", "?0")
                connection.header("sec-ch-ua-platform", if (attempt % 2 == 0) "\"macOS\"" else "\"Windows\"")
                
                // Vary referrer based on attempt to simulate different traffic patterns
                when (attempt) {
                    1 -> connection.referrer("https://www.google.com/search?q=book")
                    2 -> connection.referrer("https://www.bing.com/")
                    else -> connection.referrer("https://duckduckgo.com/")
                }
            }
        } else {
            connection
                .userAgent(DESKTOP_USER_AGENT)
                .timeout(DEFAULT_CONNECTION_TIMEOUT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")
        }

        // Add a small random delay before making the request to further randomize timing
        if (isProblematicSite) {
            val maxPreDelay = when {
                isBarnesNobleUrl -> 1800L
                isBookOutletDomain -> 800L // Reduced delay for Book Outlet
                isBetterWorldBooksDomain -> 1200L // Longer delay for BWB
                isWorldOfBooksDomain -> 1000L // Standard delay for World of Books
                isAmazonDomain -> 600L // Short delay for Amazon
                else -> 1000L
            }
            val minPreDelay = when {
                isBarnesNobleUrl -> 600L
                isBookOutletDomain -> 300L // Reduced minimum delay for Book Outlet
                isBetterWorldBooksDomain -> 600L // Longer minimum delay for BWB
                isWorldOfBooksDomain -> 400L // Standard minimum delay for World of Books
                isAmazonDomain -> 100L // Short minimum delay for Amazon
                else -> 200L
            }
            val preRequestDelay = Random.nextLong(minPreDelay, maxPreDelay)
            Log.d("BookTracker", "WebScraping: Adding pre-request delay of ${preRequestDelay}ms")
            delay(preRequestDelay.milliseconds)
        }
        
        connection.get()
    }
    
    private fun isProblematicSite(url: String): Boolean {
        val host = extractHost(url) ?: return false
        return PROBLEMATIC_SITES.any { site -> host.contains(site) }
    }
    
    private fun isBarnesAndNoble(url: String): Boolean {
        val host = extractHost(url) ?: return false
        return host.contains("barnesandnoble.com") || host.contains("bn.com")
    }

    private fun isBetterWorldBooksUrl(url: String): Boolean {
        val host = extractHost(url) ?: return false
        return host.contains("betterworldbooks.com")
    }

    private fun isBookOutletUrl(url: String): Boolean {
        val host = extractHost(url) ?: return false
        return host.contains("bookoutlet.com")
    }

    private fun isWorldOfBooksUrl(url: String): Boolean {
        val host = extractHost(url) ?: return false
        return host.contains("worldofbooks.com")
    }

    private fun isAmazonUrl(url: String): Boolean {
        val host = extractHost(url) ?: return false
        return host.contains("amazon.") || host.contains("a.co")
    }

    private fun extractHost(url: String): String? {
        if (url.isBlank()) {
            return null
        }
        val normalizedUrl = if (url.startsWith("http://") || url.startsWith("https://")) {
            url
        } else {
            "https://$url"
        }
        return try {
            URL(normalizedUrl).host.lowercase(Locale.ROOT)
        } catch (_: Exception) {
            null
        }
    }
    
    private fun cleanBarnesNobleUrl(url: String): String {
        var cleanedUrl = url.trim()
        if (cleanedUrl.isEmpty()) {
            return cleanedUrl
        }

        if (!cleanedUrl.startsWith("http://") && !cleanedUrl.startsWith("https://")) {
            cleanedUrl = "https://$cleanedUrl"
        }
        cleanedUrl = cleanedUrl.replaceFirst("http://", "https://")

        return try {
            val parsed = URL(cleanedUrl)
            val normalizedHost = when {
                parsed.host.equals("www.barnesandnoble.com", ignoreCase = true) -> "www.barnesandnoble.com"
                parsed.host.equals("barnesandnoble.com", ignoreCase = true) -> "www.barnesandnoble.com"
                parsed.host.equals("www.bn.com", ignoreCase = true) -> "www.barnesandnoble.com"
                parsed.host.equals("bn.com", ignoreCase = true) -> "www.barnesandnoble.com"
                parsed.host.startsWith("www.", ignoreCase = true) -> parsed.host
                else -> "www.${parsed.host}"
            }

            val path = parsed.file.ifBlank { "/" }
            val normalizedUrl = "https://$normalizedHost$path"
            Log.d("BookTracker", "WebScraping: Cleaned Barnes & Noble URL: $normalizedUrl")
            normalizedUrl
        } catch (e: Exception) {
            Log.w("BookTracker", "WebScraping: Failed to normalize Barnes & Noble URL: ${e.message}")
            cleanedUrl
        }
    }

    private suspend fun establishBarnesNobleSession() {
        try {
            Log.d("BookTracker", "WebScraping: Visiting Barnes & Noble homepage to establish session...")

            val homepageConnection = Jsoup.connect("https://www.barnesandnoble.com/")
                .followRedirects(true)
                .ignoreHttpErrors(true)
                .userAgent(FIREFOX_USER_AGENT)
                .timeout(BN_READ_TIMEOUT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Accept-Encoding", "gzip, deflate, br")
                .header("DNT", "1")
                .header("Connection", "keep-alive")
                .header("Upgrade-Insecure-Requests", "1")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")
                .header("Sec-Fetch-User", "?1")
                .header("Cache-Control", "max-age=0")
                .referrer("https://www.google.com/")

            simulateBarnesNobleCookies(homepageConnection, isFirstVisit = true)

            delay(Random.nextLong(500, 1200).milliseconds)

            val homepageDoc = homepageConnection.get()
            Log.d("BookTracker", "WebScraping: Successfully visited Barnes & Noble homepage (title='${homepageDoc.title()}')")

            val responseCookies = homepageConnection.response().cookies()
            if (responseCookies.isNotEmpty()) {
                responseCookies.forEach { (key, value) ->
                    sessionCookies["bn_$key"] = value
                }
            }

            delay(Random.nextLong(700, 1500).milliseconds)

        } catch (e: Exception) {
            Log.w("BookTracker", "WebScraping: Failed to establish Barnes & Noble session: ${e.message}")
            throw e
        }
    }

    private fun simulateBarnesNobleCookies(connection: Connection, isFirstVisit: Boolean) {
        val sessionId = sessionCookies.getOrPut("bn_session") {
            "BN${System.currentTimeMillis()}${Random.nextInt(1000, 9999)}"
        }

        val cookies = mutableMapOf<String, String>()

        if (isFirstVisit) {
            cookies["_ga"] = "GA1.1.${Random.nextLong(100000000, 999999999)}.${System.currentTimeMillis() / 1000}"
            cookies["_gid"] = "GA1.1.${Random.nextLong(100000000, 999999999)}"
            cookies["_gcl_au"] = "1.${System.currentTimeMillis()}.${Random.nextLong(100000000, 999999999)}"
            cookies["bn_session_data"] = sessionId
            cookies["bn_locale"] = "en_US"
            cookies["bn_country"] = "US"
            cookies["bn_currency"] = "USD"
            cookies["bn_consent"] = "true"
        } else {
            val existingGa = sessionCookies.getOrPut("bn_ga") {
                "GA1.1.${Random.nextLong(100000000, 999999999)}.${System.currentTimeMillis() / 1000}"
            }
            cookies["_ga"] = existingGa
            cookies["_gid"] = "GA1.1.${Random.nextLong(100000000, 999999999)}"
            cookies["_gcl_au"] = sessionCookies.getOrPut("bn_gcl") {
                "1.${System.currentTimeMillis()}.${Random.nextLong(100000000, 999999999)}"
            }
            cookies["bn_session_data"] = sessionId
            cookies["bn_user_type"] = "returning"
            cookies["bn_locale"] = "en_US"
            cookies["bn_country"] = "US"
            cookies["bn_currency"] = "USD"
            cookies["bn_consent"] = "true"
        }

        cookies["bnVisitorId"] = sessionCookies.getOrPut("bn_visitor") {
            "BNV${Random.nextLong(1000000000000L, 9999999999999L)}"
        }
        cookies["tracking_preference"] = "accepted"
        cookies["cookie-consent"] = "true"

        val realCookies = sessionCookies
            .filter { it.key.startsWith("bn_") }
            .mapKeys { it.key.removePrefix("bn_") }
        if (realCookies.isNotEmpty()) {
            cookies.putAll(realCookies)
        }

        cookies.forEach { (key, value) ->
            when (key) {
                "_ga" -> sessionCookies["bn_ga"] = value
                "_gid" -> sessionCookies["bn_gid"] = value
                "_gcl_au" -> sessionCookies["bn_gcl"] = value
                "bn_session_data" -> sessionCookies["bn_session"] = value
                "bnVisitorId" -> sessionCookies["bn_visitor"] = value
            }
        }

        connection.cookies(cookies)
    }
    
    private suspend fun establishBookOutletSession() {
        try {
            Log.d("BookTracker", "WebScraping: Visiting Book Outlet homepage to establish session...")
            
            val homepageConnection = Jsoup.connect("https://bookoutlet.com/")
                .followRedirects(true)
                .ignoreHttpErrors(true)
                .userAgent(DESKTOP_USER_AGENT)
                .timeout(15000)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Accept-Encoding", "gzip, deflate")
                .header("DNT", "1")
                .header("Connection", "keep-alive")
                .header("Upgrade-Insecure-Requests", "1")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")
                .header("Sec-Fetch-User", "?1")
                .header("Cache-Control", "max-age=0")
                .referrer("https://www.google.com/search?q=book+outlet")
            
            // Add basic first-visit cookies
            simulateBookOutletCookies(homepageConnection, isFirstVisit = true)
            
            // Small delay before making request
            delay(Random.nextLong(200, 600).milliseconds)
            
            val homepageDoc = homepageConnection.get()
            Log.d("BookTracker", "WebScraping: Successfully visited Book Outlet homepage (title='${homepageDoc.title()}')")
            
            // Extract and store real cookies from homepage response
            val responseCookies = homepageConnection.response().cookies()
            if (responseCookies.isNotEmpty()) {
                responseCookies.forEach { (key, value) ->
                    sessionCookies[key] = value
                }
            }
            
            // Simulate page load time with JavaScript execution
            delay(Random.nextLong(800, 1500).milliseconds)
            
        } catch (e: Exception) {
            Log.w("BookTracker", "WebScraping: Failed to establish session: ${e.message}")
            throw e
        }
    }
    
    private fun cleanBookOutletUrl(url: String): String {
        var cleanedUrl = url
        
        // For Book Outlet, trailing letters like "B" are actually part of the URL structure
        // Only clean up obvious formatting issues
        
        // Ensure proper URL format
        if (!cleanedUrl.startsWith("https://")) {
            cleanedUrl = "https://$cleanedUrl"
        }
        
        // Remove any trailing whitespace
        cleanedUrl = cleanedUrl.trim()
        
        Log.d("BookTracker", "WebScraping: Book Outlet URL is valid as-is: $cleanedUrl")
        
        return cleanedUrl
    }
    
    private fun simulateBookOutletCookies(connection: Connection, isFirstVisit: Boolean) {
        val sessionId = sessionCookies.getOrPut("bookoutlet_session") {
            // Generate a realistic session ID
            "${System.currentTimeMillis()}_${Random.nextInt(100000, 999999)}"
        }
        
        val cookies = mutableMapOf<String, String>()
        
        if (isFirstVisit) {
            // First visit cookies
            cookies["_ga"] = "GA1.2.${Random.nextLong(100000000, 999999999)}.${System.currentTimeMillis() / 1000}"
            cookies["_gid"] = "GA1.2.${Random.nextLong(100000000, 999999999)}"
            cookies["session_id"] = sessionId
            cookies["first_visit"] = "true"
            cookies["currency"] = "USD"
            cookies["country"] = "US"
            cookies["timezone"] = "America/New_York"
        } else {
            // Return visitor cookies
            val existingGa = sessionCookies.getOrPut("_ga") {
                "GA1.2.${Random.nextLong(100000000, 999999999)}.${System.currentTimeMillis() / 1000}"
            }
            
            cookies["_ga"] = existingGa
            cookies["_gid"] = "GA1.2.${Random.nextLong(100000000, 999999999)}"
            cookies["session_id"] = sessionId
            cookies["returning_visitor"] = "true"
            cookies["currency"] = "USD"
            cookies["country"] = "US"
            cookies["timezone"] = "America/New_York"
            cookies["last_page"] = "/books"
            cookies["pages_viewed"] = "${Random.nextInt(3, 15)}"
        }
        
        // Add common tracking cookies
        cookies["_fbp"] = "fb.1.${System.currentTimeMillis()}.${Random.nextLong(100000000, 999999999)}"
        cookies["_hjid"] = "${Random.nextLong(10000000, 99999999)}"
        cookies["visitor_id"] = "${Random.nextLong(1000000000, 9999999999)}"
        
        // Merge with real cookies from session if available
        val realCookies = sessionCookies.filter { it.key.isNotBlank() }
        if (realCookies.isNotEmpty()) {
            cookies.putAll(realCookies)
        }
        
        // Store some cookies for next request
        cookies.forEach { (key, value) ->
            if (key.startsWith("_ga") || key == "session_id") {
                sessionCookies[key] = value
            }
        }
        
        // Apply cookies to connection
        connection.cookies(cookies)
    }
    
    private suspend fun establishBetterWorldBooksSession() {
        try {
            Log.d("BookTracker", "WebScraping: Visiting Better World Books homepage to establish session...")
            
            val homepageConnection = Jsoup.connect("https://www.betterworldbooks.com/")
                .followRedirects(true)
                .ignoreHttpErrors(true)
                .userAgent(FIREFOX_USER_AGENT)
                .timeout(20000)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")
                .header("Accept-Encoding", "gzip, deflate, br")
                .header("DNT", "1")
                .header("Connection", "keep-alive")
                .header("Upgrade-Insecure-Requests", "1")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "cross-site")
                .header("Sec-Fetch-User", "?1")
                .header("Cache-Control", "max-age=0")
                .referrer("https://www.google.com/search?q=better+world+books")
            
            // Add basic first-visit cookies
            simulateBetterWorldBooksCookies(homepageConnection, isFirstVisit = true)
            
            // Longer delay before making request
            delay(Random.nextLong(800, 1500).milliseconds)
            
            val homepageDoc = homepageConnection.get()
            Log.d("BookTracker", "WebScraping: Successfully visited Better World Books homepage (title='${homepageDoc.title()}')")
            
            // Extract and store real cookies from homepage response
            val responseCookies = homepageConnection.response().cookies()
            if (responseCookies.isNotEmpty()) {
                responseCookies.forEach { (key, value) ->
                    sessionCookies["bwb_$key"] = value
                }
            }
            
            // Simulate longer page load time
            delay(Random.nextLong(1200, 2500).milliseconds)
            
        } catch (e: Exception) {
            Log.w("BookTracker", "WebScraping: Failed to establish BWB session: ${e.message}")
            throw e
        }
    }
    
    private fun cleanBetterWorldBooksUrl(url: String): String {
        var cleanedUrl = url
        
        // Ensure proper URL format
        if (!cleanedUrl.startsWith("https://")) {
            cleanedUrl = "https://$cleanedUrl"
        }
        
        // Remove any trailing whitespace
        cleanedUrl = cleanedUrl.trim()
        
        // Ensure proper www subdomain
        cleanedUrl = cleanedUrl.replace("https://betterworldbooks.com", "https://www.betterworldbooks.com")
        
        Log.d("BookTracker", "WebScraping: Cleaned BWB URL: $cleanedUrl")
        
        return cleanedUrl
    }
    
    private fun simulateBetterWorldBooksCookies(connection: Connection, isFirstVisit: Boolean) {
        val sessionId = sessionCookies.getOrPut("bwb_session") {
            // Generate a realistic session ID
            "BWB_${System.currentTimeMillis()}_${Random.nextInt(100000, 999999)}"
        }
        
        val cookies = mutableMapOf<String, String>()
        
        if (isFirstVisit) {
            // First visit cookies
            cookies["_ga"] = "GA1.2.${Random.nextLong(100000000, 999999999)}.${System.currentTimeMillis() / 1000}"
            cookies["_gid"] = "GA1.2.${Random.nextLong(100000000, 999999999)}"
            cookies["_gat"] = "1"
            cookies["session_id"] = sessionId
            cookies["first_visit"] = "1"
            cookies["currency"] = "USD"
            cookies["country_code"] = "US"
            cookies["locale"] = "en_US"
            cookies["visitor_id"] = "${Random.nextLong(1000000000, 9999999999)}"
        } else {
            // Return visitor cookies
            val existingGa = sessionCookies.getOrPut("bwb_ga") {
                "GA1.2.${Random.nextLong(100000000, 999999999)}.${System.currentTimeMillis() / 1000}"
            }
            
            cookies["_ga"] = existingGa
            cookies["_gid"] = "GA1.2.${Random.nextLong(100000000, 999999999)}"
            cookies["session_id"] = sessionId
            cookies["returning_visitor"] = "1"
            cookies["currency"] = "USD"
            cookies["country_code"] = "US"
            cookies["locale"] = "en_US"
            cookies["last_page"] = "/books"
            cookies["pages_viewed"] = "${Random.nextInt(2, 12)}"
            cookies["visitor_id"] = sessionCookies.getOrPut("bwb_visitor") {
                "${Random.nextLong(1000000000, 9999999999)}"
            }
        }
        
        // Add tracking cookies specific to BWB
        cookies["_fbp"] = "fb.1.${System.currentTimeMillis()}.${Random.nextLong(100000000, 999999999)}"
        cookies["_hjFirstSeen"] = "1"
        cookies["_hjIncludedInPageviewSample"] = "1"
        cookies["_hjid"] = "${Random.nextLong(10000000, 99999999)}"
        cookies["OptanonAlertBoxClosed"] = "${System.currentTimeMillis()}"
        cookies["OptanonConsent"] = "isIABGlobal=false&datestamp=${System.currentTimeMillis()}"
        
        // Merge with real cookies from session if available
        val realCookies = sessionCookies.filter { it.key.startsWith("bwb_") }.mapKeys { it.key.removePrefix("bwb_") }
        if (realCookies.isNotEmpty()) {
            cookies.putAll(realCookies)
        }
        
        // Store some cookies for next request
        cookies.forEach { (key, value) ->
            if (key.startsWith("_ga") || key == "session_id" || key == "visitor_id") {
                sessionCookies["bwb_$key"] = value
            }
        }
        
        // Apply cookies to connection
        connection.cookies(cookies)
    }
    
    private suspend fun establishWorldOfBooksSession() {
        try {
            Log.d("BookTracker", "WebScraping: Visiting World of Books homepage to establish session...")
            
            val homepageConnection = Jsoup.connect("https://www.worldofbooks.com/")
                .followRedirects(true)
                .ignoreHttpErrors(true)
                .userAgent(FIREFOX_USER_AGENT)
                .timeout(18000)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")
                .header("Accept-Encoding", "gzip, deflate, br")
                .header("DNT", "1")
                .header("Connection", "keep-alive")
                .header("Upgrade-Insecure-Requests", "1")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "cross-site")
                .header("Sec-Fetch-User", "?1")
                .header("Cache-Control", "max-age=0")
                .referrer("https://www.google.com/search?q=world+of+books")
            
            // Add basic first-visit cookies
            simulateWorldOfBooksCookies(homepageConnection, isFirstVisit = true)
            
            // Standard delay before making request
            delay(Random.nextLong(600, 1200).milliseconds)
            
            val homepageDoc = homepageConnection.get()
            Log.d("BookTracker", "WebScraping: Successfully visited World of Books homepage (title='${homepageDoc.title()}')")
            
            // Extract and store real cookies from homepage response
            val responseCookies = homepageConnection.response().cookies()
            if (responseCookies.isNotEmpty()) {
                responseCookies.forEach { (key, value) ->
                    sessionCookies["wob_$key"] = value
                }
            }
            
            // Simulate page load time with JavaScript execution
            delay(Random.nextLong(1000, 2000).milliseconds)
            
        } catch (e: Exception) {
            Log.w("BookTracker", "WebScraping: Failed to establish WOB session: ${e.message}")
            throw e
        }
    }
    
    private fun cleanWorldOfBooksUrl(url: String): String {
        var cleanedUrl = url
        
        // Ensure proper URL format
        if (!cleanedUrl.startsWith("https://")) {
            cleanedUrl = "https://$cleanedUrl"
        }
        
        // Remove any trailing whitespace
        cleanedUrl = cleanedUrl.trim()
        
        // Ensure proper www subdomain (World of Books uses www)
        cleanedUrl = cleanedUrl.replace("https://worldofbooks.com", "https://www.worldofbooks.com")
        
        Log.d("BookTracker", "WebScraping: Cleaned WOB URL: $cleanedUrl")
        
        return cleanedUrl
    }
    
    private fun cleanAmazonUrl(url: String): String {
        try {
            var cleanedUrl = url
            
            Log.d("BookTracker", "WebScraping: Cleaning Amazon URL: $url")
            
            // Ensure proper URL format
            if (!cleanedUrl.startsWith("https://") && !cleanedUrl.startsWith("http://")) {
                cleanedUrl = "https://$cleanedUrl"
            }
            
            // Remove any trailing whitespace
            cleanedUrl = cleanedUrl.trim()
            
            // Parse the URL to extract essential components
            val urlObj = URL(cleanedUrl)
            val path = urlObj.path
            
            // Extract the product ID (ASIN) from various Amazon URL patterns
            val asinRegex = Regex("/(dp|gp/product|product)/([A-Z0-9]{10})")
            val asinMatch = asinRegex.find(path)
            
            if (asinMatch != null) {
                val asin = asinMatch.groupValues[2]
                
                // Rebuild clean URL with just the essential parts
                val protocol = if (urlObj.protocol == "http") "http" else "https"
                val host = urlObj.host
                
                // Use /dp/ format as it's the most standard
                cleanedUrl = "$protocol://$host/dp/$asin"
                
                Log.d("BookTracker", "WebScraping: Cleaned Amazon URL from '$url' to '$cleanedUrl'")
            } else {
                // If we can't extract ASIN, just remove query parameters but keep the path
                cleanedUrl = "${urlObj.protocol}://${urlObj.host}${urlObj.path}"
                Log.d("BookTracker", "WebScraping: Removed query parameters from Amazon URL: '$cleanedUrl'")
            }
            
            return cleanedUrl
            
        } catch (e: Exception) {
            Log.w("BookTracker", "WebScraping: Failed to clean Amazon URL '$url', using original: ${e.message}")
            return url
        }
    }
    
    private fun simulateWorldOfBooksCookies(connection: Connection, isFirstVisit: Boolean) {
        val sessionId = sessionCookies.getOrPut("wob_session") {
            // Generate a realistic session ID
            "WOB_${System.currentTimeMillis()}_${Random.nextInt(100000, 999999)}"
        }
        
        val cookies = mutableMapOf<String, String>()
        
        if (isFirstVisit) {
            // First visit cookies for World of Books (Shopify-based)
            cookies["_ga"] = "GA1.2.${Random.nextLong(100000000, 999999999)}.${System.currentTimeMillis() / 1000}"
            cookies["_gid"] = "GA1.2.${Random.nextLong(100000000, 999999999)}"
            cookies["session_id"] = sessionId
            cookies["shopify_y"] = "${Random.nextInt(10000000, 99999999)}"
            cookies["shopify_s"] = "${Random.nextInt(10000000, 99999999)}"
            cookies["shopify_sa_t"] = "${System.currentTimeMillis()}"
            cookies["shopify_sa_p"] = ""
            cookies["currency"] = "USD"
            cookies["country_code"] = "US" 
            cookies["locale"] = "en"
            cookies["cart"] = "${Random.nextLong(100000000000L, 999999999999L)}"
            cookies["visitor_id"] = "${Random.nextLong(1000000000, 9999999999)}"
        } else {
            // Return visitor cookies
            val existingGa = sessionCookies.getOrPut("wob_ga") {
                "GA1.2.${Random.nextLong(100000000, 999999999)}.${System.currentTimeMillis() / 1000}"
            }
            
            cookies["_ga"] = existingGa
            cookies["_gid"] = "GA1.2.${Random.nextLong(100000000, 999999999)}"
            cookies["session_id"] = sessionId
            cookies["shopify_y"] = sessionCookies.getOrPut("wob_shopify_y") { "${Random.nextInt(10000000, 99999999)}" }
            cookies["shopify_s"] = "${Random.nextInt(10000000, 99999999)}"
            cookies["shopify_sa_t"] = "${System.currentTimeMillis()}"
            cookies["shopify_sa_p"] = "/products"
            cookies["currency"] = "USD"
            cookies["country_code"] = "US"
            cookies["locale"] = "en"
            cookies["cart"] = sessionCookies.getOrPut("wob_cart") { "${Random.nextLong(100000000000L, 999999999999L)}" }
            cookies["last_page"] = "/products"
            cookies["pages_viewed"] = "${Random.nextInt(2, 10)}"
            cookies["visitor_id"] = sessionCookies.getOrPut("wob_visitor") {
                "${Random.nextLong(1000000000, 9999999999)}"
            }
        }
        
        // Add tracking cookies specific to World of Books/Shopify
        cookies["_fbp"] = "fb.1.${System.currentTimeMillis()}.${Random.nextLong(100000000, 999999999)}"
        cookies["_hjFirstSeen"] = "1"
        cookies["_hjIncludedInPageviewSample"] = "1"
        cookies["_hjid"] = "${Random.nextLong(10000000, 99999999)}"
        cookies["_shopify_sa_t"] = "${System.currentTimeMillis()}"
        cookies["_shopify_sa_p"] = if (isFirstVisit) "" else "/products"
        cookies["_landing_page"] = if (isFirstVisit) "%2F" else "%2Fproducts"
        cookies["_orig_referrer"] = if (isFirstVisit) "https%3A//www.google.com/" else "https%3A//www.worldofbooks.com/"
        cookies["_secure_session_id"] = "${Random.nextLong(1000000000000000L, 9999999999999999L)}"
        
        // GDPR/Cookie consent cookies
        cookies["cookieconsent_status"] = "allow"
        cookies["CookieConsentPolicy"] = "0:1"
        cookies["LSKey-c" + '$' + "CookieConsentPolicy"] = "0:1"
        
        // Merge with real cookies from session if available
        val realCookies = sessionCookies.filter { it.key.startsWith("wob_") }.mapKeys { it.key.removePrefix("wob_") }
        if (realCookies.isNotEmpty()) {
            cookies.putAll(realCookies)
        }
        
        // Store some cookies for next request
        cookies.forEach { (key, value) ->
            if (key.startsWith("_ga") || key == "session_id" || key == "visitor_id" || 
                key.startsWith("shopify_") || key == "cart") {
                sessionCookies["wob_$key"] = value
            }
        }
        
        // Apply cookies to connection
        connection.cookies(cookies)
    }
    
    private suspend fun establishAmazonSession() {
        try {
            Log.d("BookTracker", "WebScraping: Visiting Amazon homepage to establish session...")
            
            val homepageConnection = Jsoup.connect("https://www.amazon.com/")
                .followRedirects(true)
                .ignoreHttpErrors(true)
                .userAgent(DESKTOP_USER_AGENT)
                .timeout(15000)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Accept-Encoding", "gzip, deflate, br")
                .header("DNT", "1")
                .header("Connection", "keep-alive")
                .header("Upgrade-Insecure-Requests", "1")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "cross-site")
                .header("Sec-Fetch-User", "?1")
                .header("Cache-Control", "max-age=0")
                .referrer("https://www.google.com/search?q=books")
            
            // Add basic first-visit cookies
            simulateAmazonCookies(homepageConnection, isFirstVisit = true)
            
            // Delay before making request
            delay(Random.nextLong(300, 800).milliseconds)
            
            val homepageDoc = homepageConnection.get()
            Log.d("BookTracker", "WebScraping: Successfully visited Amazon homepage (title='${homepageDoc.title()}')")
            
            // Extract and store real cookies from homepage response
            val responseCookies = homepageConnection.response().cookies()
            if (responseCookies.isNotEmpty()) {
                responseCookies.forEach { (key, value) ->
                    sessionCookies["amz_$key"] = value
                }
            }
            
            // Simulate page load time with JavaScript execution
            delay(Random.nextLong(800, 1500).milliseconds)
            
        } catch (e: Exception) {
            Log.w("BookTracker", "WebScraping: Failed to establish Amazon session: ${e.message}")
            throw e
        }
    }
    
    private fun simulateAmazonCookies(connection: Connection, isFirstVisit: Boolean) {
        val cookies = mutableMapOf<String, String>()
        
        // Basic Amazon session cookies
        if (isFirstVisit) {
            // First-time visitor cookies
            cookies["session-id"] = "${Random.nextLong(100000000000000L, 999999999999999L)}-${Random.nextLong(1000000L, 9999999L)}"
            cookies["session-id-time"] = "${System.currentTimeMillis() / 1000}l"
            cookies["i18n-prefs"] = "USD"
            cookies["lc-main"] = "en_US"
            cookies["sp-cdn"] = "L5Z9:US"
            cookies["skin"] = "noskin"
        } else {
            // Returning visitor cookies
            cookies["session-id"] = "${Random.nextLong(100000000000000L, 999999999999999L)}-${Random.nextLong(1000000L, 9999999L)}"
            cookies["ubid-main"] = "${Random.nextLong(100000000L, 999999999L)}-${Random.nextLong(1000000L, 9999999L)}"
            cookies["x-main"] = "\"${Random.nextLong(10000000000000L, 99999999999999L)}\""
            cookies["at-main"] = "Atza|${generateRandomString(80)}"
        }
        
        // Geographic and preference cookies
        cookies["lc-main"] = "en_US"
        cookies["i18n-prefs"] = "USD"
        cookies["sp-cdn"] = "L5Z9:US"
        
        // Browser capability cookies
        cookies["csm-hit"] = "tb:${generateRandomString(20)}+s-${generateRandomString(13)}|${System.currentTimeMillis()}"
        
        // GDPR/Cookie consent cookies
        cookies["session-token"] = "\"${generateRandomString(300)}\""
        
        // Merge with real cookies from session if available
        val realCookies = sessionCookies.filter { it.key.startsWith("amz_") }.mapKeys { it.key.removePrefix("amz_") }
        if (realCookies.isNotEmpty()) {
            cookies.putAll(realCookies)
        }
        
        // Store some cookies for next request
        cookies.forEach { (key, value) ->
            if (key.startsWith("session") || key == "ubid-main" || key == "x-main" || 
                key.startsWith("at-") || key == "lc-main") {
                sessionCookies["amz_$key"] = value
            }
        }
        
        // Apply cookies to connection
        connection.cookies(cookies)
    }
    
    private fun generateRandomString(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="
        return (1..length)
            .map { chars.random() }
            .joinToString("")
    }
    
    sealed class ScrapingResult {
        data class Success(val bookInfo: ParsedBookInfo) : ScrapingResult()
        data class Error(val message: String) : ScrapingResult()
        @kotlinx.parcelize.Parcelize
        data class MultipleSellerOptions(
            val sellerOptions: List<SellerOption>,
            val bookTitle: String,
            val bookAuthor: String?,
            val bookIsbn: Pair<String?, String?>,
            val coverImageUrl: String?,
            val originalUrl: String
        ) : ScrapingResult(), android.os.Parcelable
    }
} 
