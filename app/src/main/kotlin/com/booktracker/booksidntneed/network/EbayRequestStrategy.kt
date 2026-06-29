package com.booktracker.booksidntneed.network

import android.util.Log
import org.jsoup.Connection
import org.jsoup.Jsoup
import java.net.URL
import java.util.Locale

class EbayRequestStrategy : StoreRequestStrategy {
    override val storeName: String = "eBay"

    override fun canHandle(url: String): Boolean {
        val host = RequestStrategyUtils.extractHost(url) ?: return false
        return host == "ebay.com" ||
            host.endsWith(".ebay.com") ||
            host == "ebay.co.uk" ||
            host.endsWith(".ebay.co.uk") ||
            host == "ebay.ca" ||
            host.endsWith(".ebay.ca") ||
            host == "ebay.de" ||
            host.endsWith(".ebay.de") ||
            host == "ebay.fr" ||
            host.endsWith(".ebay.fr") ||
            host == "ebay.it" ||
            host.endsWith(".ebay.it") ||
            host == "ebay.es" ||
            host.endsWith(".ebay.es") ||
            host == "ebay.com.au" ||
            host.endsWith(".ebay.com.au")
    }

    override fun canonicalizeUrl(url: String): String {
        val cleanedUrl = RequestStrategyUtils.ensureHttps(url)
        return try {
            val parsed = URL(cleanedUrl)
            val host = normalizeEbayHost(parsed.host)
            val itemId = Regex("""^/itm/(?:[^/]+/)?(\d+)""")
                .find(parsed.path)
                ?.groupValues
                ?.get(1)

            val normalizedUrl = if (itemId != null) {
                "https://www.$host/itm/$itemId"
            } else {
                "https://www.$host${parsed.path.ifBlank { "/" }}"
            }
            Log.d("BookTracker", "EbayRequestStrategy: Cleaned eBay URL: $normalizedUrl")
            normalizedUrl
        } catch (e: Exception) {
            Log.w("BookTracker", "EbayRequestStrategy: Failed to normalize eBay URL: ${e.message}")
            cleanedUrl
        }
    }

    override suspend fun establishSession(session: CookieSession) {
        Log.d("BookTracker", "EbayRequestStrategy: Visiting eBay homepage to establish session...")

        val homepageConnection = Jsoup.connect("https://www.ebay.com/")
            .followRedirects(true)
            .ignoreHttpErrors(true)
            .userAgent(RequestStrategyUtils.DESKTOP_USER_AGENT)
            .timeout(15000)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("DNT", "1")
            .header("Connection", "keep-alive")
            .header("Upgrade-Insecure-Requests", "1")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")
            .header("Sec-Fetch-User", "?1")
            .header("Cache-Control", "max-age=0")

        val homepageDoc = homepageConnection.get()
        Log.d("BookTracker", "EbayRequestStrategy: Successfully visited eBay homepage (title='${homepageDoc.title()}')")

        session.putAll(homepageConnection.response().cookies(), prefix = "ebay_")
    }

    override fun configureRequest(connection: Connection, attempt: Int, session: CookieSession) {
        val userAgent = when (attempt % 3) {
            0 -> RequestStrategyUtils.DESKTOP_USER_AGENT
            1 -> RequestStrategyUtils.FIREFOX_USER_AGENT
            else -> RequestStrategyUtils.EDGE_USER_AGENT
        }

        RequestStrategyUtils.configureEnhancedBrowserHeaders(
            connection = connection,
            userAgent = userAgent,
            timeout = RequestStrategyUtils.EXTENDED_CONNECTION_TIMEOUT,
            attempt = attempt
        )

        when (userAgent) {
            RequestStrategyUtils.FIREFOX_USER_AGENT -> {
                connection.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                connection.header("Accept-Language", "en-US,en;q=0.5")
                connection.header("Accept-Encoding", "gzip, deflate")
            }
            RequestStrategyUtils.EDGE_USER_AGENT -> {
                RequestStrategyUtils.configureChromiumClientHints(connection, browser = "Microsoft Edge")
            }
            else -> {
                RequestStrategyUtils.configureChromiumClientHints(connection)
            }
        }

        when (attempt) {
            1 -> {
                connection.referrer("https://www.google.com/search?q=ebay+books")
                connection.header("Sec-Fetch-Site", "cross-site")
            }
            else -> {
                connection.referrer("https://www.ebay.com/")
                connection.header("Sec-Fetch-Site", "same-origin")
            }
        }

        val ebayCookies = session.matching("ebay_", stripPrefix = true)
        if (ebayCookies.isNotEmpty()) {
            connection.cookies(ebayCookies)
        }
    }

    override fun retryPolicy(): RetryPolicy = RetryPolicy(
        maxAttempts = 2,
        baseDelayMs = 1000L,
        maxRandomDelayMs = 500L,
        retryAllExceptions = true
    )

    override fun preRequestDelayRangeMs(): LongRange = 100L..600L

    private fun normalizeEbayHost(host: String): String {
        val lowerHost = host.lowercase(Locale.ROOT).removePrefix("www.")
        return when {
            lowerHost == "ebay.co.uk" || lowerHost.endsWith(".ebay.co.uk") -> "ebay.co.uk"
            lowerHost == "ebay.com.au" || lowerHost.endsWith(".ebay.com.au") -> "ebay.com.au"
            lowerHost == "ebay.com" || lowerHost.endsWith(".ebay.com") -> "ebay.com"
            lowerHost == "ebay.ca" || lowerHost.endsWith(".ebay.ca") -> "ebay.ca"
            lowerHost == "ebay.de" || lowerHost.endsWith(".ebay.de") -> "ebay.de"
            lowerHost == "ebay.fr" || lowerHost.endsWith(".ebay.fr") -> "ebay.fr"
            lowerHost == "ebay.it" || lowerHost.endsWith(".ebay.it") -> "ebay.it"
            lowerHost == "ebay.es" || lowerHost.endsWith(".ebay.es") -> "ebay.es"
            else -> lowerHost
        }
    }
}
