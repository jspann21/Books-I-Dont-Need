package com.booktracker.booksidntneed.work

import android.os.SystemClock
import android.util.Log
import com.booktracker.booksidntneed.network.RequestStrategyUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps bulk price updates quick without producing request bursts that look automated.
 *
 * Requests may run in parallel across different hosts, but requests to the same host are
 * serialized and spaced apart. Store-specific request strategies can still add their own
 * randomized delays inside the network layer.
 */
class ResponsiblePriceUpdateLimiter(
    maxConcurrentRequests: Int = DEFAULT_MAX_CONCURRENT_REQUESTS
) {
    private val globalSemaphore = Semaphore(maxConcurrentRequests)
    private val hostLocks = ConcurrentHashMap<String, Mutex>()
    private val nextAllowedAtByHost = ConcurrentHashMap<String, Long>()

    suspend fun <T> run(storeUrl: String, block: suspend () -> T): T {
        val host = RequestStrategyUtils.extractHost(storeUrl) ?: UNKNOWN_HOST
        val hostLock = hostLockFor(host)
        return hostLock.withLock {
            waitForHostSlot(host)
            globalSemaphore.withPermit {
                try {
                    block()
                } finally {
                    nextAllowedAtByHost[host] = SystemClock.elapsedRealtime() + delayForHost(host)
                }
            }
        }
    }

    private fun hostLockFor(host: String): Mutex {
        return hostLocks.getOrPut(host) { Mutex() }
    }

    private suspend fun waitForHostSlot(host: String) {
        val waitMs = ((nextAllowedAtByHost[host] ?: 0L) - SystemClock.elapsedRealtime())
            .coerceAtLeast(0L)
        if (waitMs > 0L) {
            Log.d(TAG, "Waiting ${waitMs}ms before next $host price request")
            delay(waitMs)
        }
    }

    private fun delayForHost(host: String): Long {
        return when {
            host.contains("amazon.") ||
                host.contains("walmart.") ||
                host.contains("ebay.") -> SENSITIVE_HOST_DELAY_MS
            else -> DEFAULT_HOST_DELAY_MS
        }
    }

    companion object {
        private const val TAG = "PriceUpdateLimiter"
        private const val UNKNOWN_HOST = "unknown"
        private const val DEFAULT_MAX_CONCURRENT_REQUESTS = 3
        private const val DEFAULT_HOST_DELAY_MS = 2_500L
        private const val SENSITIVE_HOST_DELAY_MS = 5_000L
    }
}
