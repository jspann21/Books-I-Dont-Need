package com.booktracker.booksidntneed.network

import android.util.Log
import org.jsoup.Connection
import org.jsoup.Jsoup
import java.net.URL
import java.util.Locale

class EbayRequestStrategy : StoreRequestStrategy {
    override val storeName: String = "eBay"
    override val requiresSession: Boolean = true

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

        // eBay binds its bot-management cookies to the browser identity that created
        // them. Do not carry cookies from a challenged or differently configured
        // request into the new session.
        session.clear(SESSION_COOKIE_PREFIX)

        val homepageConnection = Jsoup.connect("https://www.ebay.com/")
            .followRedirects(true)
            .ignoreHttpErrors(true)
            .userAgent(RequestStrategyUtils.DESKTOP_USER_AGENT)
            .timeout(15000)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Upgrade-Insecure-Requests", "1")

        val homepageDoc = homepageConnection.get()
        val response = homepageConnection.response()
        val responseCookies = response.cookies()

        // eBay can answer the anonymous homepage bootstrap with HTTP 403 while
        // still issuing the cookies required for a subsequent item request. The
        // listing succeeds when those cookies are retained. Previously we threw
        // before saving them, so the caller continued with an empty session and
        // every item request was rejected with another 403.
        session.putAll(responseCookies, prefix = SESSION_COOKIE_PREFIX)

        if (response.statusCode() in 200..299) {
            blockedPageError(homepageDoc)?.let { error -> throw IllegalStateException(error) }
            Log.d(
                "BookTracker",
                "EbayRequestStrategy: Successfully visited eBay homepage (title='${homepageDoc.title()}')"
            )
        } else if (response.statusCode() == 403 && responseCookies.isNotEmpty()) {
            Log.d(
                "BookTracker",
                "EbayRequestStrategy: eBay homepage bootstrap returned HTTP 403 and issued ${responseCookies.size} session cookies"
            )
        } else {
            throw IllegalStateException(
                "eBay session setup returned HTTP ${response.statusCode()} without issuing session cookies"
            )
        }
    }

    override fun configureRequest(connection: Connection, attempt: Int, session: CookieSession) {
        // Keep this identity identical to establishSession(). Previously attempt 1
        // changed to Firefox while sending cookies issued to Chrome, which reliably
        // redirected otherwise valid listings to /splashui/challenge.
        connection
            .userAgent(RequestStrategyUtils.DESKTOP_USER_AGENT)
            .timeout(RequestStrategyUtils.EXTENDED_CONNECTION_TIMEOUT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Accept-Encoding", "gzip, deflate")
            .header("Upgrade-Insecure-Requests", "1")
            .referrer("https://www.ebay.com/")

        val ebayCookies = session.matching(SESSION_COOKIE_PREFIX, stripPrefix = true)
        if (ebayCookies.isNotEmpty()) {
            connection.cookies(ebayCookies)
        }
    }

    override fun updateSession(responseCookies: Map<String, String>, session: CookieSession) {
        session.putAll(responseCookies, prefix = SESSION_COOKIE_PREFIX)
    }

    override fun blockedPageError(document: org.jsoup.nodes.Document): String? {
        val location = document.location().lowercase(Locale.ROOT)
        val title = document.title().lowercase(Locale.ROOT)
        val text = document.body().text().lowercase(Locale.ROOT)
        val isChallenge = location.contains("/splashui/challenge") ||
            title.contains("pardon our interruption") ||
            text.contains("pardon our interruption") ||
            text.contains("verify yourself to continue") ||
            text.contains("security measure")

        return if (isChallenge) {
            "eBay blocked this request with a browser verification challenge"
        } else {
            null
        }
    }

    override fun retryPolicy(): RetryPolicy = RetryPolicy(
        maxAttempts = 3,
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

    private companion object {
        const val SESSION_COOKIE_PREFIX = "ebay_"
    }
}
