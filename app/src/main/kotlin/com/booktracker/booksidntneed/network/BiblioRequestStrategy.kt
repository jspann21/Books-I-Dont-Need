package com.booktracker.booksidntneed.network

import android.util.Log
import kotlinx.coroutines.delay
import org.jsoup.Connection
import org.jsoup.Jsoup
import java.net.URL
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

class BiblioRequestStrategy : StoreRequestStrategy {
    override val storeName: String = "Biblio"

    override fun canHandle(url: String): Boolean {
        val host = RequestStrategyUtils.extractHost(url) ?: return false
        return host == "biblio.com" || host.endsWith(".biblio.com")
    }

    override fun canonicalizeUrl(url: String): String {
        val cleanedUrl = RequestStrategyUtils.ensureHttps(url).trim()
        return try {
            val parsed = URL(cleanedUrl)
            val normalizedHost = when {
                parsed.host.equals("biblio.com", ignoreCase = true) -> "www.biblio.com"
                else -> parsed.host
            }
            val query = parsed.query
                ?.takeIf { it.isNotBlank() }
                ?.let { existingQuery ->
                    if (existingQuery.split("&").any { it.startsWith("aid=", ignoreCase = true) }) {
                        existingQuery
                    } else {
                        "$existingQuery&aid=frg"
                    }
                }
                ?: "aid=frg"
            val normalizedUrl = "https://$normalizedHost${parsed.path.ifBlank { "/" }}?$query"
            Log.d("BookTracker", "BiblioRequestStrategy: Cleaned URL: $normalizedUrl")
            normalizedUrl
        } catch (e: Exception) {
            Log.w("BookTracker", "BiblioRequestStrategy: Failed to normalize URL: ${e.message}")
            cleanedUrl
        }
    }

    override suspend fun establishSession(session: CookieSession) {
        Log.d("BookTracker", "BiblioRequestStrategy: Visiting homepage to establish session...")

        val homepageConnection = Jsoup.connect("https://www.biblio.com/")
            .followRedirects(true)
            .ignoreHttpErrors(true)
            .userAgent(RequestStrategyUtils.FIREFOX_USER_AGENT)
            .timeout(RequestStrategyUtils.EXTENDED_CONNECTION_TIMEOUT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Accept-Encoding", "gzip, deflate")
            .header("DNT", "1")
            .header("Connection", "keep-alive")
            .header("Upgrade-Insecure-Requests", "1")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "cross-site")
            .header("Sec-Fetch-User", "?1")
            .header("Cache-Control", "max-age=0")
            .referrer("https://www.google.com/search?q=biblio+books")

        applyCookies(homepageConnection, session, isFirstVisit = true)
        delay(Random.nextLong(500, 1100).milliseconds)

        val homepageDoc = homepageConnection.get()
        Log.d("BookTracker", "BiblioRequestStrategy: Homepage status title='${homepageDoc.title()}'")

        session.putAll(homepageConnection.response().cookies(), prefix = "biblio_")
        delay(Random.nextLong(700, 1600).milliseconds)
    }

    override fun configureRequest(connection: Connection, attempt: Int, session: CookieSession) {
        val userAgent = RequestStrategyUtils.FIREFOX_USER_AGENT

        RequestStrategyUtils.configureEnhancedBrowserHeaders(
            connection = connection,
            userAgent = userAgent,
            timeout = RequestStrategyUtils.EXTENDED_CONNECTION_TIMEOUT,
            attempt = attempt
        )

        connection
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Accept-Encoding", "gzip, deflate")

        when (attempt) {
            1 -> {
                connection.referrer("https://www.google.com/search?q=biblio+books")
                connection.header("Sec-Fetch-Site", "cross-site")
                applyCookies(connection, session, isFirstVisit = true)
            }
            2 -> {
                connection.referrer("https://www.biblio.com/")
                connection.header("Sec-Fetch-Site", "same-origin")
                applyCookies(connection, session, isFirstVisit = false)
            }
            else -> {
                connection.referrer("https://www.biblio.com/bookstore")
                connection.header("Sec-Fetch-Site", "same-origin")
                applyCookies(connection, session, isFirstVisit = false)
            }
        }
    }

    override fun retryPolicy(): RetryPolicy = RetryPolicy(
        maxAttempts = 3,
        baseDelayMs = 2500L,
        maxRandomDelayMs = 1500L,
        retryHttp403 = true
    )

    override fun preRequestDelayRangeMs(): LongRange = 400L..1000L

    override fun blockedPageError(document: org.jsoup.nodes.Document): String? {
        val title = document.title()
        val text = document.text()
        return when {
            title.contains("Just a moment", ignoreCase = true) ||
                text.contains("checking if the site connection is secure", ignoreCase = true) ||
                text.contains("cloudflare", ignoreCase = true) ->
                "Biblio is asking for browser verification. Please try again later or use a different store URL."
            else -> null
        }
    }

    private fun applyCookies(connection: Connection, session: CookieSession, isFirstVisit: Boolean) {
        val cookies = session.matching("biblio_", stripPrefix = true).toMutableMap()
        cookies["aid"] = cookies["aid"] ?: "frg"
        connection.cookies(cookies)
    }
}
