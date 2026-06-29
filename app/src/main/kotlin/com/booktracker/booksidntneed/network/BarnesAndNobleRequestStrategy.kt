package com.booktracker.booksidntneed.network

import android.util.Log
import kotlinx.coroutines.delay
import org.jsoup.Connection
import org.jsoup.Jsoup
import java.net.URL
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

class BarnesAndNobleRequestStrategy : StoreRequestStrategy {
    override val storeName: String = "Barnes & Noble"

    private val attemptOrder = listOf(3, 1, 2)

    override fun canHandle(url: String): Boolean {
        val host = RequestStrategyUtils.extractHost(url) ?: return false
        return host.contains("barnesandnoble.com") || host.contains("bn.com")
    }

    override fun canonicalizeUrl(url: String): String {
        val cleanedUrl = RequestStrategyUtils.ensureHttps(url)
        return try {
            val parsed = URL(cleanedUrl)
            val normalizedHost = when {
                parsed.host.equals("www.barnesandnoble.com", ignoreCase = true) -> "www.barnesandnoble.com"
                parsed.host.equals("barnesandnoble.com", ignoreCase = true) -> "www.barnesandnoble.com"
                parsed.host.equals("www.bn.com", ignoreCase = true) -> "www.barnesandnoble.com"
                parsed.host.equals("bn.com", ignoreCase = true) -> "www.barnesandnoble.com"
                parsed.host.startsWith("www.", ignoreCase = true) -> parsed.host
                else -> "www.${parsed.host}"
            }

            val normalizedUrl = "https://$normalizedHost${parsed.file.ifBlank { "/" }}"
            Log.d("BookTracker", "BarnesAndNobleRequestStrategy: Cleaned URL: $normalizedUrl")
            normalizedUrl
        } catch (e: Exception) {
            Log.w("BookTracker", "BarnesAndNobleRequestStrategy: Failed to normalize URL: ${e.message}")
            cleanedUrl
        }
    }

    override suspend fun establishSession(session: CookieSession) {
        Log.d("BookTracker", "BarnesAndNobleRequestStrategy: Visiting homepage to establish session...")

        val homepageConnection = Jsoup.connect("https://www.barnesandnoble.com/")
            .followRedirects(true)
            .ignoreHttpErrors(true)
            .userAgent(RequestStrategyUtils.FIREFOX_USER_AGENT)
            .timeout(RequestStrategyUtils.BN_READ_TIMEOUT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Accept-Encoding", "gzip, deflate, br")
            .header("DNT", "1")
            .header("Connection", "keep-alive")
            .header("Upgrade-Insecure-Requests", "1")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")
            .header("Sec-Fetch-User", "?1")
            .header("Cache-Control", "max-age=0")
            .referrer("https://www.google.com/")

        simulateCookies(homepageConnection, session, isFirstVisit = true)
        delay(Random.nextLong(500, 1200).milliseconds)

        val homepageDoc = homepageConnection.get()
        Log.d("BookTracker", "BarnesAndNobleRequestStrategy: Successfully visited homepage (title='${homepageDoc.title()}')")

        session.putAll(homepageConnection.response().cookies(), prefix = "bn_")
        delay(Random.nextLong(700, 1500).milliseconds)
    }

    override fun configureRequest(connection: Connection, attempt: Int, session: CookieSession) {
        val strategyAttempt = attemptOrder[(attempt - 1).coerceIn(0, attemptOrder.lastIndex)]
        Log.d("BookTracker", "BarnesAndNobleRequestStrategy: Strategy $strategyAttempt selected for attempt $attempt")

        val userAgent = when (strategyAttempt % 3) {
            0 -> RequestStrategyUtils.FIREFOX_USER_AGENT
            1 -> RequestStrategyUtils.EDGE_USER_AGENT
            else -> RequestStrategyUtils.DESKTOP_USER_AGENT
        }

        RequestStrategyUtils.configureEnhancedBrowserHeaders(
            connection = connection,
            userAgent = userAgent,
            timeout = RequestStrategyUtils.BN_CONNECTION_TIMEOUT,
            attempt = attempt
        )

        connection
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Accept-Encoding", "gzip, deflate, br")
            .header("sec-ch-ua", "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"121\", \"Google Chrome\";v=\"121\"")
            .header("sec-ch-ua-mobile", "?0")
            .header("sec-ch-ua-platform", "\"Windows\"")
            .header("Sec-Ch-Ua-Full-Version-List", "\"Not_A Brand\";v=\"8.0.0.0\", \"Chromium\";v=\"121.0.6167.140\", \"Google Chrome\";v=\"121.0.6167.140\"")
            .header("Sec-Fetch-Site", if (strategyAttempt == 1) "cross-site" else "same-origin")
            .header("Purpose", if (strategyAttempt == 1) "prefetch" else "navigate")
            .referrer(
                when (strategyAttempt) {
                    1 -> "https://www.google.com/search?q=barnes+noble+books"
                    2 -> "https://www.barnesandnoble.com/"
                    else -> "https://www.barnesandnoble.com/b/books/_/N-29Z8q8"
                }
            )

        simulateCookies(connection, session, strategyAttempt == 1)
    }

    override fun retryPolicy(): RetryPolicy = RetryPolicy(
        maxAttempts = 3,
        baseDelayMs = 4000L,
        maxRandomDelayMs = 2000L
    )

    override fun preRequestDelayRangeMs(): LongRange = 600L..1800L

    private fun simulateCookies(connection: Connection, session: CookieSession, isFirstVisit: Boolean) {
        val sessionId = session.getOrPut("bn_session") {
            "BN${System.currentTimeMillis()}${Random.nextInt(1000, 9999)}"
        }

        val cookies = mutableMapOf<String, String>()

        if (isFirstVisit) {
            cookies["_ga"] = "GA1.1.${Random.nextLong(100000000, 999999999)}.${System.currentTimeMillis() / 1000}"
            cookies["_gid"] = "GA1.1.${Random.nextLong(100000000, 999999999)}"
            cookies["_gcl_au"] = "1.${System.currentTimeMillis()}.${Random.nextLong(100000000, 999999999)}"
            cookies["bn_session_data"] = sessionId
            cookies["bn_locale"] = "en_US"
            cookies["bn_country"] = "US"
            cookies["bn_currency"] = "USD"
            cookies["bn_consent"] = "true"
        } else {
            cookies["_ga"] = session.getOrPut("bn_ga") {
                "GA1.1.${Random.nextLong(100000000, 999999999)}.${System.currentTimeMillis() / 1000}"
            }
            cookies["_gid"] = "GA1.1.${Random.nextLong(100000000, 999999999)}"
            cookies["_gcl_au"] = session.getOrPut("bn_gcl") {
                "1.${System.currentTimeMillis()}.${Random.nextLong(100000000, 999999999)}"
            }
            cookies["bn_session_data"] = sessionId
            cookies["bn_user_type"] = "returning"
            cookies["bn_locale"] = "en_US"
            cookies["bn_country"] = "US"
            cookies["bn_currency"] = "USD"
            cookies["bn_consent"] = "true"
        }

        cookies["bnVisitorId"] = session.getOrPut("bn_visitor") {
            "BNV${Random.nextLong(1000000000000L, 9999999999999L)}"
        }
        cookies["tracking_preference"] = "accepted"
        cookies["cookie-consent"] = "true"

        val realCookies = session.matching("bn_", stripPrefix = true)
        if (realCookies.isNotEmpty()) {
            cookies.putAll(realCookies)
        }

        cookies.forEach { (key, value) ->
            when (key) {
                "_ga" -> session["bn_ga"] = value
                "_gid" -> session["bn_gid"] = value
                "_gcl_au" -> session["bn_gcl"] = value
                "bn_session_data" -> session["bn_session"] = value
                "bnVisitorId" -> session["bn_visitor"] = value
            }
        }

        connection.cookies(cookies)
    }
}
