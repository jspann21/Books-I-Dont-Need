package com.booktracker.booksidntneed.ui.dialog

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import kotlin.math.min

object DialogStyling {
    fun apply(dialog: Dialog?, widthFraction: Float = 0.92f, maxWidthDp: Int = 560) {
        val window = dialog?.window ?: return
        val metrics = window.context.resources.displayMetrics
        val targetWidth = (metrics.widthPixels * widthFraction).toInt()
        val maxWidth = (maxWidthDp * metrics.density).toInt()

        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setDimAmount(0.48f)
        window.setLayout(min(targetWidth, maxWidth), ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
