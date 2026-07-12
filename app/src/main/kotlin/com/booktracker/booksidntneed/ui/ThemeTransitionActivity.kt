package com.booktracker.booksidntneed.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.WindowCompat
import androidx.core.view.doOnPreDraw
import com.booktracker.booksidntneed.ui.dialog.DialogStyling
import com.booktracker.booksidntneed.utils.AppThemeMode
import com.booktracker.booksidntneed.utils.ThemePreferences

class ThemeTransitionActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var themeApplied = false
    private var oldFrameView: ImageView? = null
    private var restoredFrameView: ImageView? = null
    private var oldSnapshotToRecycle: Bitmap? = null
    private var restoredSnapshotToRecycle: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        overrideOpenTransitionWithoutAnimation()
        super.onCreate(savedInstanceState)

        val snapshot = ThemeTransitionSnapshot.peek()
        if (snapshot == null) {
            finishWithoutAnimation()
            return
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        window.setTransparentSystemBarColorsCompat()
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply {
            dimAmount = DialogStyling.BACKGROUND_DIM_AMOUNT
        }

        val restoredFrame = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val oldFrame = ImageView(this).apply {
            setImageBitmap(snapshot)
            scaleType = ImageView.ScaleType.FIT_XY
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        restoredFrameView = restoredFrame
        oldFrameView = oldFrame
        val content = FrameLayout(this).apply {
            addView(restoredFrame, matchParentParams())
            addView(oldFrame, matchParentParams())
        }
        setContentView(
            content,
            matchParentParams()
        )

        content.doOnPreDraw {
            content.post { applyThemeAndWait(oldFrame, restoredFrame) }
        }
    }

    override fun finish() {
        super.finish()
        overrideCloseTransitionWithoutAnimation()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        oldFrameView?.animate()?.cancel()
        oldFrameView?.setImageDrawable(null)
        restoredFrameView?.setImageDrawable(null)
        oldFrameView = null
        restoredFrameView = null
        if (!isChangingConfigurations && ThemeTransitionSnapshot.isThemeTransition) {
            ThemeTransitionSnapshot.cancelTransition()
        }
        oldSnapshotToRecycle?.recycle()
        restoredSnapshotToRecycle?.recycle()
        oldSnapshotToRecycle = null
        restoredSnapshotToRecycle = null
        super.onDestroy()
    }

    private fun applyThemeAndWait(oldFrame: View, restoredFrame: ImageView) {
        if (!themeApplied) {
            themeApplied = true
            ThemePreferences.applyTheme(targetThemeMode())
        }
        waitForRestoredSettings(oldFrame, restoredFrame, attemptsRemaining = 60)
    }

    private fun waitForRestoredSettings(
        oldFrame: View,
        restoredFrame: ImageView,
        attemptsRemaining: Int
    ) {
        val restoredSnapshot = ThemeTransitionSnapshot.peekRestored()
        if (
            (ThemeTransitionSnapshot.isRestoredDialogReady && restoredSnapshot != null) ||
            attemptsRemaining <= 0
        ) {
            restoredFrame.setImageBitmap(restoredSnapshot)
            fadeOutAndFinish(oldFrame)
            return
        }

        mainHandler.postDelayed({
            waitForRestoredSettings(oldFrame, restoredFrame, attemptsRemaining - 1)
        }, RESTORED_DIALOG_POLL_INTERVAL_MS)
    }

    private fun fadeOutAndFinish(oldFrame: View) {
        oldFrame.animate()
            .alpha(0f)
            .setDuration(220L)
            .setInterpolator(LinearInterpolator())
            .withEndAction {
                val snapshot = ThemeTransitionSnapshot.take()
                val restoredSnapshot = ThemeTransitionSnapshot.takeRestored()
                ThemeTransitionSnapshot.clearTransition()
                oldSnapshotToRecycle = snapshot
                restoredSnapshotToRecycle = restoredSnapshot
                mainHandler.postDelayed({ finishWithoutAnimation() }, LIVE_DIM_SETTLE_DELAY_MS)
            }
            .start()
    }

    private fun targetThemeMode(): AppThemeMode {
        val value = intent.getStringExtra(EXTRA_THEME_MODE)
        return AppThemeMode.fromPreferenceValue(value)
    }

    private fun finishWithoutAnimation() {
        finish()
    }

    private fun matchParentParams(): ViewGroup.LayoutParams =
        ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

    companion object {
        private const val EXTRA_THEME_MODE = "theme_mode"
        private const val RESTORED_DIALOG_POLL_INTERVAL_MS = 16L
        private const val LIVE_DIM_SETTLE_DELAY_MS = 24L

        fun start(context: Context, mode: AppThemeMode) {
            val intent = Intent(context, ThemeTransitionActivity::class.java)
                .putExtra(EXTRA_THEME_MODE, mode.preferenceValue)
                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(intent)
            (context as? Activity)?.overrideOpenTransitionWithoutAnimation()
        }
    }
}

private fun Activity.overrideOpenTransitionWithoutAnimation() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
    } else {
        overridePendingTransitionCompat()
    }
}

private fun Activity.overrideCloseTransitionWithoutAnimation() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
    } else {
        overridePendingTransitionCompat()
    }
}

@Suppress("DEPRECATION")
private fun Activity.overridePendingTransitionCompat() {
    overridePendingTransition(0, 0)
}

private fun Window.setTransparentSystemBarColorsCompat() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        setLegacyTransparentSystemBarColors()
    }
}

@Suppress("DEPRECATION")
private fun Window.setLegacyTransparentSystemBarColors() {
    statusBarColor = Color.TRANSPARENT
    navigationBarColor = Color.TRANSPARENT
}
