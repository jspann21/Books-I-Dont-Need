package com.booktracker.booksidntneed.ui.dialog

import android.os.Bundle
import android.view.LayoutInflater
import androidx.fragment.app.DialogFragment
import com.booktracker.booksidntneed.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.widget.RadioGroup
import com.google.android.material.button.MaterialButton

class SortDialogFragment : DialogFragment() {
    
    private var onSortOptionSelected: ((String) -> Unit)? = null
    
    companion object {
        private const val ARG_CURRENT_SORT = "current_sort"
        
        fun newInstance(currentSort: String): SortDialogFragment {
            return SortDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_CURRENT_SORT, currentSort)
                }
            }
        }
    }
    
    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val currentSort = arguments?.getString(ARG_CURRENT_SORT) ?: "title"

        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_sort, null)
        val sortOptionsGroup = dialogView.findViewById<RadioGroup>(R.id.sortOptionsGroup)
        val checkedId = when (currentSort) {
            "title" -> R.id.sortTitle
            "author" -> R.id.sortAuthor
            "date" -> R.id.sortDateAdded
            "price" -> R.id.sortPrice
            else -> R.id.sortTitle
        }
        sortOptionsGroup.check(checkedId)
        sortOptionsGroup.setOnCheckedChangeListener { _, id ->
            val sortOption = when (id) {
                R.id.sortTitle -> "title"
                R.id.sortAuthor -> "author"
                R.id.sortDateAdded -> "date"
                R.id.sortPrice -> "price"
                else -> "title"
            }
            onSortOptionSelected?.invoke(sortOption)
            dismiss()
        }
        dialogView.findViewById<MaterialButton>(R.id.cancelButton).setOnClickListener { dismiss() }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .create()
        dialog.setOnShowListener {
            DialogStyling.apply(dialog)
        }
        return dialog
    }
    
    fun setOnSortOptionSelectedListener(listener: (String) -> Unit) {
        onSortOptionSelected = listener
    }
} 
