package com.booktracker.booksidntneed.network

import android.util.Log
import org.jsoup.Connection
import java.net.URL

class BooksAMillionRequestStrategy : StoreRequestStrategy {
    override val storeName: String = "Books-A-Million"

    override fun canHandle(url: String): Boolean {
        val host = RequestStrategyUtils.extractHost(url) ?: return false
        return host == "booksamillion.com" || host.endsWith(".booksamillion.com")
    }

    override fun canonicalizeUrl(url: String): String {
        val cleanedUrl = RequestStrategyUtils.ensureHttps(url).trim()
        return try {
            val parsed = URL(cleanedUrl)
            val normalizedHost = when {
                parsed.host.equals("booksamillion.com", ignoreCase = true) -> "www.booksamillion.com"
                else -> parsed.host
            }
            val normalizedUrl = "https://$normalizedHost${parsed.file.ifBlank { "/" }}"
            Log.d("BookTracker", "BooksAMillionRequestStrategy: Cleaned URL: $normalizedUrl")
            normalizedUrl
        } catch (e: Exception) {
            Log.w("BookTracker", "BooksAMillionRequestStrategy: Failed to normalize URL: ${e.message}")
            cleanedUrl
        }
    }

    override fun configureRequest(connection: Connection, attempt: Int, session: CookieSession) {
        val userAgent = when (attempt) {
            1 -> RequestStrategyUtils.FIREFOX_USER_AGENT
            2 -> RequestStrategyUtils.DESKTOP_USER_AGENT
            else -> RequestStrategyUtils.EDGE_USER_AGENT
        }

        connection
            .userAgent(userAgent)
            .timeout(RequestStrategyUtils.EXTENDED_CONNECTION_TIMEOUT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", if (userAgent == RequestStrategyUtils.FIREFOX_USER_AGENT) "en-US,en;q=0.5" else "en-US,en;q=0.9")
            .header("Accept-Encoding", "gzip, deflate")
            .header("DNT", "1")
            .header("Upgrade-Insecure-Requests", "1")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", if (attempt == 1) "cross-site" else "same-origin")
            .header("Sec-Fetch-User", "?1")
            .header("Cache-Control", "max-age=0")
            .referrer(
                if (attempt == 1) {
                    "https://www.google.com/search?q=booksamillion+books"
                } else {
                    "https://www.booksamillion.com/"
                }
            )

        if (userAgent != RequestStrategyUtils.FIREFOX_USER_AGENT) {
            RequestStrategyUtils.configureChromiumClientHints(
                connection = connection,
                browser = if (userAgent == RequestStrategyUtils.EDGE_USER_AGENT) "Microsoft Edge" else "Google Chrome"
            )
        }

        connection.cookies(
            mapOf(
                "id" to session.getOrPut("bam_id") { "BAM${System.currentTimeMillis()}" },
                "fs" to session.getOrPut("bam_fs") { "BAM${System.currentTimeMillis()}" }
            )
        )
    }

    override fun retryPolicy(): RetryPolicy = RetryPolicy(
        maxAttempts = 2,
        baseDelayMs = 1500L,
        maxRandomDelayMs = 1000L,
        retryHttp403 = true
    )

    override fun preRequestDelayRangeMs(): LongRange = 250L..750L
}
