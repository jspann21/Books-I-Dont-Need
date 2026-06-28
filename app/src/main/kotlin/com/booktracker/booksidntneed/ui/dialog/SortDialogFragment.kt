package com.booktracker.booksidntneed.ui.dialog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import com.booktracker.booksidntneed.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

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
        
        val sortOptions = arrayOf("Title", "Author", "Date Added", "Price")
        val currentIndex = when (currentSort) {
            "title" -> 0
            "author" -> 1
            "date" -> 2
            "price" -> 3
            else -> 0
        }
        
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle("Sort Books")
            .setSingleChoiceItems(sortOptions, currentIndex) { _, which ->
                val sortOption = when (which) {
                    0 -> "title"
                    1 -> "author"
                    2 -> "date"
                    3 -> "price"
                    else -> "title"
                }
                onSortOptionSelected?.invoke(sortOption)
                dismiss()
            }
            .setNegativeButton("Cancel") { _, _ ->
                dismiss()
            }
            .create()
    }
    
    fun setOnSortOptionSelectedListener(listener: (String) -> Unit) {
        onSortOptionSelected = listener
    }
} 