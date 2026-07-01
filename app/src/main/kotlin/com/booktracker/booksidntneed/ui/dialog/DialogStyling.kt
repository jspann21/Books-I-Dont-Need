package com.booktracker.booksidntneed.ui.dialog

import android.app.Dialog
import android.view.ViewGroup
import android.view.WindowManager
import kotlin.math.min

object DialogStyling {
    const val BACKGROUND_DIM_AMOUNT = 0.48f

    fun apply(dialog: Dialog?, widthFraction: Float = 0.92f, maxWidthDp: Int = 560) {
        val window = dialog?.window ?: return
        val metrics = window.context.resources.displayMetrics
        val targetWidth = (metrics.widthPixels * widthFraction).toInt()
        val maxWidth = (maxWidthDp * metrics.density).toInt()

        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply {
            dimAmount = BACKGROUND_DIM_AMOUNT
        }
        window.setLayout(min(targetWidth, maxWidth), ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setWindowAnimations(com.booktracker.booksidntneed.R.style.Animation_BooksIDontNeed_Dialog)
    }
}
