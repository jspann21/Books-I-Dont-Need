package com.booktracker.booksidntneed.utils

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.booktracker.booksidntneed.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics

object ErrorReporter {
    private const val TAG = "ErrorReporter"
    private const val MAX_KEY_LENGTH = 40
    private const val MAX_VALUE_LENGTH = 100
    private const val MAX_LOG_LENGTH = 2048

    @Volatile
    private var initialized = false

    private var analytics: FirebaseAnalytics? = null

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        val firebaseApp = runCatching {
            FirebaseApp.getApps(appContext).firstOrNull() ?: FirebaseApp.initializeApp(appContext)
        }.onFailure { error ->
            Log.i(TAG, "Firebase is not configured yet: ${error.message}")
        }.getOrNull()

        if (firebaseApp == null) {
            initialized = false
            return
        }

        runCatching {
            FirebaseCrashlytics.getInstance().apply {
                setCustomKey("version_name", BuildConfig.VERSION_NAME)
                setCustomKey("version_code", BuildConfig.VERSION_CODE.toString())
                setCustomKey("build_type", BuildConfig.BUILD_TYPE)
                log("App started")
            }
            analytics = FirebaseAnalytics.getInstance(appContext)
            initialized = true
        }.onFailure { error ->
            initialized = false
            Log.w(TAG, "Unable to initialize Firebase reporting", error)
        }
    }

    fun logEvent(name: String, params: Map<String, String> = emptyMap()) {
        if (!initialized) return

        runCatching {
            val bundle = Bundle().apply {
                params.forEach { (key, value) ->
                    putString(key.sanitizeKey(), value.sanitizeValue())
                }
            }
            analytics?.logEvent(name.sanitizeEventName(), bundle)
        }.onFailure { error ->
            Log.w(TAG, "Unable to log analytics event: $name", error)
        }
    }

    fun recordException(
        throwable: Throwable,
        message: String,
        keys: Map<String, String> = emptyMap()
    ) {
        Log.e(TAG, message, throwable)

        if (!initialized) return

        runCatching {
            FirebaseCrashlytics.getInstance().apply {
                log(message.take(MAX_LOG_LENGTH))
                keys.forEach { (key, value) ->
                    setCustomKey(key.sanitizeKey(), value.sanitizeValue())
                }
                recordException(throwable)
            }
            logEvent(
                "non_fatal_error",
                mapOf("source" to (keys["source"] ?: "unknown"))
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to record exception", error)
        }
    }

    private fun String.sanitizeKey(): String {
        return replace(Regex("[^A-Za-z0-9_]"), "_")
            .take(MAX_KEY_LENGTH)
            .ifBlank { "unknown" }
    }

    private fun String.sanitizeEventName(): String {
        val sanitized = replace(Regex("[^A-Za-z0-9_]"), "_")
            .take(MAX_KEY_LENGTH)
            .ifBlank { "app_event" }
        return if (sanitized.first().isLetter()) sanitized else "event_$sanitized"
    }

    private fun String.sanitizeValue(): String = take(MAX_VALUE_LENGTH)
}
