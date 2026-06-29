package com.booktracker.booksidntneed.ui.dialog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.booktracker.booksidntneed.R
import com.booktracker.booksidntneed.utils.AutoUpdatePreferences
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject

class RecentPriceChangesDialogFragment : DialogFragment() {

    // Use lifecycleScope to avoid leaking a custom scope

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
        val json = arguments?.getString(ARG_JSON) ?: ""
        if (json.isNotBlank()) {
            try {
                val obj = JSONObject(json)
                val arr = obj.optJSONArray("changes")
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
                        val line = when {
                            oldPrice != null && newPrice != null ->
                                "$bookTitle — $storeName: $" + String.format("%.2f", oldPrice) + " → $" + String.format("%.2f", newPrice)
                            newPrice != null -> "$bookTitle — $storeName: $" + String.format("%.2f", newPrice)
                            else -> "$bookTitle — $storeName"
                        }
                        val row = layoutInflater.inflate(R.layout.item_price_change, container, false)
                        val titleView = row.findViewById<TextView>(R.id.changeText)
                        val chip = row.findViewById<com.google.android.material.chip.Chip>(R.id.changeChip)
                        titleView.text = line
                        if (oldPrice != null && newPrice != null) {
                            if (isDrop) {
                                chip.text = "−$" + String.format("%.2f", (oldPrice - newPrice))
                                chip.setChipBackgroundColorResource(R.color.md_theme_light_secondaryContainer)
                                chip.setTextColor(resources.getColor(R.color.md_theme_light_onSecondaryContainer, null))
                                chip.chipIcon = resources.getDrawable(R.drawable.ic_arrow_down, null)
                                chip.chipIconTint = resources.getColorStateList(R.color.md_theme_light_onSecondaryContainer, null)
                            } else {
                                chip.text = "+$" + String.format("%.2f", (newPrice - oldPrice))
                                chip.setChipBackgroundColorResource(R.color.md_theme_light_tertiaryContainer)
                                chip.setTextColor(resources.getColor(R.color.md_theme_light_onTertiaryContainer, null))
                                chip.chipIcon = resources.getDrawable(R.drawable.ic_arrow_up, null)
                                chip.chipIconTint = resources.getColorStateList(R.color.md_theme_light_onTertiaryContainer, null)
                            }
                        } else {
                            chip.visibility = View.GONE
                        }
                        container.addView(row)
                    }
                } else {
                    val tv = TextView(requireContext()).apply {
                        text = getString(R.string.summary_no_changes)
                        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                    }
                    container.addView(tv)
                }
            } catch (_: Exception) {
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

    companion object {
        private const val ARG_JSON = "json"
        fun newInstance(json: String): RecentPriceChangesDialogFragment {
            val f = RecentPriceChangesDialogFragment()
            f.arguments = Bundle().apply { putString(ARG_JSON, json) }
            return f
        }
    }
}


