package com.booktracker.booksidntneed.network

import android.util.Log
import kotlinx.coroutines.delay
import org.jsoup.Connection
import org.jsoup.Jsoup
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

class WorldOfBooksRequestStrategy : StoreRequestStrategy {
    override val storeName: String = "World of Books"

    override fun canHandle(url: String): Boolean {
        val host = RequestStrategyUtils.extractHost(url) ?: return false
        return host.contains("worldofbooks.com")
    }

    override fun canonicalizeUrl(url: String): String {
        val cleanedUrl = RequestStrategyUtils.ensureHttps(url)
            .trim()
            .replace("https://worldofbooks.com", "https://www.worldofbooks.com")
        Log.d("BookTracker", "WorldOfBooksRequestStrategy: Cleaned URL: $cleanedUrl")
        return cleanedUrl
    }

    override suspend fun establishSession(session: CookieSession) {
        Log.d("BookTracker", "WorldOfBooksRequestStrategy: Visiting homepage to establish session...")

        val homepageConnection = Jsoup.connect("https://www.worldofbooks.com/")
            .followRedirects(true)
            .ignoreHttpErrors(true)
            .userAgent(RequestStrategyUtils.FIREFOX_USER_AGENT)
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

        simulateCookies(homepageConnection, session, isFirstVisit = true)
        delay(Random.nextLong(600, 1200).milliseconds)

        val homepageDoc = homepageConnection.get()
        Log.d("BookTracker", "WorldOfBooksRequestStrategy: Successfully visited homepage (title='${homepageDoc.title()}')")

        session.putAll(homepageConnection.response().cookies(), prefix = "wob_")
        delay(Random.nextLong(1000, 2000).milliseconds)
    }

    override fun configureRequest(connection: Connection, attempt: Int, session: CookieSession) {
        val userAgent = when (attempt % 3) {
            0 -> RequestStrategyUtils.FIREFOX_USER_AGENT
            1 -> RequestStrategyUtils.ALTERNATIVE_USER_AGENT
            else -> RequestStrategyUtils.EDGE_USER_AGENT
        }

        RequestStrategyUtils.configureEnhancedBrowserHeaders(
            connection = connection,
            userAgent = userAgent,
            timeout = RequestStrategyUtils.EXTENDED_CONNECTION_TIMEOUT,
            attempt = attempt
        )

        when (attempt) {
            1 -> {
                connection.referrer("https://www.google.com/search?q=world+of+books")
                connection.header("Sec-Fetch-Site", "cross-site")
                simulateCookies(connection, session, isFirstVisit = true)
            }
            2 -> {
                connection.referrer("https://www.worldofbooks.com/")
                connection.header("Sec-Fetch-Site", "same-origin")
                simulateCookies(connection, session, isFirstVisit = false)
            }
            else -> {
                connection.referrer("https://www.worldofbooks.com/products")
                connection.header("Sec-Fetch-Site", "same-origin")
                simulateCookies(connection, session, isFirstVisit = false)
            }
        }
    }

    override fun retryPolicy(): RetryPolicy = RetryPolicy(
        maxAttempts = 3,
        baseDelayMs = 5000L,
        maxRandomDelayMs = 3000L,
        retryHttp403 = true
    )

    override fun preRequestDelayRangeMs(): LongRange = 400L..1000L

    private fun simulateCookies(connection: Connection, session: CookieSession, isFirstVisit: Boolean) {
        val sessionId = session.getOrPut("wob_session") {
            "WOB_${System.currentTimeMillis()}_${Random.nextInt(100000, 999999)}"
        }

        val cookies = mutableMapOf<String, String>()

        if (isFirstVisit) {
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
            cookies["_ga"] = session.getOrPut("wob_ga") {
                "GA1.2.${Random.nextLong(100000000, 999999999)}.${System.currentTimeMillis() / 1000}"
            }
            cookies["_gid"] = "GA1.2.${Random.nextLong(100000000, 999999999)}"
            cookies["session_id"] = sessionId
            cookies["shopify_y"] = session.getOrPut("wob_shopify_y") { "${Random.nextInt(10000000, 99999999)}" }
            cookies["shopify_s"] = "${Random.nextInt(10000000, 99999999)}"
            cookies["shopify_sa_t"] = "${System.currentTimeMillis()}"
            cookies["shopify_sa_p"] = "/products"
            cookies["currency"] = "USD"
            cookies["country_code"] = "US"
            cookies["locale"] = "en"
            cookies["cart"] = session.getOrPut("wob_cart") { "${Random.nextLong(100000000000L, 999999999999L)}" }
            cookies["last_page"] = "/products"
            cookies["pages_viewed"] = "${Random.nextInt(2, 10)}"
            cookies["visitor_id"] = session.getOrPut("wob_visitor") {
                "${Random.nextLong(1000000000, 9999999999)}"
            }
        }

        cookies["_fbp"] = "fb.1.${System.currentTimeMillis()}.${Random.nextLong(100000000, 999999999)}"
        cookies["_hjFirstSeen"] = "1"
        cookies["_hjIncludedInPageviewSample"] = "1"
        cookies["_hjid"] = "${Random.nextLong(10000000, 99999999)}"
        cookies["_shopify_sa_t"] = "${System.currentTimeMillis()}"
        cookies["_shopify_sa_p"] = if (isFirstVisit) "" else "/products"
        cookies["_landing_page"] = if (isFirstVisit) "%2F" else "%2Fproducts"
        cookies["_orig_referrer"] = if (isFirstVisit) "https%3A//www.google.com/" else "https%3A//www.worldofbooks.com/"
        cookies["_secure_session_id"] = "${Random.nextLong(1000000000000000L, 9999999999999999L)}"
        cookies["cookieconsent_status"] = "allow"
        cookies["CookieConsentPolicy"] = "0:1"
        cookies["LSKey-c" + '$' + "CookieConsentPolicy"] = "0:1"

        val realCookies = session.matching("wob_", stripPrefix = true)
        if (realCookies.isNotEmpty()) {
            cookies.putAll(realCookies)
        }

        cookies.forEach { (key, value) ->
            if (key.startsWith("_ga") || key == "session_id" || key == "visitor_id" ||
                key.startsWith("shopify_") || key == "cart"
            ) {
                session["wob_$key"] = value
            }
        }

        connection.cookies(cookies)
    }
}
