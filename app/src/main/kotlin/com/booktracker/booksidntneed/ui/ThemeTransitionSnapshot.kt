package com.booktracker.booksidntneed.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import com.booktracker.booksidntneed.utils.AppThemeMode
import com.booktracker.booksidntneed.utils.ThemePreferences

/**
 * Singleton that holds a screenshot bitmap captured right before a theme
 * change so the new activity can fade it out for a seamless transition.
 *
 * Also carries a [isThemeTransition] flag so that dialogs being restored
 * by the fragment manager can suppress their enter animations until the
 * fade overlay is in place.
 */
object ThemeTransitionSnapshot {
    private data class DialogLayerSnapshot(
        val bitmap: Bitmap,
        val left: Float,
        val top: Float,
        val dimAmount: Float
    )

    private var snapshot: Bitmap? = null
    private var restoredSnapshot: Bitmap? = null
    private var cachedDialogLayer: DialogLayerSnapshot? = null
    private var restoredDialogReady: Boolean = false

    /** True while a theme-change recreation is in progress. */
    var isThemeTransition: Boolean = false
        private set

    val isRestoredDialogReady: Boolean
        get() = restoredDialogReady

    fun set(bitmap: Bitmap?) {
        snapshot?.recycle()
        snapshot = bitmap
    }

    /** Returns the old-theme snapshot without consuming it. */
    fun peek(): Bitmap? = snapshot

    /** Returns the restored new-theme snapshot without consuming it. */
    fun peekRestored(): Bitmap? = restoredSnapshot

    /** Returns the old-theme snapshot and clears the reference. */
    fun take(): Bitmap? {
        val bitmap = snapshot
        snapshot = null
        return bitmap
    }

    /** Returns the restored new-theme snapshot and clears the reference. */
    fun takeRestored(): Bitmap? {
        val bitmap = restoredSnapshot
        restoredSnapshot = null
        return bitmap
    }

    /** Called once the fade-out completes and the transition is done. */
    fun clearTransition() {
        isThemeTransition = false
        restoredDialogReady = false
    }

    fun clearCachedDialogLayer() {
        cachedDialogLayer?.bitmap?.recycle()
        cachedDialogLayer = null
    }

    fun cacheDialogWindow(
        activity: Activity,
        dialogWindow: Window?,
        dimAmount: Float
    ) {
        val activityDecor = activity.window.decorView
        val dialogDecor = dialogWindow?.decorView ?: return
        if (
            activityDecor.width <= 0 ||
            activityDecor.height <= 0 ||
            dialogDecor.width <= 0 ||
            dialogDecor.height <= 0
        ) {
            return
        }

        val activityLocation = IntArray(2)
        val dialogLocation = IntArray(2)
        activityDecor.getLocationOnScreen(activityLocation)
        dialogDecor.getLocationOnScreen(dialogLocation)
        val left = (dialogLocation[0] - activityLocation[0]).toFloat()
        val top = (dialogLocation[1] - activityLocation[1]).toFloat()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val dialogBitmap = Bitmap.createBitmap(
                dialogDecor.width,
                dialogDecor.height,
                Bitmap.Config.ARGB_8888
            )
            runCatching {
                PixelCopy.request(
                    dialogWindow,
                    dialogBitmap,
                    { result ->
                        if (result == PixelCopy.SUCCESS) {
                            storeCachedDialogLayer(dialogBitmap, left, top, dimAmount)
                        } else {
                            dialogBitmap.recycle()
                            cacheDialogWindowByDrawing(dialogDecor, left, top, dimAmount)
                        }
                    },
                    Handler(Looper.getMainLooper())
                )
            }.onFailure {
                dialogBitmap.recycle()
                cacheDialogWindowByDrawing(dialogDecor, left, top, dimAmount)
            }
            return
        }

        cacheDialogWindowByDrawing(dialogDecor, left, top, dimAmount)
    }

    fun markRestoredDialogReady() {
        if (isThemeTransition) {
            restoredDialogReady = true
        }
    }

    fun captureRestoredFrame(
        activity: Activity,
        dialogWindow: Window?,
        dialogDimAmount: Float
    ) {
        val bitmap = captureFrameByDrawing(activity, dialogWindow, dialogDimAmount) ?: return
        restoredSnapshot?.recycle()
        restoredSnapshot = bitmap
    }

    fun installRestoredBackgroundCover(activity: Activity) {
        val coverBitmap = restoredSnapshot
            ?.copy(Bitmap.Config.ARGB_8888, false)
            ?: return
        val decor = activity.window.decorView as? ViewGroup ?: run {
            coverBitmap.recycle()
            return
        }
        val cover = ImageView(activity).apply {
            setImageBitmap(coverBitmap)
            scaleType = ImageView.ScaleType.FIT_XY
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        decor.addView(
            cover,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        cover.postDelayed({
            (cover.parent as? ViewGroup)?.removeView(cover)
            cover.setImageDrawable(null)
            coverBitmap.recycle()
        }, RESTORED_BACKGROUND_COVER_MS)
    }

    /**
     * Captures the visible app window, optionally compositing an active
     * dialog window above its dimmed backdrop, then applies the new theme.
     * Dialogs live in separate windows, so drawing only the activity decor
     * would miss the settings dialog that initiated the theme change.
     */
    fun captureAndApply(
        activity: Activity,
        mode: AppThemeMode,
        dialogWindow: Window? = null,
        dialogDimAmount: Float = 0f
    ) {
        val decor = activity.window.decorView as? ViewGroup
        if (decor == null || decor.width <= 0 || decor.height <= 0) {
            ThemePreferences.applyTheme(mode)
            return
        }

        val bitmap = runCatching {
            val bitmap = Bitmap.createBitmap(
                decor.width, decor.height, Bitmap.Config.ARGB_8888
            )
            decor.draw(Canvas(bitmap))
            bitmap
        }.getOrNull()

        if (bitmap == null) {
            ThemePreferences.applyTheme(mode)
            return
        }

        val dialogDecor = dialogWindow?.decorView
        val canvas = Canvas(bitmap)
        val drewLiveDialogLayer = runCatching {
            if (dialogDecor != null && dialogDecor.width > 0 && dialogDecor.height > 0) {
                drawDialogLayer(canvas, decor, dialogDecor, dialogDimAmount)
                true
            } else {
                false
            }
        }.getOrDefault(false)
        if (!drewLiveDialogLayer) {
            drawCachedDialogLayer(canvas)
        }
        startThemeTransition(activity, bitmap, mode)
    }

    private fun captureFrameByDrawing(
        activity: Activity,
        dialogWindow: Window?,
        dialogDimAmount: Float
    ): Bitmap? {
        val decor = activity.window.decorView as? ViewGroup
        if (decor == null || decor.width <= 0 || decor.height <= 0) return null

        return runCatching {
            val bitmap = Bitmap.createBitmap(
                decor.width,
                decor.height,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            decor.draw(canvas)
            drawDialogLayer(canvas, decor, dialogWindow?.decorView, dialogDimAmount)
            bitmap
        }.getOrNull()
    }

    private fun cacheDialogWindowByDrawing(
        dialogDecor: View,
        left: Float,
        top: Float,
        dimAmount: Float
    ) {
        val dialogBitmap = runCatching {
            Bitmap.createBitmap(
                dialogDecor.width,
                dialogDecor.height,
                Bitmap.Config.ARGB_8888
            ).also { dialogDecor.draw(Canvas(it)) }
        }.getOrNull() ?: return

        storeCachedDialogLayer(dialogBitmap, left, top, dimAmount)
    }

    private fun storeCachedDialogLayer(
        dialogBitmap: Bitmap,
        left: Float,
        top: Float,
        dimAmount: Float
    ) {
        cachedDialogLayer?.bitmap?.recycle()
        cachedDialogLayer = DialogLayerSnapshot(
            bitmap = dialogBitmap,
            left = left,
            top = top,
            dimAmount = effectiveDialogDimAmount(dimAmount)
        )
    }

    private fun startThemeTransition(activity: Activity, bitmap: Bitmap, mode: AppThemeMode) {
        set(bitmap)
        isThemeTransition = true
        restoredDialogReady = false
        runCatching { ThemeTransitionActivity.start(activity, mode) }
            .onFailure { ThemePreferences.applyTheme(mode) }
    }

    private fun drawDialogLayer(
        canvas: Canvas,
        activityDecor: View,
        dialogDecor: View?,
        dimAmount: Float
    ) {
        if (dialogDecor == null || dialogDecor.width <= 0 || dialogDecor.height <= 0) return

        val effectiveDimAmount = effectiveDialogDimAmount(dimAmount)
        if (effectiveDimAmount > 0f) {
            val alpha = (effectiveDimAmount * 255).toInt()
            canvas.drawColor(Color.argb(alpha, 0, 0, 0))
        }

        val activityLocation = IntArray(2)
        val dialogLocation = IntArray(2)
        activityDecor.getLocationOnScreen(activityLocation)
        dialogDecor.getLocationOnScreen(dialogLocation)

        canvas.save()
        canvas.translate(
            (dialogLocation[0] - activityLocation[0]).toFloat(),
            (dialogLocation[1] - activityLocation[1]).toFloat()
        )
        dialogDecor.draw(canvas)
        canvas.restore()
    }

    private fun drawCachedDialogLayer(canvas: Canvas): Boolean {
        val dialogLayer = cachedDialogLayer ?: return false
        if (dialogLayer.bitmap.isRecycled) return false

        val effectiveDimAmount = effectiveDialogDimAmount(dialogLayer.dimAmount)
        if (effectiveDimAmount > 0f) {
            val alpha = (effectiveDimAmount * 255).toInt()
            canvas.drawColor(Color.argb(alpha, 0, 0, 0))
        }

        canvas.drawBitmap(dialogLayer.bitmap, dialogLayer.left, dialogLayer.top, null)
        return true
    }

    private fun effectiveDialogDimAmount(dimAmount: Float): Float {
        return dimAmount.coerceIn(0f, 1f)
    }

    private const val RESTORED_BACKGROUND_COVER_MS = 1600L
}
