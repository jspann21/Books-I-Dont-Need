package com.booktracker.booksidntneed.network

import android.util.Log
import kotlinx.coroutines.delay
import org.jsoup.Connection
import org.jsoup.nodes.Document
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

data class RetryPolicy(
    val startAttempt: Int = 1,
    val maxAttempts: Int = 3,
    val baseDelayMs: Long = 5000L,
    val maxRandomDelayMs: Long = 3000L,
    val retryHttp403: Boolean = false,
    val retryAllExceptions: Boolean = false
) {
    fun shouldRetry(exception: Exception, attempt: Int): Boolean {
        if (attempt >= maxAttempts) {
            return false
        }
        return exception is SocketTimeoutException ||
            retryAllExceptions ||
            (retryHttp403 && exception.message?.contains("403") == true)
    }
}

class CookieSession {
    private val cookies = ConcurrentHashMap<String, String>()

    operator fun get(key: String): String? = cookies[key]

    operator fun set(key: String, value: String) {
        cookies[key] = value
    }

    fun getOrPut(key: String, defaultValue: () -> String): String = cookies.getOrPut(key, defaultValue)

    fun putAll(values: Map<String, String>, prefix: String = "") {
        values.forEach { (key, value) -> cookies["$prefix$key"] = value }
    }

    fun matching(prefix: String, stripPrefix: Boolean = false): Map<String, String> {
        return cookies
            .filter { it.key.startsWith(prefix) }
            .mapKeys { if (stripPrefix) it.key.removePrefix(prefix) else it.key }
    }

    fun all(): Map<String, String> = cookies.toMap()
}

interface StoreRequestStrategy {
    val storeName: String

    fun canHandle(url: String): Boolean

    fun canonicalizeUrl(url: String): String = RequestStrategyUtils.ensureHttps(url)

    suspend fun establishSession(session: CookieSession) = Unit

    fun configureRequest(connection: Connection, attempt: Int, session: CookieSession)

    fun retryPolicy(): RetryPolicy = RetryPolicy()

    fun preRequestDelayRangeMs(): LongRange? = null

    fun retryUrlAfterFetch(document: Document, currentUrl: String): String? = null

    fun blockedPageError(document: Document): String? = null
}

object StoreRequestStrategyFactory {
    private val strategies = listOf(
        AmazonRequestStrategy(),
        EbayRequestStrategy(),
        BarnesAndNobleRequestStrategy(),
        BookOutletRequestStrategy(),
        BetterWorldBooksRequestStrategy(),
        WorldOfBooksRequestStrategy(),
        BiblioRequestStrategy(),
        WalmartRequestStrategy(),
        DefaultRequestStrategy()
    )

    fun getStrategy(url: String): StoreRequestStrategy {
        return strategies.first { strategy ->
            val canHandle = strategy.canHandle(url)
            Log.d("BookTracker", "StoreRequestStrategyFactory: ${strategy.javaClass.simpleName}.canHandle('$url') = $canHandle")
            canHandle
        }
    }
}

object RequestStrategyUtils {
    const val DEFAULT_CONNECTION_TIMEOUT = 10000
    const val EXTENDED_CONNECTION_TIMEOUT = 30000
    const val BN_CONNECTION_TIMEOUT = 18000
    const val BN_READ_TIMEOUT = 28000

    const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
    const val ALTERNATIVE_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
    const val FIREFOX_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:122.0) Gecko/20100101 Firefox/122.0"
    const val EDGE_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36 Edg/121.0.0.0"

    fun ensureHttps(url: String): String {
        val trimmedUrl = url.trim()
        return when {
            trimmedUrl.startsWith("http://") -> trimmedUrl.replaceFirst("http://", "https://")
            trimmedUrl.startsWith("https://") -> trimmedUrl
            else -> "https://$trimmedUrl"
        }
    }

    fun extractHost(url: String): String? {
        if (url.isBlank()) {
            return null
        }
        return try {
            URL(ensureHttps(url)).host.lowercase(Locale.ROOT)
        } catch (_: Exception) {
            null
        }
    }

    fun configureEnhancedBrowserHeaders(connection: Connection, userAgent: String, timeout: Int, attempt: Int) {
        connection
            .userAgent(userAgent)
            .timeout(timeout)
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
    }

    fun configureChromiumClientHints(connection: Connection, browser: String = "Google Chrome", platform: String = "\"Windows\"") {
        connection
            .header("sec-ch-ua", "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"121\", \"$browser\";v=\"121\"")
            .header("sec-ch-ua-mobile", "?0")
            .header("sec-ch-ua-platform", platform)
    }

    fun generateRandomString(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="
        return (1..length)
            .map { chars.random() }
            .joinToString("")
    }

    suspend fun delayRandom(range: LongRange?) {
        if (range == null) {
            return
        }
        val delayMs = Random.nextLong(range.first, range.last + 1)
        Log.d("BookTracker", "WebScraping: Adding pre-request delay of ${delayMs}ms")
        delay(delayMs.milliseconds)
    }
}
