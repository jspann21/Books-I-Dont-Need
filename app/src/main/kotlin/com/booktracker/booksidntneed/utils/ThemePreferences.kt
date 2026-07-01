package com.booktracker.booksidntneed.utils

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.appcompat.app.AppCompatDelegate

enum class AppThemeMode(val preferenceValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    val nightMode: Int
        get() = when (this) {
            SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }

    companion object {
        fun fromPreferenceValue(value: String?): AppThemeMode =
            entries.firstOrNull { it.preferenceValue == value } ?: SYSTEM
    }
}

object ThemePreferences {
    private const val PREFS_NAME = "theme_prefs"
    private const val KEY_THEME_MODE = "theme_mode"

    fun getThemeMode(context: Context): AppThemeMode {
        val value = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THEME_MODE, AppThemeMode.SYSTEM.preferenceValue)
        return AppThemeMode.fromPreferenceValue(value)
    }

    fun setThemeMode(context: Context, mode: AppThemeMode) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_MODE, mode.preferenceValue)
            .commit()
    }

    fun applyTheme(mode: AppThemeMode) {
        AppCompatDelegate.setDefaultNightMode(mode.nightMode)
    }

    fun applySavedTheme(context: Context) {
        applyTheme(getThemeMode(context))
    }

    fun changesEffectiveNightMode(
        context: Context,
        currentMode: AppThemeMode,
        nextMode: AppThemeMode
    ): Boolean {
        return effectiveNightMode(context, currentMode) != effectiveNightMode(context, nextMode)
    }

    private fun effectiveNightMode(context: Context, mode: AppThemeMode): Int {
        return when (mode) {
            AppThemeMode.SYSTEM -> systemNightMode(context)
            AppThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            AppThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }
    }

    private fun systemNightMode(context: Context): Int {
        val uiModeManager = context.getSystemService(UiModeManager::class.java)
        return when (uiModeManager?.nightMode) {
            UiModeManager.MODE_NIGHT_YES -> AppCompatDelegate.MODE_NIGHT_YES
            UiModeManager.MODE_NIGHT_NO -> AppCompatDelegate.MODE_NIGHT_NO
            else -> configurationNightMode(Resources.getSystem().configuration)
        }
    }

    private fun configurationNightMode(configuration: Configuration): Int {
        val isNightMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return if (isNightMode) {
            AppCompatDelegate.MODE_NIGHT_YES
        } else {
            AppCompatDelegate.MODE_NIGHT_NO
        }
    }
}
