package com.booktracker.booksidntneed.ui

import android.text.SpannableString
import android.text.style.TypefaceSpan
import android.view.View
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

// Material 3 Expressive spring constants
private const val SPRING_DAMPING_RATIO = SpringForce.DAMPING_RATIO_LOW_BOUNCY

const val SAVING_TO_LIBRARY_BASE = "Saving to library"
const val SAVING_TO_LIBRARY_DOT_SLOTS = 3
const val SAVING_TO_LIBRARY_DOT_INTERVAL_MS = 400L

fun isSavingToLibraryStatus(text: String): Boolean =
    text.startsWith(SAVING_TO_LIBRARY_BASE, ignoreCase = true)

/** Sets "Saving to library" with [dotCount] visible dots (0–3) in a fixed-width suffix. */
fun TextView.setSavingToLibraryDots(dotCount: Int) {
    val clampedCount = dotCount.coerceIn(0, SAVING_TO_LIBRARY_DOT_SLOTS)
    val dots = ".".repeat(clampedCount).padEnd(SAVING_TO_LIBRARY_DOT_SLOTS, ' ')
    val fullText = SAVING_TO_LIBRARY_BASE + dots
    val spannable = SpannableString(fullText)
    spannable.setSpan(
        TypefaceSpan("monospace"),
        SAVING_TO_LIBRARY_BASE.length,
        fullText.length,
        SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
    )
    text = spannable
}

/**
 * Animates a quick press effect on the view using spring physics.
 * Scales the view down briefly and then returns it to normal size with a slight overshoot.
 */
fun View.animateClick() {
    // Quick press animation
    val scaleDownX = SpringAnimation(this, DynamicAnimation.SCALE_X, 0.95f).apply {
        spring.stiffness = SpringForce.STIFFNESS_HIGH
        spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
        addEndListener { _, _, _, _ ->
            // Return to normal with slight overshoot
            SpringAnimation(this@animateClick, DynamicAnimation.SCALE_X, 1.0f).apply {
                spring.stiffness = SpringForce.STIFFNESS_MEDIUM
                spring.dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
            }.start()
        }
    }
    val scaleDownY = SpringAnimation(this, DynamicAnimation.SCALE_Y, 0.95f).apply {
        spring.stiffness = SpringForce.STIFFNESS_HIGH
        spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
        addEndListener { _, _, _, _ ->
            SpringAnimation(this@animateClick, DynamicAnimation.SCALE_Y, 1.0f).apply {
                spring.stiffness = SpringForce.STIFFNESS_MEDIUM
                spring.dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
            }.start()
        }
    }
    scaleDownX.start()
    scaleDownY.start()
}

/**
 * Animates a celebratory bounce effect on the view using spring physics.
 * Scales the view up and then returns it to normal size with a bounce.
 */
fun View.animateSuccessBounce() {
    // Celebratory bounce effect
    val bounceAnimation = SpringAnimation(this, DynamicAnimation.SCALE_X, 1.1f).apply {
        spring.stiffness = SpringForce.STIFFNESS_HIGH
        spring.dampingRatio = SpringForce.DAMPING_RATIO_HIGH_BOUNCY
        addEndListener { _, _, _, _ ->
            SpringAnimation(this@animateSuccessBounce, DynamicAnimation.SCALE_X, 1.0f).apply {
                spring.stiffness = SpringForce.STIFFNESS_MEDIUM
                spring.dampingRatio = SPRING_DAMPING_RATIO
            }.start()
        }
    }
    val bounceYAnimation = SpringAnimation(this, DynamicAnimation.SCALE_Y, 1.1f).apply {
        spring.stiffness = SpringForce.STIFFNESS_HIGH
        spring.dampingRatio = SpringForce.DAMPING_RATIO_HIGH_BOUNCY
        addEndListener { _, _, _, _ ->
            SpringAnimation(this@animateSuccessBounce, DynamicAnimation.SCALE_Y, 1.0f).apply {
                spring.stiffness = SpringForce.STIFFNESS_MEDIUM
                spring.dampingRatio = SPRING_DAMPING_RATIO
            }.start()
        }
    }
    bounceAnimation.start()
    bounceYAnimation.start()
}

/**
 * Animates a status text change on a TextView using spring physics.
 * Fades out the text if not visible, or fades in new text if visible.
 * @param newText The new text to display.
 * @param visible Whether the text should be visible after the animation.
 */
fun TextView.animateStatusTextChange(newText: String, visible: Boolean) {
    if (!visible) {
        // Fade out with spring
        val fadeOutAnimation = SpringAnimation(this, DynamicAnimation.ALPHA, 0f).apply {
            spring.stiffness = SpringForce.STIFFNESS_HIGH
            spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            addEndListener { _, _, _, _ ->
                this@animateStatusTextChange.visibility = View.GONE
            }
        }
        fadeOutAnimation.start()
    } else {
        // Update text and animate in with spring
        this.text = newText
        this.visibility = View.VISIBLE
        this.alpha = 0f
        val fadeInAnimation = SpringAnimation(this, DynamicAnimation.ALPHA, 1f).apply {
            spring.stiffness = SpringForce.STIFFNESS_MEDIUM
            spring.dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
        }
        fadeInAnimation.start()
    }
}

