package com.booktracker.booksidntneed.network

import android.util.Log
import android.os.SystemClock
import com.booktracker.booksidntneed.utils.ErrorReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Progress callback interface for granular progress tracking
interface ScrapingProgressCallback {
    fun onTaskStarted(task: String, progress: Int = 0)
    fun onTaskProgress(task: String, progress: Int)
    fun onTaskCompleted(task: String)
    fun onError(task: String, error: String)
}

class WebScrapingService {
    private val session = CookieSession()
    private val sessionLocks = ConcurrentHashMap<String, Mutex>()
    private val sessionEstablishedAt = ConcurrentHashMap<String, Long>()

    suspend fun scrapeBookInfo(url: String, progressCallback: ScrapingProgressCallback? = null): ScrapingResult = withContext(Dispatchers.IO) {
        val strategy = StoreRequestStrategyFactory.getStrategy(url)

        try {
            Log.d("BookTracker", "WebScraping: Starting to scrape URL: $url")
            Log.d("BookTracker", "WebScraping: Selected request strategy: ${strategy.javaClass.simpleName}")

            progressCallback?.onTaskStarted("Validating URL", 0)
            if (!isValidUrl(url)) {
                Log.e("BookTracker", "WebScraping: Invalid URL format: $url")
                ErrorReporter.recordException(
                    UrlParseFailureException("Invalid submitted URL format"),
                    "Invalid submitted URL format",
                    buildFailureKeys(
                        source = "web_scraping_invalid_url",
                        originalUrl = url,
                        finalUrl = url,
                        strategy = strategy
                    )
                )
                progressCallback?.onError("Validating URL", "Invalid URL format")
                return@withContext ScrapingResult.Error("Invalid URL format")
            }
            progressCallback?.onTaskCompleted("Validating URL")

            var document = fetchDocumentWithRetry(url, strategy, progressCallback)
            var finalUrl = document.location().ifBlank { strategy.canonicalizeUrl(url) }

            Log.d("BookTracker", "WebScraping: Original URL: $url")
            Log.d("BookTracker", "WebScraping: Final URL after redirects: $finalUrl")

            var retriedWithCanonicalUrl = false
            strategy.retryUrlAfterFetch(document, finalUrl)?.let { retryUrl ->
                Log.d("BookTracker", "WebScraping: ${strategy.storeName} requested a post-fetch retry with URL: $retryUrl")
                try {
                    document = fetchDocumentWithRetry(retryUrl, strategy, null)
                    finalUrl = retryUrl
                    retriedWithCanonicalUrl = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("BookTracker", "WebScraping: Post-fetch retry failed: ${e.message}")
                }
            }

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
                val errorMessage = buildParseErrorMessage(document, finalUrl, bookInfo, retriedWithCanonicalUrl)
                Log.e("BookTracker", "WebScraping: Error - $errorMessage")
                ErrorReporter.recordException(
                    UrlParseFailureException(errorMessage),
                    "Failed to parse submitted URL",
                    buildFailureKeys(
                        source = "web_scraping_parse_failure",
                        originalUrl = url,
                        finalUrl = finalUrl,
                        strategy = strategy,
                        parser = parser,
                        pageTitle = document.select("title").first()?.text().orEmpty()
                    )
                )
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
            val errorMessage = if (strategy is BarnesAndNobleRequestStrategy) {
                "Timeout: Barnes & Noble took too long to respond. Please try again later."
            } else {
                "Timeout: The website took too long to respond. Please try again later."
            }
            progressCallback?.onError("Fetching Document", errorMessage)
            ScrapingResult.Error(errorMessage)
        } catch (e: SocketException) {
            val host = hostFrom(url)
            Log.w("BookTracker", "WebScraping: Connection was interrupted while fetching from $host. Message: ${e.message}")
            progressCallback?.onError("Fetching Document", "Network connection interrupted. Please try again.")
            ScrapingResult.Error("Network connection interrupted. Please try again.")
        } catch (e: SSLException) {
            Log.e("BookTracker", "WebScraping: SSL error", e)
            ErrorReporter.recordException(
                e,
                "Web scraping SSL error",
                mapOf(
                    "source" to "web_scraping_ssl",
                    "store_host" to hostFrom(url)
                )
            )
            progressCallback?.onError("Fetching Document", "Security error: Could not establish secure connection")
            ScrapingResult.Error("Security error: Could not establish secure connection")
        } catch (e: HttpStatusException) {
            val host = runCatching { URL(url).host }.getOrDefault("unknown host")
            Log.w("BookTracker", "WebScraping: HTTP error fetching document from $host. Status=${e.statusCode}, URL=${e.url}", e)
            val errorMessage = when {
                e.statusCode == 404 && strategy is EbayRequestStrategy ->
                    "This eBay listing has ended, was removed, or is no longer available (404)."
                e.statusCode == 404 ->
                    "The page was not found (404). The book may no longer be available at this URL."
                e.statusCode == 403 ->
                    "Access was denied (403). This may be due to bot detection. Please try again later."
                e.statusCode == 503 ->
                    "The website is temporarily unavailable (503). Please try again later."
                else -> "Received an unexpected HTTP error: ${e.statusCode}."
            }
            progressCallback?.onError("Fetching Document", errorMessage)
            ScrapingResult.Error(errorMessage)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("BookTracker", "WebScraping: Unexpected error", e)
            ErrorReporter.recordException(
                e,
                "Unexpected web scraping error",
                mapOf(
                    "source" to "web_scraping_unexpected",
                    "store_host" to hostFrom(url)
                )
            )
            progressCallback?.onError("Unknown", "Unexpected error: ${e.message ?: "Unknown error occurred"}")
            ScrapingResult.Error("Unexpected error: ${e.message ?: "Unknown error occurred"}")
        }
    }

    private fun hostFrom(url: String): String {
        return runCatching { URL(url).host }
            .getOrDefault("unknown")
            .ifBlank { "unknown" }
    }

    private fun buildFailureKeys(
        source: String,
        originalUrl: String,
        finalUrl: String,
        strategy: StoreRequestStrategy,
        parser: BookParser? = null,
        pageTitle: String = ""
    ): Map<String, String> {
        return buildMap {
            put("source", source)
            put("store_host", hostFrom(finalUrl))
            put("original_host", hostFrom(originalUrl))
            put("strategy", strategy.javaClass.simpleName)
            put("store_name", strategy.storeName)
            parser?.let {
                put("parser", it.javaClass.simpleName)
                put("parser_store", it.getStoreName())
            }
            put("url_sample", urlSample(finalUrl))
            if (pageTitle.isNotBlank()) {
                put("page_title", pageTitle)
            }
        }
    }

    private fun urlSample(url: String): String {
        return runCatching {
            val parsed = URL(RequestStrategyUtils.ensureHttps(url))
            "${parsed.host}${parsed.path}".ifBlank { "unknown" }
        }.getOrDefault("invalid_url")
    }

    private fun isValidUrl(url: String): Boolean {
        return try {
            val normalizedUrl = RequestStrategyUtils.ensureHttps(url)
            Log.d("BookTracker", "WebScraping: Validating URL: $normalizedUrl")

            val javaUrl = URL(normalizedUrl)
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

    private suspend fun fetchDocumentWithRetry(
        url: String,
        strategy: StoreRequestStrategy,
        progressCallback: ScrapingProgressCallback?
    ): Document {
        val canonicalUrl = strategy.canonicalizeUrl(url)
        if (canonicalUrl != url) {
            Log.d("BookTracker", "WebScraping: Canonicalized URL from '$url' to '$canonicalUrl'")
        }

        ensureSession(strategy, progressCallback)

        progressCallback?.onTaskStarted("Fetching Document", 0)

        val retryPolicy = strategy.retryPolicy()
        var lastException: Exception? = null
        val totalAttempts = retryPolicy.maxAttempts - retryPolicy.startAttempt + 1

        for (attempt in retryPolicy.startAttempt..retryPolicy.maxAttempts) {
            try {
                if (attempt > retryPolicy.startAttempt) {
                    ensureSession(strategy, null)
                }
                Log.d("BookTracker", "WebScraping: Fetch attempt $attempt of ${retryPolicy.maxAttempts} for URL: $canonicalUrl")
                val attemptProgress = ((attempt - retryPolicy.startAttempt + 1) * 100) / totalAttempts
                progressCallback?.onTaskProgress("Fetching Document", attemptProgress)

                if (attempt > retryPolicy.startAttempt) {
                    val randomDelay = retryPolicy.baseDelayMs + Random.nextLong(0, retryPolicy.maxRandomDelayMs)
                    Log.d("BookTracker", "WebScraping: Waiting ${randomDelay}ms before retry...")
                    delay(randomDelay.milliseconds)
                }

                val document = fetchDocument(canonicalUrl, attempt, strategy)
                progressCallback?.onTaskCompleted("Fetching Document")
                return document
            } catch (e: CancellationException) {
                throw e
            } catch (e: SocketTimeoutException) {
                Log.w("BookTracker", "WebScraping: Timeout on attempt $attempt: ${e.message}")
                lastException = e
                if (!retryPolicy.shouldRetry(e, attempt)) {
                    throw e
                }
            } catch (e: HttpStatusException) {
                Log.w("BookTracker", "WebScraping: HTTP ${e.statusCode} on attempt $attempt: ${e.message}")
                lastException = e
                // Missing product/listing pages are not transient. Retrying can
                // replace this useful response with a later bot challenge.
                if (e.statusCode == 404 || !retryPolicy.shouldRetry(e, attempt)) {
                    throw e
                }
            } catch (e: Exception) {
                Log.w("BookTracker", "WebScraping: Exception on attempt $attempt: ${e.message}")
                if (shouldRefreshSession(e)) {
                    sessionEstablishedAt.remove(sessionKey(strategy))
                }
                lastException = e
                if (!retryPolicy.shouldRetry(e, attempt)) {
                    throw e
                }
            }
        }

        throw lastException ?: SocketTimeoutException("All retry attempts failed")
    }

    private suspend fun ensureSession(
        strategy: StoreRequestStrategy,
        progressCallback: ScrapingProgressCallback?
    ) {
        val key = sessionKey(strategy)
        val now = SystemClock.elapsedRealtime()
        val establishedAt = sessionEstablishedAt[key]
        if (establishedAt != null && now - establishedAt < SESSION_TTL_MS) {
            return
        }

        sessionLocks.getOrPut(key) { Mutex() }.withLock {
            val refreshedAt = sessionEstablishedAt[key]
            val lockNow = SystemClock.elapsedRealtime()
            if (refreshedAt != null && lockNow - refreshedAt < SESSION_TTL_MS) {
                return@withLock
            }

            progressCallback?.onTaskStarted("Establishing Session", 0)
            try {
                progressCallback?.onTaskProgress("Establishing Session", 50)
                strategy.establishSession(session)
                sessionEstablishedAt[key] = SystemClock.elapsedRealtime()
                progressCallback?.onTaskProgress("Establishing Session", 100)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("BookTracker", "WebScraping: Failed to establish ${strategy.storeName} session, continuing anyway: ${e.message}")
            } finally {
                progressCallback?.onTaskCompleted("Establishing Session")
            }
        }
    }

    private fun sessionKey(strategy: StoreRequestStrategy): String = strategy.javaClass.name

    private fun shouldRefreshSession(error: Exception): Boolean {
        val message = error.message.orEmpty()
        return message.contains("403") ||
            message.contains("401") ||
            message.contains("blocked", ignoreCase = true) ||
            message.contains("captcha", ignoreCase = true) ||
            message.contains("verification", ignoreCase = true)
    }

    private suspend fun fetchDocument(url: String, attempt: Int, strategy: StoreRequestStrategy): Document = withContext(Dispatchers.IO) {
        val normalizedUrl = RequestStrategyUtils.ensureHttps(url)
        Log.d("BookTracker", "WebScraping: Fetching document from: $normalizedUrl")

        val connection = Jsoup.connect(normalizedUrl)
            .followRedirects(true)
            .ignoreHttpErrors(false)
            .ignoreContentType(false)
            .maxBodySize(2048 * 1024)

        strategy.configureRequest(connection, attempt, session)
        RequestStrategyUtils.delayRandom(strategy.preRequestDelayRangeMs())

        val document = connection.get()
        strategy.blockedPageError(document)?.let { error -> throw IllegalStateException(error) }
        strategy.updateSession(connection.response().cookies(), session)
        document
    }

    private fun buildParseErrorMessage(
        document: Document,
        finalUrl: String,
        bookInfo: ParsedBookInfo?,
        retriedWithCanonicalUrl: Boolean
    ): String {
        val pageTitle = document.select("title").first()?.text().orEmpty()
        val pageText = document.text().lowercase(Locale.ROOT)

        Log.w("BookTracker", "WebScraping: Failed to parse book info")
        Log.w("BookTracker", "WebScraping: Page title: $pageTitle")
        Log.w("BookTracker", "WebScraping: BookInfo was null: ${bookInfo == null}")
        if (bookInfo != null) {
            Log.w("BookTracker", "WebScraping: BookInfo.isValid() = ${bookInfo.isValid()}, Title: '${bookInfo.title}', Author: '${bookInfo.author}'")
        }

        return when {
            pageText.contains("continue shopping") ->
                "This appears to be an Amazon intermediate page. The link may be expired or require manual navigation."
            pageText.contains("page not found") || pageText.contains("404") ->
                "The page was not found. The book may no longer be available at this URL."
            pageText.contains("robot") || pageText.contains("captcha") ->
                "The website is asking for verification. Please try again later or use a different URL."
            pageText.contains("access denied") || pageText.contains("blocked") ->
                "Access was denied by the website. This may be due to bot detection. Please try again later."
            pageTitle.contains("amazon", ignoreCase = true) && !document.select("#productTitle, h1[id*='title']").any() -> {
                if (retriedWithCanonicalUrl) {
                    "Amazon served an intermediate page even after URL cleaning. The link may be invalid or temporarily unavailable."
                } else {
                    "This doesn't appear to be a book product page. Please ensure the URL points to a specific book."
                }
            }
            finalUrl.contains("amazon") && (finalUrl.contains("/gp/") || finalUrl.contains("/ref=") || !finalUrl.contains("/dp/")) -> {
                if (retriedWithCanonicalUrl) {
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
    }

    private class UrlParseFailureException(message: String) : Exception(message)

    companion object {
        private const val SESSION_TTL_MS = 30 * 60 * 1000L
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
