package com.booktracker.booksidntneed.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.ContextCompat

enum class HapticType {
    SOFT_PRESS,     // Light touch feedback
    CONFIRMATION,   // Action completed
    SELECTION,      // Item selected
    BOUNDARY,       // Scroll boundary reached
    ERROR,          // Error occurred
    SUCCESS         // Success action
}

/**
 * Provides haptic feedback following Material 3 Expressive guidelines.
 * Uses modern VibrationEffect APIs with springy, expressive patterns.
 * @param type The type of haptic feedback to provide (see HapticType).
 */
fun Context.provideHapticFeedback(type: HapticType) {
    val vibrator = ContextCompat.getSystemService(this, Vibrator::class.java) ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val effect = when (type) {
            HapticType.SOFT_PRESS -> VibrationEffect.createOneShot(8, VibrationEffect.DEFAULT_AMPLITUDE)
            HapticType.CONFIRMATION -> VibrationEffect.createOneShot(40, 120)
            HapticType.SELECTION -> VibrationEffect.createOneShot(20, 80)
            HapticType.BOUNDARY -> VibrationEffect.createWaveform(
                longArrayOf(0, 12, 8, 12), // Springy double-pulse pattern
                intArrayOf(0, 100, 0, 80), // Amplitude pattern for spring effect
                -1
            )
            HapticType.ERROR -> VibrationEffect.createWaveform(
                longArrayOf(0, 50, 30, 50, 30, 50), // Error pattern
                intArrayOf(0, 150, 0, 120, 0, 100), // Decreasing amplitude
                -1
            )
            HapticType.SUCCESS -> VibrationEffect.createWaveform(
                longArrayOf(0, 30, 15, 30, 15, 30), // Success pattern
                intArrayOf(0, 100, 0, 80, 0, 60), // Gentle decreasing amplitude
                -1
            )
        }
        vibrator.vibrate(effect)
    } else {
        // Fallback for older devices
        val duration = when (type) {
            HapticType.SOFT_PRESS -> 8L
            HapticType.CONFIRMATION -> 40L
            HapticType.SELECTION -> 20L
            HapticType.BOUNDARY -> 32L // Total duration of springy pattern
            HapticType.ERROR -> 160L
            HapticType.SUCCESS -> 120L
        }
        @Suppress("DEPRECATION")
        vibrator.vibrate(duration)
    }
} 