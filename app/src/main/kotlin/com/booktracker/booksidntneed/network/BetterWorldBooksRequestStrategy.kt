package com.booktracker.booksidntneed.network

import android.util.Log
import kotlinx.coroutines.delay
import org.jsoup.Connection
import org.jsoup.Jsoup
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

class BetterWorldBooksRequestStrategy : StoreRequestStrategy {
    override val storeName: String = "Better World Books"

    override fun canHandle(url: String): Boolean {
        val host = RequestStrategyUtils.extractHost(url) ?: return false
        return host.contains("betterworldbooks.com")
    }

    override fun canonicalizeUrl(url: String): String {
        val cleanedUrl = RequestStrategyUtils.ensureHttps(url)
            .trim()
            .replace("https://betterworldbooks.com", "https://www.betterworldbooks.com")
        Log.d("BookTracker", "BetterWorldBooksRequestStrategy: Cleaned URL: $cleanedUrl")
        return cleanedUrl
    }

    override suspend fun establishSession(session: CookieSession) {
        Log.d("BookTracker", "BetterWorldBooksRequestStrategy: Visiting homepage to establish session...")

        val homepageConnection = Jsoup.connect("https://www.betterworldbooks.com/")
            .followRedirects(true)
            .ignoreHttpErrors(true)
            .userAgent(RequestStrategyUtils.FIREFOX_USER_AGENT)
            .timeout(20000)
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
            .referrer("https://www.google.com/search?q=better+world+books")

        simulateCookies(homepageConnection, session, isFirstVisit = true)
        delay(Random.nextLong(800, 1500).milliseconds)

        val homepageDoc = homepageConnection.get()
        Log.d("BookTracker", "BetterWorldBooksRequestStrategy: Successfully visited homepage (title='${homepageDoc.title()}')")

        session.putAll(homepageConnection.response().cookies(), prefix = "bwb_")
        delay(Random.nextLong(1200, 2500).milliseconds)
    }

    override fun configureRequest(connection: Connection, attempt: Int, session: CookieSession) {
        val userAgent = when (attempt % 3) {
            0 -> RequestStrategyUtils.FIREFOX_USER_AGENT
            1 -> RequestStrategyUtils.EDGE_USER_AGENT
            else -> RequestStrategyUtils.ALTERNATIVE_USER_AGENT
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
            else -> RequestStrategyUtils.configureChromiumClientHints(connection, platform = "\"macOS\"")
        }

        connection
            .header("Sec-Ch-Ua-Full-Version-List", "\"Not_A Brand\";v=\"8.0.0.0\", \"Chromium\";v=\"121.0.6167.139\", \"Google Chrome\";v=\"121.0.6167.139\"")
            .header("Sec-Ch-Viewport-Width", "1920")
            .header("Sec-Ch-Viewport-Height", "1080")
            .header("Viewport-Width", "1920")
            .header("Device-Memory", "8")
            .header("Downlink", "10")
            .header("ECT", "4g")
            .header("RTT", "50")
            .header("Save-Data", "off")

        when (attempt) {
            1 -> {
                connection.referrer("https://www.google.com/search?q=better+world+books")
                connection.header("Sec-Fetch-Site", "cross-site")
                connection.header("Purpose", "prefetch")
                simulateCookies(connection, session, isFirstVisit = true)
            }
            2 -> {
                connection.referrer("https://www.betterworldbooks.com/")
                connection.header("Sec-Fetch-Site", "same-origin")
                simulateCookies(connection, session, isFirstVisit = false)
            }
            else -> {
                connection.referrer("https://www.betterworldbooks.com/books")
                connection.header("Sec-Fetch-Site", "same-origin")
                simulateCookies(connection, session, isFirstVisit = false)
            }
        }
    }

    override fun retryPolicy(): RetryPolicy = RetryPolicy(
        maxAttempts = 2,
        baseDelayMs = 3000L,
        maxRandomDelayMs = 2000L,
        retryHttp403 = true
    )

    override fun preRequestDelayRangeMs(): LongRange = 600L..1200L

    private fun simulateCookies(connection: Connection, session: CookieSession, isFirstVisit: Boolean) {
        val sessionId = session.getOrPut("bwb_session") {
            "BWB_${System.currentTimeMillis()}_${Random.nextInt(100000, 999999)}"
        }

        val cookies = mutableMapOf<String, String>()

        if (isFirstVisit) {
            cookies["_ga"] = "GA1.2.${Random.nextLong(100000000, 999999999)}.${System.currentTimeMillis() / 1000}"
            cookies["_gid"] = "GA1.2.${Random.nextLong(100000000, 999999999)}"
            cookies["_gat"] = "1"
            cookies["session_id"] = sessionId
            cookies["first_visit"] = "1"
            cookies["currency"] = "USD"
            cookies["country_code"] = "US"
            cookies["locale"] = "en_US"
            cookies["visitor_id"] = "${Random.nextLong(1000000000, 9999999999)}"
        } else {
            cookies["_ga"] = session.getOrPut("bwb_ga") {
                "GA1.2.${Random.nextLong(100000000, 999999999)}.${System.currentTimeMillis() / 1000}"
            }
            cookies["_gid"] = "GA1.2.${Random.nextLong(100000000, 999999999)}"
            cookies["session_id"] = sessionId
            cookies["returning_visitor"] = "1"
            cookies["currency"] = "USD"
            cookies["country_code"] = "US"
            cookies["locale"] = "en_US"
            cookies["last_page"] = "/books"
            cookies["pages_viewed"] = "${Random.nextInt(2, 12)}"
            cookies["visitor_id"] = session.getOrPut("bwb_visitor") {
                "${Random.nextLong(1000000000, 9999999999)}"
            }
        }

        cookies["_fbp"] = "fb.1.${System.currentTimeMillis()}.${Random.nextLong(100000000, 999999999)}"
        cookies["_hjFirstSeen"] = "1"
        cookies["_hjIncludedInPageviewSample"] = "1"
        cookies["_hjid"] = "${Random.nextLong(10000000, 99999999)}"
        cookies["OptanonAlertBoxClosed"] = "${System.currentTimeMillis()}"
        cookies["OptanonConsent"] = "isIABGlobal=false&datestamp=${System.currentTimeMillis()}"

        val realCookies = session.matching("bwb_", stripPrefix = true)
        if (realCookies.isNotEmpty()) {
            cookies.putAll(realCookies)
        }

        cookies.forEach { (key, value) ->
            if (key.startsWith("_ga") || key == "session_id" || key == "visitor_id") {
                session["bwb_$key"] = value
            }
        }

        connection.cookies(cookies)
    }
}
