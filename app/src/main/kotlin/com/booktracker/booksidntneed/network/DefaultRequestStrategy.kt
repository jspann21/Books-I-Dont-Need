package com.booktracker.booksidntneed.network

import org.jsoup.Connection

class DefaultRequestStrategy : StoreRequestStrategy {
    override val storeName: String = "Default"

    override fun canHandle(url: String): Boolean = true

    override fun configureRequest(connection: Connection, attempt: Int, session: CookieSession) {
        connection
            .userAgent(RequestStrategyUtils.DESKTOP_USER_AGENT)
            .timeout(RequestStrategyUtils.DEFAULT_CONNECTION_TIMEOUT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.5")
    }
}
