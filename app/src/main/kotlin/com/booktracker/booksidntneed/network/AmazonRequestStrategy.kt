package com.booktracker.booksidntneed.network

import android.util.Log
import kotlinx.coroutines.delay
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URL
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

class AmazonRequestStrategy : StoreRequestStrategy {
    override val storeName: String = "Amazon"

    override fun canHandle(url: String): Boolean {
        val host = RequestStrategyUtils.extractHost(url) ?: return false
        return host.contains("amazon.") || host.contains("a.co")
    }

    override fun canonicalizeUrl(url: String): String {
        return try {
            var cleanedUrl = RequestStrategyUtils.ensureHttps(url)
            Log.d("BookTracker", "AmazonRequestStrategy: Cleaning Amazon URL: $url")

            val urlObj = URL(cleanedUrl)
            val asinMatch = Regex("/(dp|gp/product|product)/([A-Z0-9]{10})").find(urlObj.path)

            cleanedUrl = if (asinMatch != null) {
                val asin = asinMatch.groupValues[2]
                "${urlObj.protocol}://${urlObj.host}/dp/$asin"
            } else {
                "${urlObj.protocol}://${urlObj.host}${urlObj.path}"
            }

            Log.d("BookTracker", "AmazonRequestStrategy: Cleaned Amazon URL to '$cleanedUrl'")
            cleanedUrl
        } catch (e: Exception) {
            Log.w("BookTracker", "AmazonRequestStrategy: Failed to clean Amazon URL '$url', using original: ${e.message}")
            url
        }
    }

    override suspend fun establishSession(session: CookieSession) {
        Log.d("BookTracker", "AmazonRequestStrategy: Visiting Amazon homepage to establish session...")

        val homepageConnection = Jsoup.connect("https://www.amazon.com/")
            .followRedirects(true)
            .ignoreHttpErrors(true)
            .userAgent(RequestStrategyUtils.DESKTOP_USER_AGENT)
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

        simulateCookies(homepageConnection, session, isFirstVisit = true)
        delay(Random.nextLong(300, 800).milliseconds)

        val homepageDoc = homepageConnection.get()
        Log.d("BookTracker", "AmazonRequestStrategy: Successfully visited Amazon homepage (title='${homepageDoc.title()}')")

        session.putAll(homepageConnection.response().cookies(), prefix = "amz_")
        delay(Random.nextLong(800, 1500).milliseconds)
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
                connection.header("Accept-Encoding", "gzip, deflate, br")
            }
            RequestStrategyUtils.EDGE_USER_AGENT -> RequestStrategyUtils.configureChromiumClientHints(connection, browser = "Microsoft Edge")
            else -> RequestStrategyUtils.configureChromiumClientHints(connection)
        }

        when (attempt) {
            1 -> {
                connection.referrer("https://www.google.com/search?q=books")
                connection.header("Sec-Fetch-Site", "cross-site")
                simulateCookies(connection, session, isFirstVisit = true)
            }
            2 -> {
                connection.referrer("https://www.amazon.com/")
                connection.header("Sec-Fetch-Site", "same-origin")
                simulateCookies(connection, session, isFirstVisit = false)
            }
            else -> {
                connection.referrer("https://www.amazon.com/books")
                connection.header("Sec-Fetch-Site", "same-origin")
                simulateCookies(connection, session, isFirstVisit = false)
            }
        }
    }

    override fun retryPolicy(): RetryPolicy = RetryPolicy(
        maxAttempts = 2,
        baseDelayMs = 1000L,
        maxRandomDelayMs = 500L,
        retryAllExceptions = true
    )

    override fun preRequestDelayRangeMs(): LongRange = 100L..600L

    override fun retryUrlAfterFetch(document: Document, currentUrl: String): String? {
        val pageTitle = document.select("title").first()?.text().orEmpty()
        val isIntermediatePage = pageTitle.equals("Amazon.com", ignoreCase = true) ||
            (pageTitle.length < 20 && !document.select("#productTitle, h1[id*='title']").any())

        if (!isIntermediatePage) {
            return null
        }

        val cleanedFinalUrl = canonicalizeUrl(currentUrl)
        return cleanedFinalUrl.takeIf { it != currentUrl }
    }

    private fun simulateCookies(connection: Connection, session: CookieSession, isFirstVisit: Boolean) {
        val cookies = mutableMapOf<String, String>()

        if (isFirstVisit) {
            cookies["session-id"] = "${Random.nextLong(100000000000000L, 999999999999999L)}-${Random.nextLong(1000000L, 9999999L)}"
            cookies["session-id-time"] = "${System.currentTimeMillis() / 1000}l"
            cookies["i18n-prefs"] = "USD"
            cookies["lc-main"] = "en_US"
            cookies["sp-cdn"] = "L5Z9:US"
            cookies["skin"] = "noskin"
        } else {
            cookies["session-id"] = "${Random.nextLong(100000000000000L, 999999999999999L)}-${Random.nextLong(1000000L, 9999999L)}"
            cookies["ubid-main"] = "${Random.nextLong(100000000L, 999999999L)}-${Random.nextLong(1000000L, 9999999L)}"
            cookies["x-main"] = "\"${Random.nextLong(10000000000000L, 99999999999999L)}\""
            cookies["at-main"] = "Atza|${RequestStrategyUtils.generateRandomString(80)}"
        }

        cookies["lc-main"] = "en_US"
        cookies["i18n-prefs"] = "USD"
        cookies["sp-cdn"] = "L5Z9:US"
        cookies["csm-hit"] = "tb:${RequestStrategyUtils.generateRandomString(20)}+s-${RequestStrategyUtils.generateRandomString(13)}|${System.currentTimeMillis()}"
        cookies["session-token"] = "\"${RequestStrategyUtils.generateRandomString(300)}\""

        val realCookies = session.matching("amz_", stripPrefix = true)
        if (realCookies.isNotEmpty()) {
            cookies.putAll(realCookies)
        }

        cookies.forEach { (key, value) ->
            if (key.startsWith("session") || key == "ubid-main" || key == "x-main" ||
                key.startsWith("at-") || key == "lc-main"
            ) {
                session["amz_$key"] = value
            }
        }

        connection.cookies(cookies)
    }
}
