package com.booktracker.booksidntneed.network

import android.util.Log
import org.jsoup.Connection
import java.net.URL

class WalmartRequestStrategy : StoreRequestStrategy {
    override val storeName: String = "Walmart"

    override fun canHandle(url: String): Boolean {
        val host = RequestStrategyUtils.extractHost(url) ?: return false
        return host == "walmart.com" || host.endsWith(".walmart.com")
    }

    override fun canonicalizeUrl(url: String): String {
        val cleanedUrl = RequestStrategyUtils.ensureHttps(url).trim()
        return try {
            val parsed = URL(cleanedUrl)
            val normalizedHost = when {
                parsed.host.equals("walmart.com", ignoreCase = true) -> "www.walmart.com"
                else -> parsed.host
            }
            val normalizedUrl = "https://$normalizedHost${parsed.path.ifBlank { "/" }}"
            Log.d("BookTracker", "WalmartRequestStrategy: Cleaned URL: $normalizedUrl")
            normalizedUrl
        } catch (e: Exception) {
            Log.w("BookTracker", "WalmartRequestStrategy: Failed to normalize URL: ${e.message}")
            cleanedUrl
        }
    }

    override fun configureRequest(connection: Connection, attempt: Int, session: CookieSession) {
        val userAgent = when (attempt % 3) {
            0 -> RequestStrategyUtils.FIREFOX_USER_AGENT
            1 -> RequestStrategyUtils.DESKTOP_USER_AGENT
            else -> RequestStrategyUtils.EDGE_USER_AGENT
        }

        RequestStrategyUtils.configureEnhancedBrowserHeaders(
            connection = connection,
            userAgent = userAgent,
            timeout = RequestStrategyUtils.EXTENDED_CONNECTION_TIMEOUT,
            attempt = attempt
        )
        RequestStrategyUtils.configureChromiumClientHints(connection)

        connection
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Accept-Encoding", "gzip, deflate")
            .header("Sec-Fetch-Site", if (attempt == 1) "cross-site" else "same-origin")
            .referrer(
                if (attempt == 1) {
                    "https://www.google.com/search?q=walmart+books"
                } else {
                    "https://www.walmart.com/"
                }
            )
            .cookies(
                mapOf(
                    "vtc" to session.getOrPut("walmart_vtc") { RequestStrategyUtils.generateRandomString(32) },
                    "deliveryPostalCode" to "10001",
                    "postalCode" to "10001",
                    "country" to "US",
                    "hasLocData" to "1"
                )
            )
    }

    override fun retryPolicy(): RetryPolicy = RetryPolicy(
        maxAttempts = 2,
        baseDelayMs = 2000L,
        maxRandomDelayMs = 1000L
    )
}
