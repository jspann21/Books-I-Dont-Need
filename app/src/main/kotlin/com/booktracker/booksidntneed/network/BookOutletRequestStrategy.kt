package com.booktracker.booksidntneed.network

import android.util.Log
import kotlinx.coroutines.delay
import org.jsoup.Connection
import org.jsoup.Jsoup
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

class BookOutletRequestStrategy : StoreRequestStrategy {
    override val storeName: String = "Book Outlet"

    override fun canHandle(url: String): Boolean {
        val host = RequestStrategyUtils.extractHost(url) ?: return false
        return host.contains("bookoutlet.com")
    }

    override fun canonicalizeUrl(url: String): String {
        val cleanedUrl = RequestStrategyUtils.ensureHttps(url).trim()
        Log.d("BookTracker", "BookOutletRequestStrategy: Book Outlet URL is valid as-is: $cleanedUrl")
        return cleanedUrl
    }

    override suspend fun establishSession(session: CookieSession) {
        Log.d("BookTracker", "BookOutletRequestStrategy: Visiting homepage to establish session...")

        val homepageConnection = Jsoup.connect("https://bookoutlet.com/")
            .followRedirects(true)
            .ignoreHttpErrors(true)
            .userAgent(RequestStrategyUtils.DESKTOP_USER_AGENT)
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

        simulateCookies(homepageConnection, session, isFirstVisit = true)
        delay(Random.nextLong(200, 600).milliseconds)

        val homepageDoc = homepageConnection.get()
        Log.d("BookTracker", "BookOutletRequestStrategy: Successfully visited homepage (title='${homepageDoc.title()}')")

        session.putAll(homepageConnection.response().cookies())
        delay(Random.nextLong(800, 1500).milliseconds)
    }

    override fun configureRequest(connection: Connection, attempt: Int, session: CookieSession) {
        val userAgent = when (attempt % 4) {
            0 -> RequestStrategyUtils.DESKTOP_USER_AGENT
            1 -> RequestStrategyUtils.ALTERNATIVE_USER_AGENT
            2 -> RequestStrategyUtils.FIREFOX_USER_AGENT
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
            RequestStrategyUtils.EDGE_USER_AGENT -> RequestStrategyUtils.configureChromiumClientHints(connection, browser = "Microsoft Edge")
            else -> RequestStrategyUtils.configureChromiumClientHints(
                connection,
                platform = if (attempt % 2 == 0) "\"macOS\"" else "\"Windows\""
            )
        }

        when (attempt) {
            1 -> {
                connection.referrer("https://www.google.com/search?q=book+outlet+books")
                connection.header("Sec-Fetch-Site", "cross-site")
                connection.header("Purpose", "prefetch")
                simulateCookies(connection, session, isFirstVisit = true)
            }
            2 -> {
                connection.referrer("https://bookoutlet.com/")
                connection.header("Sec-Fetch-Site", "same-origin")
                simulateCookies(connection, session, isFirstVisit = false)
            }
            else -> {
                connection.referrer("https://bookoutlet.com/books")
                connection.header("Sec-Fetch-Site", "same-origin")
                simulateCookies(connection, session, isFirstVisit = false)
            }
        }
    }

    override fun retryPolicy(): RetryPolicy = RetryPolicy(
        startAttempt = 2,
        maxAttempts = 2,
        baseDelayMs = 2000L,
        maxRandomDelayMs = 1000L
    )

    override fun preRequestDelayRangeMs(): LongRange = 300L..800L

    private fun simulateCookies(connection: Connection, session: CookieSession, isFirstVisit: Boolean) {
        val sessionId = session.getOrPut("bookoutlet_session") {
            "${System.currentTimeMillis()}_${Random.nextInt(100000, 999999)}"
        }

        val cookies = mutableMapOf<String, String>()

        if (isFirstVisit) {
            cookies["_ga"] = "GA1.2.${Random.nextLong(100000000, 999999999)}.${System.currentTimeMillis() / 1000}"
            cookies["_gid"] = "GA1.2.${Random.nextLong(100000000, 999999999)}"
            cookies["session_id"] = sessionId
            cookies["first_visit"] = "true"
            cookies["currency"] = "USD"
            cookies["country"] = "US"
            cookies["timezone"] = "America/New_York"
        } else {
            val existingGa = session.getOrPut("_ga") {
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

        cookies["_fbp"] = "fb.1.${System.currentTimeMillis()}.${Random.nextLong(100000000, 999999999)}"
        cookies["_hjid"] = "${Random.nextLong(10000000, 99999999)}"
        cookies["visitor_id"] = "${Random.nextLong(1000000000, 9999999999)}"

        val realCookies = session.all().filter { it.key.isNotBlank() }
        if (realCookies.isNotEmpty()) {
            cookies.putAll(realCookies)
        }

        cookies.forEach { (key, value) ->
            if (key.startsWith("_ga") || key == "session_id") {
                session[key] = value
            }
        }

        connection.cookies(cookies)
    }
}
