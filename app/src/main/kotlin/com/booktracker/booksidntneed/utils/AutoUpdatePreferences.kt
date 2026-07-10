package com.booktracker.booksidntneed.utils

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// DataStore instance tied to application context
val Context.autoUpdateDataStore by preferencesDataStore(name = "auto_update_prefs")

object AutoUpdatePreferences {
    private val KEY_ENABLED: Preferences.Key<Boolean> = booleanPreferencesKey("auto_update_enabled")
    // Minutes since midnight local time (0..1439)
    private val KEY_TIME_MINUTES: Preferences.Key<Int> = intPreferencesKey("auto_update_time_minutes")
    private val KEY_RECENT_CHANGES_JSON: Preferences.Key<String> = stringPreferencesKey("recent_price_changes_json")

    fun isEnabled(context: Context): Flow<Boolean> =
        context.autoUpdateDataStore.data.map { it[KEY_ENABLED] ?: false }

    suspend fun setEnabled(context: Context, enabled: Boolean) {
        context.autoUpdateDataStore.edit { it[KEY_ENABLED] = enabled }
    }

    fun timeMinutes(context: Context): Flow<Int> =
        context.autoUpdateDataStore.data.map { it[KEY_TIME_MINUTES] ?: (8 * 60) } // default 8:00 AM

    suspend fun setTimeMinutes(context: Context, minutes: Int) {
        val clamped = minutes.coerceIn(0, 24 * 60 - 1)
        context.autoUpdateDataStore.edit { it[KEY_TIME_MINUTES] = clamped }
    }

    fun recentChangesJson(context: Context): Flow<String?> =
        context.autoUpdateDataStore.data.map { it[KEY_RECENT_CHANGES_JSON] }

    suspend fun setRecentChangesJson(context: Context, json: String?) {
        context.autoUpdateDataStore.edit { prefs ->
            if (json == null) prefs.remove(KEY_RECENT_CHANGES_JSON) else prefs[KEY_RECENT_CHANGES_JSON] = json
        }
    }

}

data class PriceChangeEntry(
    val bookId: Long,
    val bookTitle: String,
    val storeName: String,
    val oldPrice: Double?,
    val newPrice: Double?,
    val timestamp: Long
)

data class UpdateSummary(
    val totalChecked: Int,
    val changed: Int,
    val drops: Int,
    val increases: Int,
    val failed: Int,
    val skipped: Int,
    val changes: List<PriceChangeEntry>,
    val completedAt: Long
)

