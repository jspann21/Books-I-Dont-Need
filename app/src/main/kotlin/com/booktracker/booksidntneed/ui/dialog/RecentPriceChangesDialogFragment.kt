package com.booktracker.booksidntneed.ui.dialog

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import com.booktracker.booksidntneed.R
import com.booktracker.booksidntneed.utils.AutoUpdatePreferences
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

class RecentPriceChangesDialogFragment : DialogFragment() {

    // Use lifecycleScope to avoid leaking a custom scope
    private val currencyFormatter = NumberFormat.getCurrencyInstance(Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.ThemeOverlay_BooksIDontNeed_Dialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_recent_changes, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialog?.setTitle(getString(R.string.recent_price_changes_title))
        val container = view.findViewById<LinearLayout>(R.id.changesContainer)
        val summaryView = view.findViewById<TextView>(R.id.summaryText)
        val json = arguments?.getString(ARG_JSON) ?: ""
        if (json.isNotBlank()) {
            try {
                val obj = JSONObject(json)
                val arr = obj.optJSONArray("changes")
                val failures = obj.optJSONArray("failures")
                val totalChecked = obj.optInt("totalChecked", 0)
                val changed = obj.optInt("changed", arr?.length() ?: 0)
                val failureCount = obj.optInt("failed", failures?.length() ?: 0)
                val skippedCount = obj.optInt("skipped", 0)

                if (failureCount > 0 || skippedCount > 0) {
                    view.findViewById<TextView>(R.id.dialogTitle).setText(R.string.update_results_title)
                    summaryView.text = getString(
                        R.string.update_results_summary,
                        totalChecked,
                        changed,
                        failureCount,
                        skippedCount
                    )
                } else {
                    summaryView.text = getString(R.string.recent_price_changes_summary, totalChecked, changed)
                }

                if (arr != null && arr.length() > 0) {
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        val bookTitle = item.optString("bookTitle")
                        val storeName = item.optString("storeName")
                        val hasOld = item.has("oldPrice") && !item.isNull("oldPrice")
                        val hasNew = item.has("newPrice") && !item.isNull("newPrice")
                        val oldPrice = if (hasOld) item.getDouble("oldPrice") else null
                        val newPrice = if (hasNew) item.getDouble("newPrice") else null
                        val isDrop = oldPrice != null && newPrice != null && newPrice < oldPrice
                        val priceText = when {
                            oldPrice != null && newPrice != null ->
                                getString(R.string.price_change_price_range, formatPrice(oldPrice), formatPrice(newPrice))
                            newPrice != null -> getString(R.string.price_change_new_price, formatPrice(newPrice))
                            else -> ""
                        }
                        val row = layoutInflater.inflate(R.layout.item_price_change, container, false)
                        val titleView = row.findViewById<TextView>(R.id.changeTitle)
                        val storeView = row.findViewById<TextView>(R.id.changeStore)
                        val pricesView = row.findViewById<TextView>(R.id.changePrices)
                        val chip = row.findViewById<Chip>(R.id.changeChip)
                        titleView.text = bookTitle
                        storeView.text = storeName
                        pricesView.text = priceText
                        pricesView.visibility = if (priceText.isBlank()) View.GONE else View.VISIBLE
                        if (oldPrice != null && newPrice != null) {
                            if (isDrop) {
                                chip.text = getString(R.string.price_change_delta_down, formatPrice(oldPrice - newPrice))
                                chip.chipIcon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_arrow_down)
                                setChipColors(
                                    chip,
                                    com.google.android.material.R.attr.colorSurfaceContainerHigh,
                                    com.google.android.material.R.attr.colorSecondary,
                                    R.color.md_theme_light_surfaceContainerHigh,
                                    R.color.md_theme_light_secondary
                                )
                            } else {
                                chip.text = getString(R.string.price_change_delta_up, formatPrice(newPrice - oldPrice))
                                chip.chipIcon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_arrow_up)
                                setChipColors(
                                    chip,
                                    com.google.android.material.R.attr.colorSurfaceContainerHigh,
                                    com.google.android.material.R.attr.colorTertiary,
                                    R.color.md_theme_light_surfaceContainerHigh,
                                    R.color.md_theme_light_tertiary
                                )
                            }
                        } else {
                            chip.visibility = View.GONE
                        }
                        container.addView(row)
                    }
                }

                if (failures != null) {
                    for (i in 0 until failures.length()) {
                        val item = failures.getJSONObject(i)
                        val row = layoutInflater.inflate(R.layout.item_price_change, container, false)
                        val titleView = row.findViewById<TextView>(R.id.changeTitle)
                        val storeView = row.findViewById<TextView>(R.id.changeStore)
                        val messageView = row.findViewById<TextView>(R.id.changePrices)
                        val chip = row.findViewById<Chip>(R.id.changeChip)

                        titleView.text = item.optString("bookTitle")
                        storeView.text = item.optString("storeName")
                        messageView.text = item.optString("errorMessage")
                        messageView.setTextColor(
                            MaterialColors.getColor(
                                requireContext(),
                                androidx.appcompat.R.attr.colorError,
                                ContextCompat.getColor(requireContext(), R.color.md_theme_light_tertiary)
                            )
                        )
                        chip.setText(R.string.update_result_failed)
                        chip.chipIcon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_error)
                        setChipColors(
                            chip,
                            com.google.android.material.R.attr.colorErrorContainer,
                            com.google.android.material.R.attr.colorOnErrorContainer,
                            R.color.md_theme_light_errorContainer,
                            R.color.md_theme_light_onErrorContainer
                        )
                        container.addView(row)
                    }
                }

                if ((arr == null || arr.length() == 0) && (failures == null || failures.length() == 0)) {
                    summaryView.visibility = View.GONE
                    val tv = TextView(requireContext()).apply {
                        text = getString(R.string.summary_no_changes)
                        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                    }
                    container.addView(tv)
                }
            } catch (_: Exception) {
                summaryView.visibility = View.GONE
                val tv = TextView(requireContext()).apply {
                    text = getString(R.string.summary_no_changes)
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                }
                container.addView(tv)
            }
        }

        view.findViewById<Button>(R.id.dismissButton).setOnClickListener {
            // Clear stored recent changes on dismiss
            viewLifecycleOwner.lifecycleScope.launch { AutoUpdatePreferences.setRecentChangesJson(requireContext(), null) }
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        DialogStyling.apply(dialog)
    }

    private fun formatPrice(price: Double): String = currencyFormatter.format(price)

    private fun setChipColors(
        chip: Chip,
        backgroundAttr: Int,
        foregroundAttr: Int,
        fallbackBackgroundRes: Int,
        fallbackForegroundRes: Int
    ) {
        val backgroundColor = MaterialColors.getColor(
            chip.context,
            backgroundAttr,
            ContextCompat.getColor(chip.context, fallbackBackgroundRes)
        )
        val foregroundColor = MaterialColors.getColor(
            chip.context,
            foregroundAttr,
            ContextCompat.getColor(chip.context, fallbackForegroundRes)
        )
        chip.chipBackgroundColor = ColorStateList.valueOf(backgroundColor)
        chip.setTextColor(foregroundColor)
        chip.chipIconTint = ColorStateList.valueOf(foregroundColor)
    }

    companion object {
        private const val ARG_JSON = "json"
        fun newInstance(json: String): RecentPriceChangesDialogFragment {
            val f = RecentPriceChangesDialogFragment()
            f.arguments = Bundle().apply { putString(ARG_JSON, json) }
            return f
        }
    }
}


